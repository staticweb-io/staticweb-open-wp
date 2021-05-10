(ns io.staticweb.static-wp-daemon.init-deploy
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer (sh)]
            [io.staticweb.static-wp-daemon.mysql :as mysql
             :refer (mysql-real-escape-string-quote+ q)]
            [io.staticweb.static-wp-daemon.util :as util]
            [selmer.parser :as selmer]))

(def ^:const php-versions ["7.4" "8.0"])

(defn get-wordpress-config [db-password]
  (let [template (slurp (io/resource "io/staticweb/static-wp-daemon/wp-config-template.php"))]
    (selmer/render template
      {:DB_PASSWORD db-password
       :AUTH_KEY (util/wp-config-key)
       :SECURE_AUTH_KEY (util/wp-config-key)
       :LOGGED_IN_KEY (util/wp-config-key)
       :NONCE_KEY (util/wp-config-key)
       :AUTH_SALT (util/wp-config-key)
       :SECURE_AUTH_SALT (util/wp-config-key)
       :LOGGED_IN_SALT (util/wp-config-key)
       :NONCE_SALT (util/wp-config-key)})))

(defn write-nginx-sites [{:keys [cloudfront-auth-header] :as init}]
  (let [template (slurp (io/resource "io/staticweb/static-wp-daemon/wordpress-php-template"))
        local-template (slurp (io/resource "io/staticweb/static-wp-daemon/wordpress-php-local-template"))]
      (doseq [php-version php-versions]
        (spit (str "/etc/nginx/sites-available/wordpress-php-" php-version)
          (selmer/render template
            {:auth-header cloudfront-auth-header
             :php-version php-version}))
        (spit (str "/etc/nginx/sites-available/wordpress-php-local-" php-version)
          (selmer/render local-template
            {:php-version php-version})))))

(defn link-default-nginx-sites []
  (when (= 2 (:exit (sh "ls" "/etc/nginx/sites-enabled/wordpress")))
    (sh "ln" "-s"
      (str "/etc/nginx/sites-available/wordpress-php-" (first php-versions))
      "/etc/nginx/sites-enabled/wordpress"))
  (when (= 2 (:exit (sh "ls" "/etc/nginx/sites-enabled/wordpress-local")))
    (sh "ln" "-s"
      (str "/etc/nginx/sites-available/wordpress-php-local-" (first php-versions))
      "/etc/nginx/sites-enabled/wordpress-local")))

(defn set-mysql-user-password [username old-pass new-pass]
  (sh "/usr/bin/mysql"
    (str "--password=" old-pass)
    "-u" username
    "-e" (str "SET password=PASSWORD('" new-pass "');")))

(defn set-db-passwords [db new-wordpress-pass new-root-pass]
  (println "Setting MySQL root user password")
  (let [{:keys [err exit] :as result}
        #__ (set-mysql-user-password "root"
              (:root-password db) new-root-pass)]
    (if-not (zero? exit)
      (throw (ex-info (str "Failed setting password: " err)
               {:result result}))
      (do (println "Setting MySQL wordpress user password")
          (let [{:keys [err exit] :as result}
                #__ (set-mysql-user-password "wordpress"
                      (:password db) new-wordpress-pass)]
            (when-not (zero? exit)
              (throw (ex-info (str "Failed setting password: " err)
                       {:result result}))))))))

(defn set-db-values [config init]
  (let [{:keys [db]} config
        new-db (assoc db
                 :password (util/wp-generate-password 32)
                 :root-password (util/wp-generate-password 32))
        new-config (assoc config :db new-db)
        {:keys [bucket-name cloudfront-distribution cloudfront-domain-name
                region user-pass]} init]
    (write-nginx-sites init)
    (link-default-nginx-sites)
    (sh "nginx" "-s" "reload")
    (q db
      (str "UPDATE wp_users SET user_pass=\""
        (mysql-real-escape-string-quote+ user-pass)
        "\" WHERE ID=1"))
    (q db
      (str "UPDATE wp_wp2static_core_options SET value=\"https://"
        (mysql-real-escape-string-quote+ cloudfront-domain-name)
        "\" WHERE name=\"deploymentURL\""))
    (q db
      (str "UPDATE wp_wp2static_addon_s3_options SET value=\""
        (mysql-real-escape-string-quote+ bucket-name)
        "\" WHERE name=\"s3Bucket\""))
    (q db
      (str "UPDATE wp_wp2static_addon_s3_options SET value=\""
        (mysql-real-escape-string-quote+ cloudfront-distribution)
        "\" WHERE name=\"cfDistributionID\""))
    (q db
      (str "UPDATE wp_wp2static_addon_s3_options SET value=\""
        (mysql-real-escape-string-quote+ region)
        "\" WHERE name=\"cfRegion\" OR name=\"s3Region\""))
    (q db
      (str "UPDATE wp_wp2static_addon_advanced_crawling_options"
        " SET blob_value=\""
        (mysql-real-escape-string-quote+ "localhost\n")
        "\" WHERE name=\"additionalHostsToRewrite\""))
    (spit "/home/staticweb/wordpress/wp-config.php"
      (get-wordpress-config (:password new-db)))
    (sh "chown" "staticweb:staticweb" "/home/staticweb/wordpress/wp-config.php")
    (set-db-passwords db (:password new-db) (:root-password new-db))
    (util/write-config.edn new-config)))

(defn -main []
  (set-db-values (util/get-config) (util/get-init)))

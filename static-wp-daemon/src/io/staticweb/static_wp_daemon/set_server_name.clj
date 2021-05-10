(ns io.staticweb.static-wp-daemon.set-server-name
  (:require [cheshire.core :as json]
            [clojure.java.shell :refer (sh)]
            [clojure.string :as str]
            [io.staticweb.static-wp-daemon.mysql :as mysql
             :refer (mysql-real-escape-string-quote+ q)]
            [io.staticweb.static-wp-daemon.util :as util]))

(defn create-wp2static-jobs [db]
  (doseq [t ["detect" "crawl" "post_process" "deploy"]]
    (q db
      (str "INSERT INTO wp_wp2static_jobs (created_at, job_type, status) "
      "VALUES (NOW(), \"" t "\", \"waiting\")"))))

(defn current-deployment-url [db]
  (-> db
    (q "SELECT value FROM wp_wp2static_core_options WHERE name=\"deploymentURL\"")
    first
    (get "value")))

(defn outputs-map [outputs-vec]
  (into {}
    (map (juxt :OutputKey :OutputValue) outputs-vec)))

(defn get-server-name [stack]
  (-> stack :Outputs outputs-map (get "WPServerDomainName")))

(defn get-hosts-to-rewrite [db]
  (some-> db
    (q "SELECT blob_value FROM wp_wp2static_addon_advanced_crawling_options WHERE name=\"additionalHostsToRewrite\"")
    first
    (get "blob_value")
    (str/split #"\n")))

(defn add-host-to-rewrite [db host]
  (let [lhost (str/lower-case host)
        hosts (get-hosts-to-rewrite db)]
    (when-not (some #(= (str/lower-case %) lhost) hosts)
      (q db
        (str "UPDATE wp_wp2static_addon_advanced_crawling_options"
          " SET blob_value=\""
          (->> (concat hosts [lhost])
            (str/join "\n")
            mysql-real-escape-string-quote+)
          "\" WHERE name=\"additionalHostsToRewrite\"")))))

(defn set-server-name [config server-name]
  (add-host-to-rewrite (:db config) server-name)
  (util/write-config.edn (assoc config :wp-domain-name server-name)))

(defn update-server-name-loop [init]
  (let [{:keys [db wp-domain-name] :as config} (util/get-config)
        {:keys [create-jobs? region stack-id]} init
        deployment-url (current-deployment-url db)]
    (if (seq wp-domain-name)
      (println "Server name already set to \"" wp-domain-name "\". Exiting.")
      (let [r (sh "aws" "cloudformation" "describe-stacks"
                "--stack-name" stack-id "--region" region)]
        (if-not (zero? (:exit r))
          (do
            (println "Error describing stack:" (:err r))
            (Thread/sleep 30000)
            (recur init))
          (let [stack (json/parse-string (:out r) true)
                server-name (-> stack :Stacks first get-server-name)]
            (if (empty? server-name)
              (do
                (Thread/sleep 30000)
                (recur init))
              (when create-jobs?
                (set-server-name config server-name)
                (create-wp2static-jobs db)))))))))

(defn -main []
  (update-server-name-loop (util/get-init)))

(ns io.staticweb.static-wp-daemon.shutdown
  (:require [clojure.java.shell :refer (sh)]
            [clojure.string :as str]
            [io.staticweb.static-wp-daemon.util :as util]))

(defn uptime-since []
  (str/trim (util/sh-out "uptime" "-s")))

(defn system-start-time-in-sec []
  (util/sh-long "date" "--date" (uptime-since) "+%s"))

(defn access-log-mtime []
  (util/sh-long "stat" "/var/log/nginx/access.log" "--format" "%Y"))

(defn now-sec []
  (quot (System/currentTimeMillis) 1000))

(defn system-uptime []
  (- (now-sec) (system-start-time-in-sec)))

(defn shutdown-if-idle! []
  (when-let [idle-seconds (:idle-seconds (util/get-config))]
    (if-not (and (number? idle-seconds) (not (neg? idle-seconds)))
      (throw
        (ex-info
          ":idle-seconds must be >= 0"
          {:idle-seconds idle-seconds}))
      (when (pos? idle-seconds)
        (let [uptime-sec (system-uptime)]
          (when (<= idle-seconds uptime-sec)
            (let [m-sec (- (now-sec) (access-log-mtime))]
              (when (and (<= idle-seconds m-sec)
                      (str/blank? (util/sh-out "who")))
                (println "Idle for" m-sec "seconds, calling for shutdown.")
                (sh "sudo" "systemctl" "halt")))))))))

(defn -main []
  (shutdown-if-idle!))

(ns io.staticweb.static-wp-daemon.util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh :refer (sh)]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]))

(defn get-config []
  (edn/read-string (slurp "/opt/staticweb/config.edn")))

(defn get-init []
  (edn/read-string (slurp "/opt/staticweb/init-deploy.edn")))

(defn write-config.edn [config]
  (with-open [w (io/writer "/opt/staticweb/config.edn")]
    (pprint config w)))

(defn rand-bytes ^bytes [n]
  {:pre [(number? n)]}
  (let [arr (byte-array n)]
    (.nextBytes
      (java.security.SecureRandom/getInstance "SHA1PRNG")
      arr)
    arr))

(defn wp-config-key []
  (let [^bytes raw (rand-bytes 32)]
    (format "%064x" (BigInteger. 1 raw))))

(def secure-random (java.security.SecureRandom.))

(defn ^Long secure-rand-int [^Long n]
  (int
    (* n (.nextDouble secure-random))))

(defn choice [xs]
  (if (seq xs)
    (nth xs (secure-rand-int (count xs)))
    nil))

(def wp-generate-password-chars
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")

(defn wp-generate-password
  "https://github.com/WordPress/WordPress/blob/251d77e1a1a4bc70e79200784c35158546281229/wp-includes/pluggable.php#L2412"
  [n]
  (apply str
    (repeatedly n #(choice wp-generate-password-chars))))

(defn sh-out [& args]
  (let [result (apply sh/sh args)]
    (if (zero? (:exit result))
      (:out result)
      (throw
        (ex-info
          (str "Shell call returned non-zero exit code: " (:exit result))
          {:result result})))))

(defn sh-long [& args]
  (-> (apply sh-out args) str/trim Long/parseLong))

(ns io.staticweb.static-wp-daemon.mysql
  (:require [clojure.data.xml :as xml]
            [clojure.java.shell :refer (sh)]))

; https://mariadb.com/kb/en/string-literals/
(def mysql-escape-non-ansi-replacements
  {\u0000 "\\0"
   \' "\\'"
   \" "\\\""
   \backspace "\\b"
   \newline "\\n"
   \return "\\r"
   \tab "\\t"
   \u001a "\\Z"
   \\ "\\\\"
   \% "\\%"
   \_ "\\_"})

(defn mysql-escape-non-ansi [^String s]
  {:pre [(string? s)]}
  (apply str
    (map #(mysql-escape-non-ansi-replacements % %) s)))

; https://dev.mysql.com/doc/c-api/8.0/en/mysql-real-escape-string-quote.html
; Also remove \backspace because it causes errors and I'm not sure if it
; can cause security issues.
(def mysql-real-escape-string-quote+-replacements
  {\u0000 "\\0"
   \' "\\'"
   \" "\\\""
   \newline "\\n"
   \return "\\r"
   \u001a "\\Z"
   \\ "\\\\"
   \` "\\`"
   \backspace ""})

(defn mysql-real-escape-string-quote+ [^String s]
  {:pre [(string? s)]}
  (apply str
    (map #(mysql-real-escape-string-quote+-replacements % %) s)))

;; map2arg & make-mysql-command adapted from https://github.com/bherrmann7/babashka/blob/bb0b8d60481f24d6c2a833b7d583ee2d3b82b0f9/examples/mysql_cmdline.clj

;; (map2arg mdb "-h" :host) => "-hlocalhost"
(defn map2arg
  "Create mysql command line argument from connection map"
  [mdb arg key]
  (when-let [v (get mdb key)]
    (str arg v)))

(defn make-mysql-command
  "Create mysql command line using connection map and statement"
  [mdb statement]
  (filterv seq
    ["mysql"
     (map2arg mdb "-h" :host)
     (map2arg mdb "-u" :user)
     (map2arg mdb "-p" :password)
     (:dbname mdb)
     "--xml"
     "-e" statement ]))

(defn mysql-exec [mdb statement]
  (let [mysql-command (make-mysql-command mdb statement)]
    (let [result (apply sh mysql-command)]
      (if (zero? (:exit result))
        result
        (throw (ex-info (:err result) {:result result}))))))

(defn row->map [{:keys [content]}]
  (->> content
    (filter map?)
    (reduce
      #(assoc %
         (get-in %2 [:attrs :name])
         (first (:content %2)))
      nil)))

(defn resultset->map [resultset]
  (->> resultset :content
    (filter map?)
    (map row->map)))

(defn q [mdb statement]
  (let [result (mysql-exec mdb statement)
        s (:out result)]
    (when (seq s)
      (-> s xml/parse-str resultset->map))))

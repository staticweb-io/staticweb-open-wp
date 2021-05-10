(ns io.staticweb.template-page
  (:require [cljs-http.client :as http]
            ["crypto-js/core" :as crypto-js]
            ["crypto-js/md5" :as md5]
            ["uuid" :as uuid]))

; PHPass Portable Hash
; https://passlib.readthedocs.io/en/stable/lib/passlib.hash.phpass.html
; $P$B indicates a portable hash with 13 rounds
;   (.indexOf itoa64-chars "B") = 13

; The characters used in PHPass's brand of base 64 encoding
(def ^:const itoa64-chars
  "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

(defn as-unsigned-byte [n]
  (if (neg? n) (+ 256 n) n))

(defn itoa64-char [n]
  (.charAt itoa64-chars (as-unsigned-byte n)))

(defn itoa64-encode [bytes]
  (when (seq bytes)
    (let [ct (alength bytes)
          add-char #(->> (bit-shift-right %2 %3) (bit-and 63) itoa64-char
                      (conj %))]
      (loop [v (as-unsigned-byte (aget bytes 0))
             r (add-char [] v 0)
             i 1]
        (if (>= i ct)
          (apply str (add-char r v 6))
          (let [v (-> (aget bytes i) as-unsigned-byte (bit-shift-left 8)
                    (bit-or v))
                r (add-char r v 6)
                i (inc i)]
            (if (= i ct)
              (apply str (add-char r v 12))
              (let [v (-> (aget bytes i) as-unsigned-byte (bit-shift-left 16)
                        (bit-or v))
                    r (add-char r v 12)
                    r (add-char r v 18)
                    i (inc i)]
                (if (= i ct)
                  (apply str r)
                  (let [v (as-unsigned-byte (aget bytes i))]
                    (recur v (add-char r v 0) (inc i))))))))))))

(defn rand-salt []
  (-> (js/Uint8Array. 6)
    js/window.crypto.getRandomValues
    itoa64-encode))

(defn base16-decode [s]
  (->> (re-seq #".{1,2}" s)
    (map #(js/parseInt % 16))
    js/Uint8Array.))

(defn base16->itoa64 [s]
  (-> s base16-decode itoa64-encode))

(defn str->WordArray [s]
  (let [enc (.encode (js/TextEncoder.) s)]
    (-> (map (fn [[a b c d]]
               (+ (* 16777216 a)
                 (* 65536 b)
                 (* 256 c)
                 d))
          (partition 4 4 [0 0 0]
            enc))
      into-array
      (crypto-js/lib.WordArray.create (.-length enc)))))

(defn hash-password [password & [rounds salt]]
  {:pre [(or (nil? rounds) (<= 4 rounds 31))]}
  (let [rounds (or rounds 13)
        salt (or salt (rand-salt))
        pass (str->WordArray password)]
    (loop [i (Math/pow 2 rounds)
           hash (md5 (str salt password))]
      (if (zero? i)
        (str "\\$P\\$" (nth itoa64-chars rounds) salt (base16->itoa64 (str hash)))
        (recur (dec i) (md5 (.concat hash pass)))))))

(defn set-auth-header []
  (-> js/document (.getElementById "cloudfront-authorization-header-value")
    .-textContent
    (set! (str "Bearer " (uuid/v4)))))

(defn set-launch-stack-link []
  (let [base "https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/quickcreate"
        params {:stackName (-> js/document
                             (.getElementById "stack-name")
                             .-value)
                :templateURL (-> js/document
                             (.getElementById "template-url")
                             .-value)
                :param_CloudFrontAuthorizationHeader
                (-> js/document
                  (.getElementById "cloudfront-authorization-header-value")
                  .-textContent)
                :param_UserPass
                (when (-> js/document (.getElementById "wordpress-user-password")
                        .-value seq)
                  (-> js/document
                    (.getElementById "user-pass-value")
                    .-textContent))}]
    (-> js/document (.getElementById "launch-stack-link") .-href
      (set! (str base "?" (http/generate-query-string params))))))

(defn set-password-hash []
  (let [txt (-> js/document (.getElementById "wordpress-user-password")
            .-value)
        hash (when (seq txt) (hash-password txt))]
    (set! (-> js/document (.getElementById "user-pass-value") .-textContent)
      (or hash "Please enter a password."))
    (set-launch-stack-link)))

(defn check-can-launch [])

(defn init []
  (-> js/document (.getElementById "wordpress-user-password")
    .-onchange
    (set! set-password-hash))
  (-> js/document (.getElementById "wordpress-user-password")
    .-onkeydown
    (set! set-password-hash))
  (-> js/document (.getElementById "launch-stack-link")
    .-onkeydown
    (set! set-password-hash))
  (set-auth-header)
  (set-password-hash))


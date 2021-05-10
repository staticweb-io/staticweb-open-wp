(ns io.staticweb.template-page
  (:require [cljs-http.client :as http]
            [reagent.core :as r]
            [reagent.dom :as rd]
            ["crypto-js/core" :as crypto-js]
            ["crypto-js/md5" :as md5]
            ["@material-ui/core" :as mui]
            ["react-password-strength-bar" :default PasswordStrengthBar]
            ["uuid" :as uuid]
            ["zxcvbn" :as zxcvbn]))

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

(defn rand-salt [& [n-bytes]]
  (-> (js/Uint8Array. (or n-bytes 6))
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

(def extra-password-dict-words
  #js["initial" "open" "source" "static" "staticweb" "staticweb.io"
      "user" "wordpress" "wp"])
(def quick-create-base "https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/quickcreate")

; https://cljdoc.org/d/reagent/reagent/1.0.0/doc/frequently-asked-questions/how-do-i-use-react-s-refs-
(defn Popper [{:keys [anchor-ref props]} & [children]]
  (when-let [anchorEl @anchor-ref]
    [:> mui/Popper (assoc props :anchorEl anchorEl)
     [:<>
      children]]))

(defn CopyButton []
  (let [anchor-ref (clojure.core/atom nil)
        open? (r/atom false)]
    (fn [{:keys [input-id]}]
      [:<>
       [Popper {:anchor-ref anchor-ref
                :props {:open @open?
                        :placement "top-start"}}
        [:div {:class "popper"
               :on-click #(reset! open? false)
               :tab-index 0}
         "Copied!"]]
       [:button {:on-click #(when-let [el (js/document.getElementById input-id)]
                              (.select el)
                              (.setSelectionRange el 0 99999)
                              (js/document.execCommand "copy")
                              (swap! open? not))
                 :ref #(reset! anchor-ref %)}
        "\ud83d\udccb"]])))

(defn strong-password []
  (loop [n-bytes 8]
    (let [password (rand-salt n-bytes)]
      (if (>= (.-score (zxcvbn password)) 4)
        password
        (recur (inc n-bytes))))))

(defn PasswordInput []
  (let [password-id (str (gensym "G__PasswordInput"))]
    (fn [{:keys [on-change on-random password score user-inputs]}]
      [:div {:class "wordpress-user-password-container"}
       [:div
        [:label {:for password-id}
         "Initial WordPress user password:"]
        [:input {:id password-id
                 :on-change on-change
                 :style {:background-color ({0 "#ff5846"
                                             1 "#ff5846"
                                             2 "#ffc45d"
                                             3 "#3ba0ff"
                                             4 "#35d291"}
                                            score)}
                 :value password}]
        [CopyButton {:input-id password-id}]
        [:button {:on-click on-random}
         "\uD83C\uDFB2"]]
       [:> PasswordStrengthBar
        {:password password
         :userInputs (if (array? user-inputs)
                       user-inputs
                       (into-array user-inputs))}]
       [:p "Please write this password down. You will need it to log in to WordPress. The username is always "
        [:b "user"] "."]
       [:p "You should change the password immediately after you log in for the first time."
        " You can change the username then as well."]])))

(defn launch-stack-link [{:keys [auth-header stack-name template-url user-pass]}]
  (let [base "https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/quickcreate"
        params {:stackName stack-name
                :templateURL template-url
                :param_CloudFrontAuthorizationHeader auth-header
                :param_UserPass user-pass}]
    (str base "?" (http/generate-query-string params))))

(defn LaunchStack []
  (let [anchor-ref (clojure.core/atom nil)
        open? (r/atom false)]
    (fn [{:keys [user-pass] :as params}]
      [:div
       [Popper {:anchor-ref anchor-ref
                :props {:open @open?
                        :placement "bottom-start"}}
        [:div {:class "popper"
               :on-click #(reset! open? false)}
         "Please enter a strong password."]]
       [:a {:href (when (seq user-pass) (launch-stack-link params))
            :ref #(reset! anchor-ref %)
            :target "_blank"}
        [:img {:on-click #(when (empty? user-pass)
                            (swap! open? not))
               :src "https://s3.amazonaws.com/cloudformation-examples/cloudformation-launch-stack.png"}]]])))

(defn TemplateParameters []
  (let [auth-header-id (str (gensym "G__AuthHeader"))
        state (r/atom {:show-params? false})
        user-pass-id (str (gensym "G__UserPass"))]
    (fn [{:keys [auth-header user-pass] :as params}]
      (let [{:keys [show-params?]} @state]
        [:div {:class "launch-stack-container"}
         [:div
          [:h3 "Launch Stack"]]
         [:div
          [LaunchStack params]]
         [:div
          [:button {:on-click #(swap! state update :show-params? not)}
           (if show-params?
             "Hide Parameters"
             "Show Parameters")]]
         (when show-params?
           [:div
            [:label {:for auth-header-id}
             "CloudFrontAuthorizationHeader:"]
            [:input {:id auth-header-id :read-only true :value auth-header}]
            [CopyButton {:input-id auth-header-id}]])
         (when show-params?
           [:div
            [:label {:for user-pass-id}
             "UserPass:"]
            [:input {:id user-pass-id
                     :read-only true
                     :style (when (empty? user-pass) {:background-color "rgb(255, 88, 70)"})
                     :value (or user-pass "Please enter a strong password.")}]
            [CopyButton {:input-id user-pass-id}]])]))))

(defn App []
  (let [state (r/atom {:auth-header (str "Bearer " (uuid/v4))
                       :password (strong-password)
                       :template-url (-> js/document
                                       (.getElementById "template-url")
                                       .-value)})]
    (fn []
      (let [{:keys [auth-header password template-url]} @state
            score (.-score (zxcvbn password extra-password-dict-words))]
        [:div {:style {:font-family "Inter var,system-ui,-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,Helvetica Neue,Arial,Noto Sans,sans-serif,Apple Color Emoji,Segoe UI Emoji,Segoe UI Symbol,Noto Color Emoji"}}
         [PasswordInput {:on-change #(swap! state assoc :password
                                       (.-value (.-target %)))
                         :on-random #(swap! state assoc :password
                                       (strong-password))
                         :password password
                         :score score
                         :user-inputs extra-password-dict-words}]
         [TemplateParameters {:auth-header auth-header
                              :stack-name "StaticWeb-WordPress"
                              :template-url template-url
                              :user-pass (when (< 1 score)
                                           (hash-password password))}]]))))

(defn ^:export ^:dev/after-load init []
  (rd/render [App]
    (js/document.getElementById "reagent-mount")))


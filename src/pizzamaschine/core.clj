;;;; Pizza plugin for the benevolent Subraum-AI
;;;; TODO:
;;;; - announce closing of the order in #c3pb

(ns pizzamaschine.core
  (:require [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.consumers :as lc]
            [langohr.basic :as lb]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:gen-class))

(defn make-url []
  (get (System/getenv)
       "AMQP_SERVER" "amqp://guest:guest@localhost"))

(def +state-path+ (or (System/getenv "EDI_PIZZA_FILE") "/tmp/pizza.edn"))

(def +help-message+
  (str
    "!pizza-reset      -- Leert die Liste mit Bestellungen\n"
    "!pizza-list       -- Listet Bestellungen\n"
    "!pizza-help       -- Diese Nachricht\n"
    "!pizza $something -- Bestellt $something"))

(defn now []
(defn emit-cmd [ch &{:as body}]
  {:pre [(contains? body :cmd)
         (contains? body :args)
         (contains? body :user)
         (contains? body :src)]}

  (let [json (json/write-str body)]
    (lb/publish ch "cmd" (:cmd body) json :content-type "application/json")))

(defn emit-msg-reply [ch src &{:as body}]
  {:pre [(contains? body :msg) (re-matches #".*\.recv\..*" src)]}

  (let [dst (str/replace src #"recv" "send")
        json (json/write-str body)]
    (lb/publish ch "msg" dst json :content-type "application/json")))

  (java.util.Date.))

(def +orders+ (ref '{}))
(def +first-order+ (ref ""))
(def +started-by+ (ref ""))

(defn store-state
  ([] (store-state +state-path+))
  ([path]
   (try
     (dosync
       (spit path
             (str (json/write-str
                    {"orders" @+orders+
                     "first-order" @+first-order+
                     "started-by" @+started-by+})
                  "\n"))
       true)
     (catch Exception e
       (println (str "Could not store state in " path ":\n"
                     (.getMessage e)))
       false))))

(defn load-state!
  ([] (load-state! +state-path+))
  ([path]
   (dosync
     (try
       (let [data (json/read-str (slurp path))]
         (alter +orders+
                (fn [x] (get data "orders")))
         (alter +first-order+
                (fn [x] (get data "first-order")))
         (alter +started-by+
                (fn [x] (get data "started-by")))
         true)
       (catch Exception e
         (println (str "Could not load state from " path ":\n"
                       (.getMessage e)))
         false)))))

(defn list-orders []
  (dosync
    (if (empty? @+orders+)
      "Keine Bestellungen :/"
      (str
        (apply str
               (interpose "\n"
                          (map #(str (first %) ": "
                                     (first (second %)) " at "
                                     (second (second %))
                                     )
                               @+orders+)))
        "\n(gestartet: " @+first-order+
        " von " @+started-by+ ")\n"
        ))))

(defn add-order! [user order]
  (dosync
    (when (empty? @+first-order+)
      (alter +first-order+ (fn [x] (str (now))))
      (alter +started-by+ (fn [x] user)))
    (alter +orders+ #(assoc % user (list order (str (now)))))
    (store-state)
    (str "Ack: "
         order)))

(defn reset-orders! []
  (dosync
    (let [o (list-orders)
          rv (str o
                  (when-not (empty? @+orders+)
                    (alter +first-order+ (fn [x] ""))
                    (alter +orders+ (fn [x] '{}))
                    (alter +started-by+ (fn [x] ""))
                    "\nBestelliste wurde geleert."))]
      (store-state)
      rv)))

(defn dispatch-command [msg]
  (let [args (:args msg)
        user (:user msg)
        cmd  (:cmd msg)]
    (cond
      (= cmd "pizza-reset")
      (reset-orders!)
      (= cmd "pizza-list")
      (list-orders)
      (or (= cmd "pizza-help")
          (empty? args))
      +help-message+
      :else
      (add-order! user args))))

(defn message-handler
  [ch {:keys [content-type delivery-tag] :as meta} ^bytes payload]
  (let [msg (json/read-str (String. payload "UTF-8")
                           :key-fn keyword)
        dst (.replace (:src msg) "recv" "send")
        reply (dispatch-command msg)]
    (println
      (str "[recv] " msg))
    (lb/publish ch "msg" dst
                (json/write-str {:user (:user msg) :msg reply})
                :content-type "application/json")
    (lb/ack ch delivery-tag)))

(defn setup
  ([] (setup false))
  ([blocking]
   (load-state!)
   (let [conn  (rmq/connect {:uri (make-url)})
         ch    (lch/open conn)
         qname (:queue (lq/declare ch))
         exchange "cmd"
         keys ["pizza" "pizza-reset" "pizza-list" "pizza-help"]]
     (doseq [k keys]
       (println "Binding to" exchange "/" k)
       (lq/bind ch qname exchange :routing-key k))
     (if blocking
       (lc/blocking-subscribe ch qname #'message-handler)
       (lc/subscribe ch qname #'message-handler))
     {:conn conn :channel ch})))

(comment
  (rmq/close (:conn *conn*))
  (def ^:dynamic *conn* (setup))
)

(defn -main [& args]
  (let [conn (setup true)]
    (rmq/close (:channel conn))
    (rmq/close (:conn conn))
    ))

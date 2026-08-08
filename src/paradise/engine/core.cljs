(ns paradise.engine.core
  (:require
   [clojure.string :as str]
   [cljs.core.async :refer [go <!]]
   [cljs-workers.worker :as worker]
   [goog.object :as gobj]
   [paradise.shared.utils.macros :refer [config]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [shadow.esm :refer [dynamic-import]]))

(defonce !registry (atom {}))

(defn register-engine! [id handler-map]
  (swap! !registry assoc (keyword id) (js->clj handler-map :keywordize-keys true)))

(defn get-engine-url [protocol]
  (when-let [paths (get config protocol)]
    (if ^boolean goog.DEBUG
      (:dev paths)
      (:prod paths))))

(defn get-fallback-bootstrap [protocol]
  (let [prop-name (str (str/capitalize protocol) "EngineBootstrap")]
    (gobj/get js/globalThis prop-name)))

(defn load-protocol-engine! [protocol]
  (go
    (try
      (let [url (get-engine-url protocol)]
        (if-not url
          (throw (js/Error. (str "Unknown protocol: " protocol)))
          (let [module (<p! (dynamic-import url))
                bootstrap-fn (or (.-bootstrap module)
                                 (.-init module)
                                 (some-> module .-default .-bootstrap)
                                 (some-> module .-default .-init)
                                 (get-fallback-bootstrap protocol))]
            (if bootstrap-fn
              (<! (bootstrap-fn register-engine!))
              (js/console.error "Engine module did not export a bootstrap function:" protocol)))))
      (catch js/Error e
        (js/console.error "External import failed:" e)))))

(worker/register :register-engine
                 (fn [payload]
                   (go
                     (try
                       (let [protocol-str (if (map? payload) (:engine-id payload) payload)]
                         (if protocol-str
                           (let [protocol-name (name protocol-str)]
                             (<! (load-protocol-engine! protocol-name))
                             (let [engine-map (get @!registry (keyword protocol-name))
                                   preload-fn (:preload engine-map)]
                               (when preload-fn
                                 (preload-fn))
                               {:status "success" :msg (str "Registered engine " protocol-name)}))
                           {:status "error" :msg "No engine provided."}))
                       (catch :default e
                         (js/console.error "Engine Registration Crash:" e)
                         {:status "error" :msg (.-message e)})))))

(worker/bootstrap)
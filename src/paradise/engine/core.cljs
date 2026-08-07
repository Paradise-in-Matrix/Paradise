(ns paradise.engine.core
  (:require [cljs.core.async :refer [go <!]]
            [cljs-workers.worker :as worker]
            [cljs-workers.mesh :as mesh]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defonce !registry (atom {}))

(defn register-engine! [id handler-map]
  (swap! !registry assoc (keyword id) (js->clj handler-map :keywordize-keys true)))

(defn fetch-engine-chunk! [url]
  (js* "import(~{})" url))

(defn load-protocol-engine! [protocol]
  (go
    (try
      (let [url (case protocol
                  "nostr" "http://localhost:8081/plugin.js"
                  "matrix"
                  "https://paradise-chat.github.io/Matrix-Engine/plugin.js"
                  (throw (js/Error. (str "Unknown protocol: " protocol))))

            module (<p! (fetch-engine-chunk! url))
            bootstrap-fn (.-bootstrap module)]

        (if bootstrap-fn
          (bootstrap-fn register-engine!)
          (js/console.error "Engine module did not export a bootstrap function:" protocol)))
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

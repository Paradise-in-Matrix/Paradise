(ns paradise.binding.core
  (:require [goog.object]
            [taoensso.timbre :as log]
            [cljs-workers.helpers :as workers-helpers]
            ))

(defonce !stream-handlers (atom {}))

(defn register-handlers!
  "Allows an engine plugin to register its UI stream listeners."
  [engine-id handlers]
  (swap! !stream-handlers update engine-id merge handlers))


(defonce !active-engine (atom nil))


(defn handle-worker-stream! [data]
  (let [is-map?       (map? data)
        msg-type      (if is-map? (:type data) (goog.object/get data "type"))
        raw-engine-id (if is-map? (get data :engine-id) (goog.object/get data "engine-id"))
        engine-id     (keyword (or raw-engine-id @!active-engine))
        get-val       (fn [k-str k-kw]
                        (if is-map? (get data k-kw) (goog.object/get data k-str)))]
    (if-let [handler (get-in @!stream-handlers [engine-id msg-type])]
      (handler data get-val)
      (when msg-type
        (log/warn "Unhandled stream event:" msg-type "for engine:" engine-id)))))

(defn set-active-engine! [engine-id]
  (reset! !active-engine engine-id))

(def internal-handlers #{:register-port :bind-app-db :register-engine})



(defn rpc-interceptor [payload]
  (if-let [handler (:handler payload)]
    (if (or (namespace handler)
            (internal-handlers handler)
            (nil? @!active-engine))
      payload
      (assoc payload :handler (keyword (name @!active-engine) (name handler))))
    payload)
  )

(defn init-middleware! []
  (workers-helpers/set-request-interceptor! rpc-interceptor))
(ns paradise.engine.binding
  (:require [cljs-workers.worker :as worker]))

(defn register!
  "Namespaces the handler under the given engine-id (e.g. :bootstrap becomes :matrix/bootstrap)"
  [engine-id handler-key handler-fn]
  (let [namespaced-key (keyword (name engine-id) (name handler-key))]
    (worker/register namespaced-key handler-fn)))

(defn stream!
  "Attaches the engine-id to the stream payload so the UI knows how to route it."
  [engine-id payload]
  (let [enriched (if (map? payload)
                   (assoc payload :engine-id (name engine-id))
                   (do (js/Object.assign payload #js {:engine-id (name engine-id)})
                       payload))]
    (worker/stream! enriched)))

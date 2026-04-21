(ns worker.state)

(defonce !client (atom nil))
(defonce !media-cache (atom {}))
(defonce !plugin-handlers (atom {}))
(defn register-handler [id handler-fn]
  (swap! !plugin-handlers assoc id handler-fn))

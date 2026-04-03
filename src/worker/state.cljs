(ns worker.state)

(defonce !client (atom nil))
(defonce !media-cache (atom {}))
(ns paradise.virtualizer.state)

(defonce !worker-components (atom {}))

(defonce !worker-overrides (atom {}))

(defonce !client (atom nil))

(defonce !shared-app-db (atom nil))

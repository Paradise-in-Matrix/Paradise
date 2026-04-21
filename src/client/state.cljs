(ns client.state
  (:require [reagent.core :as r]))

(defonce !engine-pool (atom nil))
(defonce !config (atom nil))
(defonce !components (r/atom {}))
(defonce !slots (r/atom {}))

(defn reg-slot-item [slot-id component]
  (swap! !slots update slot-id (fnil conj []) component))

(defn get-slot [slot-id]
  (get @!slots slot-id []))

(set! (.-dumpRegistry js/window) #(clj->js @!components))

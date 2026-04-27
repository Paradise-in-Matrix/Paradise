(ns client.state
  (:require [reagent.core :as r]))

(defonce !engine-pool (atom nil))
(defonce !media-pool (atom nil))
(defonce !config (atom nil))
(defonce !components (r/atom {}))
(defonce !active-overrides (r/atom {}))
(defonce !slots (r/atom {}))

(defn reg-slot-item [slot-id component]
  (swap! !slots update slot-id (fnil conj []) component))

(defn get-slot [slot-id]
  (->> (get @!slots slot-id [])
       (sort-by #(get % :order 0))
       (map (fn [{:keys [id component]}]
              ^{:key (or id (random-uuid))} [component]))))

(defn remove-plugin-overrides! [target-plugin-id]
  (let [target-str (name target-plugin-id)]
    (swap! !active-overrides
           (fn [overrides]
             (into {} (remove (fn [[_ override-data]]
                                (= (name (:plugin-id override-data)) target-str))
                              overrides))))))

(defn remove-plugin-slots! [target-plugin-id]
  (let [target-str (name target-plugin-id)]
    (swap! !slots
           (fn [slots]
             (into {}
                   (map (fn [[slot-id items]]
                          [slot-id (into [] (remove #(= (name (:plugin-id % "unknown")) target-str) items))])
                        slots))))))

(set! (.-dumpRegistry js/window) #(clj->js @!components))

(ns client.diff-handler
  (:require
   [taoensso.timbre :as log]
   [promesa.core :as p]
   [re-frame.core :as re-frame]))

(defn apply-matrix-diffs [current-items updates parse-fn]
  (let [initial-items (vec (or current-items []))]
    (p/loop [i 0
             items initial-items]
      (if (< i (.-length updates))
        (let [update (aget updates i)
              tag    (str (.-tag update))
              inner  (.-inner update)
              ]
          (p/let [new-items
                  (case tag
                    "Reset"     (p/let [p (p/all (map parse-fn (.-values inner)))]
                                  (vec p))
                    "Append" (let [raw-vals (or (.-values inner) inner)]
                               (p/let [new-vals (p/all (map parse-fn (js/Array.from raw-vals)))]
                                 (vec (concat items new-vals))))
                    "PushBack"  (p/let [p (parse-fn (.-value inner))]
                                  (conj items p))
                    "PushFront" (p/let [p (parse-fn (.-value inner))]
                                  (into [p] items))
                    "Clear"     []
                    "PopFront"  (vec (rest items))
                    "PopBack"   (if (empty? items) [] (pop items))
                    "Truncate"  (vec (take (.-length inner) items))
                    "Insert"    (p/let [p   (parse-fn (.-value inner))
                                        idx (.-index inner)]
                                  (vec (concat (subvec items 0 idx) [p] (subvec items idx))))
                    "Remove"    (let [idx (.-index inner)]
                                  (vec (concat (subvec items 0 idx) (subvec items (inc idx)))))
                    "Set"       (p/let [p   (parse-fn (.-value inner))
                                        idx (.-index inner)]
                                  (if (contains? items idx)
                                    (assoc items idx p)
                                    items))
                    items)]
            (p/recur (inc i) (vec (or new-items items)))))
        (vec items)))))

(defn apply-generic-diffs!
  "A universal mutex-locked diff processor for the Matrix Rust SDK."
  [{:keys [!mutex db-path parse-fn sync-event async-event updates]}]
  (swap! !mutex
         (fn [prev-promise]
           (p/then prev-promise
                   (fn []
                     (let [current-list (get-in @re-frame.db/app-db db-path [])]
                       (-> (apply-matrix-diffs current-list updates parse-fn)
                           (p/then (fn [next-list]
                                     (when sync-event
                                       (re-frame/dispatch-sync (conj sync-event next-list)))
                                     (when async-event
                                       (re-frame/dispatch (conj async-event next-list)))))
                           (p/catch (fn [err]
                                      (log/error "Diff Panic at" db-path ":" err))))))))))
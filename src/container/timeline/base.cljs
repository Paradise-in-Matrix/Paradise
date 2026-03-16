(ns container.timeline.base
  (:require [promesa.core :as p]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [re-frame.db :as db]
            ["react-virtuoso" :refer [Virtuoso]]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [utils.helpers :refer [mxc->url sanitize-custom-html format-divider-date]]
            [utils.global-ui :refer [avatar long-press-props swipe-to-action-wrapper]]
            [container.timeline.item :refer [event-tile reify-item]]
            [container.reusable :refer [room-header]]
            [input.base :refer [message-input inline-editor]]
            [navigation.rooms.room-summary :refer [build-room-summary]]
            [client.diff-handler :refer [apply-matrix-diffs]]
            ["generated-compat" :as sdk :refer [RoomMessageEventContentWithoutRelation MessageType EditedContent]]
            ))

(defonce !timeline-queues (atom {}))

(defn apply-timeline-diffs! [room-id updates]
  (swap! !timeline-queues update room-id #(or % (p/resolved [])))
  (swap! !timeline-queues
         (fn [queues]
           (update queues room-id
                   (fn [prev-promise]
                     (-> prev-promise
                         (p/then (fn [current-events]
                                   (apply-matrix-diffs current-events updates reify-item)))
                         (p/then (fn [next-events]
                                   (log/debug "Timeline diffs finished")
                                   (re-frame/dispatch [:sdk/update-timeline room-id next-events])
                                   next-events))
                         (p/catch (fn [err]
                                    (js/console.error "Timeline Diff Panic for room" room-id ":" err)
                                    (get-in @re-frame.db/app-db [:timeline room-id] []))))))))
  nil)

(re-frame/reg-sub
 :timeline/event-by-id
 (fn [[_ room-id _]]
   (re-frame/subscribe [:timeline/current-events room-id]))
 (fn [events [_ _ target-id]]
   (first (filter #(= (:id %) target-id) events))))

(re-frame/reg-event-db
 :sdk/update-timeline
 (fn [db [_ room-id events]]
   (assoc-in db [:timeline room-id] events)))

(re-frame/reg-event-fx
 :sdk/cleanup-timeline
 (fn [{:keys [db]} [_ room-id]]
   (when-let [subs (get-in db [:timeline-subs room-id])]
     (when-let [tl-handle (:tl-handle subs)]
       (.cancel tl-handle))
     (when-let [pag-handle (:pag-handle subs)]
       (.cancel pag-handle)))
   {:db (update db :timeline-subs dissoc room-id)}))

(re-frame/reg-event-db
 :sdk/update-pagination-status
 (fn [db [_ room-id status]]
   (assoc-in db [:timeline-pagination room-id] status)))


(re-frame/reg-event-fx
 :sdk/boot-timeline
 (fn [{:keys [db]} [_ room-id]]
   (if (get-in db [:timeline-subs room-id])
     (do (log/warn "Prevented duplicate timeline boot for:" room-id) {})
     (let [client (:client db)]
       (when-let [room (.getRoom client room-id)]
         (-> (p/let [timeline        (.timeline room)
                     listener        #js {:onUpdate (fn [diffs] (apply-timeline-diffs! room-id diffs))}

                     timeline-handle (.addListener timeline listener)
                     pag-listener    #js {:onUpdate #(re-frame/dispatch [:sdk/update-pagination-status room-id %])}
                     pag-handle      (.subscribeToBackPaginationStatus timeline pag-listener)
                     ]
               (.paginateBackwards timeline 5)
             (re-frame/dispatch [:sdk/save-timeline-sub room-id timeline timeline-handle pag-handle]))
             (.catch #(js/console.error "Boot failed:" %))))
       {}))))

(re-frame/reg-event-fx
 :sdk/preload-timelines
 (fn [{:keys [db]} [_ room-ids]]
   (let [active-subs (get db :timeline-subs {})
         rooms-to-boot (remove #(contains? active-subs %) (take 3 room-ids))]
     (if (seq rooms-to-boot)
       {:dispatch-n (map (fn [rid] [:sdk/boot-timeline rid]) rooms-to-boot)}
       {}))))

(re-frame/reg-event-fx
 :sdk/preload-timeline-safe
 (fn [{:keys [db]} [_ room-id]]
   (let [already-booted? (contains? (:timeline-subs db) room-id)]
     (if already-booted?
       {}
       {:dispatch [:sdk/boot-timeline room-id]}))))

(re-frame/reg-event-db
 :sdk/save-timeline-sub
 (fn [db [_ room-id timeline tl-handle pag-handle]]
   (assoc-in db [:timeline-subs room-id]
             {:timeline timeline
              :tl-handle tl-handle
              :pag-handle pag-handle})))

(re-frame/reg-event-fx
 :sdk/back-paginate
 (fn [{:keys [db]} [_ room-id]]
   (let [loading? (get-in db [:timeline/loading-more? room-id])
         status   (get-in db [:timeline-pagination room-id])]
     (if (and room-id
              (not loading?)
              (not= status "Paginating")
              (not= status "TimelineStartReached"))
       (when-let [timeline (get-in db [:timeline-subs room-id :timeline])]
         (-> (.paginateBackwards timeline 50)
             (.then #(re-frame/dispatch [:sdk/pagination-complete room-id])))
         {:db (assoc-in db [:timeline/loading-more? room-id] true)})
       {}))))

(re-frame/reg-event-db
 :sdk/pagination-complete
 (fn [db [_ room-id]]
   (let [timeline-path [:timeline-pagination room-id]
         ]
     (-> db
         (assoc-in [:timeline/loading-more? room-id] false)
         ))))

(re-frame/reg-sub
 :timeline/current-events
 (fn [db _]
   (let [active-room (:active-room-id db)
         raw-events  (get-in db [:timeline active-room] [])]
     (->> raw-events
          (reduce (fn [acc e]
                    (let [id-key   (or (:internal-id e) (:id e))
                          existing (get acc id-key)]
                      (if (and existing
                               (not (:is-edited? e))
                               (<= (:ts e) (:ts existing)))
                        acc
                        (assoc acc id-key e))))
                  {})
          (vals)
          (sort-by :ts)
          (vec)))))

(re-frame/reg-sub
 :timeline/loading-more?
 (fn [db [_ room-id]]
   (get-in db [:timeline/loading-more? room-id] false)))

(defn virtualized-timeline [events room-id]
  (r/with-let [!start-index    (r/atom 1000000)
               !prev-first-id  (r/atom nil)
               !prev-count     (r/atom 0)
               !initial-idx    (r/atom nil)
               !at-bottom?     (r/atom true)
               !virtuoso-ref   (r/atom nil)]
    (let [event-array (to-array events)
          cnt         (count event-array)
          first-id    (some-> (aget event-array 0) :id)
          loading?    @(re-frame/subscribe [:timeline/loading-more? room-id])]

      (when (and (nil? @!initial-idx) (pos? cnt))
        (reset! !initial-idx (dec (+ @!start-index cnt))))

      (when (and @!prev-first-id (not= first-id @!prev-first-id) (> cnt @!prev-count))
        (swap! !start-index - (- cnt @!prev-count)))

      (reset! !prev-first-id first-id)
      (reset! !prev-count cnt)

      [:div.timeline-messages
       (when loading?
         [:div.timeline-loading-overlay
          [:div.spinner]])

       (when-not @!at-bottom?
         [:button.jump-to-bottom
          {:on-click #(.scrollToIndex @!virtuoso-ref #js {:index (dec (+ @!start-index cnt))
                                                          :behavior "smooth"})}
          "Jump to Present"])

       [:> Virtuoso
        {:key room-id
         :ref #(reset! !virtuoso-ref %)
         :data event-array
         :firstItemIndex @!start-index
         :initialTopMostItemIndex @!initial-idx
         :alignToBottom true
         :increaseViewportBy 400
         :atBottomThreshold 100
         :atBottomStateChange #(reset! !at-bottom? %)
         :followOutput (fn [at-bottom] (if at-bottom "auto" false))
         :isScrolling (fn [scrolling?]
                        (when scrolling?
                          ))
         :startReached (fn []
                         (log/debug "Start reached for" room-id)
                         (re-frame/dispatch [:sdk/back-paginate room-id]))
         :computeItemKey (fn [_ item] (:id item))
         :itemContent (fn [_ item]
                        (r/as-element
                         [:li.timeline-item {:key (:id item)}
                          [event-tile item]]))}] ])))

(defn timeline [& {:keys [compact? hide-header?]}]
  (let [active-id    @(re-frame/subscribe [:rooms/active-id])
        room-meta    @(re-frame/subscribe [:rooms/active-metadata])
        events       @(re-frame/subscribe [:timeline/current-events])
        display-name (when active-id (or (when room-meta (.-name room-meta)) active-id))]
    [:div.timeline-container
     {:style {:display "flex"
              :flex-direction "column"
              :height "100%"
              :overflow "hidden"}}

     (when-not hide-header?
       [room-header {:display-name display-name
                     :compact?     compact?
                     :active-id    active-id}])

     (if-not active-id
       [:div.timeline-empty "Select a room to start chatting."]
       [:<>
        [virtualized-timeline events active-id]
        [message-input]])]))
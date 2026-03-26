(ns container.timeline.base
  (:require [promesa.core :as p]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [re-frame.db :as db]
            ["react-virtuoso" :refer [Virtuoso]]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [utils.helpers :refer [mxc->url sanitize-custom-html format-divider-date format-readers join-names get-status-string]]
            [utils.global-ui :refer [avatar long-press-props swipe-to-action-wrapper]]
            [utils.svg :as icons]
            [container.timeline.item :refer [event-tile wrap-item]]
            [container.reusable :refer [room-header]]
            [input.base :refer [message-input inline-editor]]
            [navigation.rooms.room-summary :refer [build-room-summary]]
            [client.diff-handler :refer [apply-matrix-diffs]]
            ["ffi-bindings" :as sdk :refer [RoomMessageEventContentWithoutRelation MessageType EditedContent TimelineConfiguration]]
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
                                   (apply-matrix-diffs current-events updates wrap-item)))
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
     (doseq [handle-key [:tl-handle :pag-handle :typing-handle]]
       (when-let [handle (get subs handle-key)]
         (try
           (.cancel handle)
           (catch :default e
             (log/warn "Failed to cancel handle:" handle-key e))))))
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
         (-> (p/let [config (.create TimelineConfiguration
                                     #js {:focus             (new (.. sdk -TimelineFocus -Live) #js {:hideThreadedEvents false} )
                                          :filter            (new (.. sdk -TimelineFilter -All))
                                          :internalIdPrefix  js/undefined
                                          :dateDividerMode   (.-Daily sdk/DateDividerMode)
                                          :trackReadReceipts true
                                          :reportUtds        false})

                     ;;timeline (.timeline room) ;; if no configs are wanted
                     timeline        (.timelineWithConfiguration room config)
                     listener        #js {:onUpdate (fn [diffs] (apply-timeline-diffs! room-id diffs))}

                     typing-listener #js {:call (fn [user-ids]
                                                  (re-frame/dispatch [:sdk/update-typing-users room-id user-ids]))}
                     typing-handle (.subscribeToTypingNotifications room typing-listener)
                     timeline-handle (.addListener timeline listener)
                     pag-listener    #js {:onUpdate #(re-frame/dispatch [:sdk/update-pagination-status room-id %])}
                                 pag-handle      (.subscribeToBackPaginationStatus timeline pag-listener)
                     ]
               (.paginateBackwards timeline 50 js/undefined)
               (re-frame/dispatch [:sdk/save-timeline-sub room-id timeline timeline-handle nil pag-handle typing-handle]))
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
 (fn [db [_ room-id timeline tl-handle pag-handle typing-handle]]
   (assoc-in db [:timeline-subs room-id]
             {:timeline      timeline
              :tl-handle     tl-handle
              :pag-handle    pag-handle
              :typing-handle typing-handle})))

(re-frame/reg-event-fx
 :sdk/back-paginate
 (fn [{:keys [db]} [_ room-id]]
   (let [loading? (get-in db [:timeline/loading-more? room-id])
         status   (get-in db [:timeline-pagination room-id])
         status-tag (.-tag status)
         start-reached? (some-> status (.-inner) (.-hitTimeLineStart))
         ]
     (if (and room-id
              (not loading?)
              (not= status "Paginating")
              status-tag)
       (if-let [timeline (get-in db [:timeline-subs room-id :timeline])]
         (do
           (log/debug "Paginating backwards")
           (-> (.paginateBackwards timeline 100 js/undefined)
               (p/then #(re-frame/dispatch [:sdk/pagination-complete room-id]))
               (p/catch #(log/error "Back pagination failed:" %)))
           {:db (assoc-in db [:timeline/loading-more? room-id] true)})
         {})
       {}))))


(re-frame/reg-event-db
 :sdk/pagination-complete
 (fn [db [_ room-id]]
   (let [timeline-path [:timeline-pagination room-id]
         ]
     (-> db
         (assoc-in [:timeline/loading-more? room-id] false)
         ))))

(defn enrich-timeline-items [items]
  (let [clean-items (remove #(and (= (:type %) :virtual)
                                  (= (:tag %) "ReadMarker"))
                            items)]
    (loop [remaining clean-items
           processed []
           last-msg  nil]
      (if (empty? remaining)
        processed
        (let [curr         (first remaining)
              curr-is-msg? (= (:content-tag curr) "MsgLike")
              is-virtual?  (= (:type curr) :virtual)
              stable-id    (or (when is-virtual?
                                 (str "virtual-" (:tag curr) "-" (:ts curr)))
                               (:id curr)
                               (:internal-id curr))
              can-merge?   (and curr-is-msg?
                                last-msg
                                (= (:sender-id curr) (:sender-id last-msg))
                                (< (- (:ts curr) (:ts last-msg)) 300000))

              new-item     (assoc curr
                                  :id stable-id
                                  :merge-with-prev? can-merge?)]
          (recur (rest remaining)
                 (conj processed new-item)
                 (if curr-is-msg? new-item last-msg)))))))

(re-frame/reg-sub
 :timeline/sorted-events
 (fn [db [_ active-room]]
   (let [raw-events (get-in db [:timeline active-room] [])]
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
          (sort-by (fn [e]
                     [(:ts e)
                      (if (= (:type e) :virtual) 0 1)]))))))


(re-frame/reg-sub
 :timeline/current-events
 (fn [[_ active-room]]
   (re-frame/subscribe [:timeline/sorted-events active-room]))
 (fn [sorted-events]
   (enrich-timeline-items sorted-events)))

(re-frame/reg-sub
 :timeline/latest-readers
 (fn [_ _]
   [(re-frame/subscribe [:timeline/current-events])
    (re-frame/subscribe [:sdk/profile])])
 (fn [[events profile] _]
   (let [my-id        (:user-id profile)
         actual-events (filter #(= (:type %) :event) events)
         latest-event  (last actual-events)
         read-by       (or (:read-by latest-event) [])
         others        (remove #(= % my-id) read-by)]
     others)))

(re-frame/reg-sub
 :timeline/loading-more?
 (fn [db [_ room-id]]
   (get-in db [:timeline/loading-more? room-id] false)))

(defn followers-indicator [active-room]
  (let [reader-ids  @(re-frame/subscribe [:timeline/latest-readers])
        members-map @(re-frame/subscribe [:room/members-map active-room])
        has-readers? (boolean (seq reader-ids))
        names (when has-readers?
                (map (fn [id] (or (:display-name (get members-map id)) id))
                     reader-ids))]
    [:div.followers-indicator
     {:class (when has-readers? "is-visible")}
     (when has-readers?
       [icons/double-check {:width "14px" :height "14px"}])
     [:span.text
      (if has-readers?
        (format-readers names)
        "\u00A0")]
     ]))


(re-frame/reg-event-db
 :sdk/clear-stale-typing
 (fn [db [_ room-id]]
   (let [data (get-in db [:typing-users room-id])]
     (if (and data (> (- (js/Date.now) (:last-update data)) 6000))
       (update db :typing-users dissoc room-id)
       db))))

(re-frame/reg-event-fx
 :sdk/update-typing-users
 (fn [{:keys [db]} [_ room-id user-ids]]
   (let [users (js->clj user-ids)]
     (if (empty? users)
       {:db (update db :typing-users dissoc room-id)}
       {:db (assoc-in db [:typing-users room-id]
                      {:users users
                       :last-update (js/Date.now)})
        :dispatch-later [{:ms 7000
                          :dispatch [:sdk/clear-stale-typing room-id]}]}))))

(re-frame/reg-sub
 :room/typing-users
 (fn [db [_ room-id]]
   (let [data (get-in db [:typing-users room-id])]
     (if (and data
              (< (- (js/Date.now) (:last-update data)) 6000))
       (:users data)
       []))))


(defn status-indicator [active-room]
  (let [tr            @(re-frame/subscribe [:i18n/tr])
        typing-info   @(re-frame/subscribe [:room/typing-users active-room])
        reader-ids    @(re-frame/subscribe [:timeline/latest-readers])
        members-map   @(re-frame/subscribe [:room/members-map active-room])
        profile       @(re-frame/subscribe [:sdk/profile])
        my-id         (:user-id profile)
        typing-ids    (if (map? typing-info) (:users typing-info) typing-info)
        others-typing (filterv #(not= % my-id) (or typing-ids []))
        get-name      #(or (:display-name (get members-map %)) %)
        typing-names  (map get-name others-typing)
        reader-names  (map get-name reader-ids)

        has-typists?  (not-empty typing-names)
        has-readers?  (not-empty reader-names)
        is-visible?   (or has-typists? has-readers?)]
    [:div.followers-indicator
     {:class (when is-visible? "is-visible")
      :key (str active-room "-status-bar")}
     (cond
       has-typists?
       [:div.status-content {:key "typing"}
        [icons/typing-dots {:style {:color "var(--cp-text-muted)"}}]
        [:span.text (get-status-string tr :typing typing-names)]]
       has-readers?
       [:div.status-content {:key "readers"}
        [icons/double-check {:width "14px" :height "14px"}]
        [:span.text (get-status-string tr :reading reader-names)]]
       :else
       [:span.text {:key "empty"} "\u00A0"])]))



(re-frame/reg-event-fx
 :room/pretty-jump
 (fn [{:keys [db]} [_ room-id event-id]]
   {:db (assoc-in db [:timeline/ui-state room-id :animating-jump?] true)
    :dispatch-later [{:ms 300
                      :dispatch [:room/execute-deep-jump room-id event-id]}]}))

(re-frame/reg-event-fx
 :room/execute-deep-jump
 (fn [{:keys [db]} [_ room-id event-id]]
   {:db (assoc-in db [:timeline/jump-target-id room-id] event-id)
    :dispatch-n [[:sdk/cleanup-timeline room-id]
                 [:room/boot-focused-timeline room-id event-id]]}))





(re-frame/reg-sub
 :timeline/ui-state
 (fn [db [_ room-id]]
   (get-in db [:timeline/ui-state room-id] {})))

(re-frame/reg-sub
 :timeline/jump-target-id
 (fn [db [_ room-id]]
   (get-in db [:timeline/jump-target-id room-id])))

(re-frame/reg-sub
 :room/is-focused?
 (fn [db [_ room-id]]
   (some? (get-in db [:timeline/jump-target-id room-id]))))

(re-frame/reg-event-db
 :timeline/clear-jump-ui
 (fn [db [_ room-id]]
   (assoc-in db [:timeline/ui-state room-id :animating-jump?] false)))


(re-frame/reg-sub
 :timeline/loading-forward?
 (fn [db [_ room-id]]
   (get-in db [:timeline/loading-forward? room-id] false)))

(re-frame/reg-event-fx
 :sdk/forward-paginate
 (fn [{:keys [db]} [_ room-id]]
   (let [loading? (get-in db [:timeline/loading-forward? room-id])
         timeline (get-in db [:timeline-subs room-id :timeline])]
     (if (and timeline (not loading?))
       (do
         (-> (.paginateForwards timeline 50 js/undefined)
             (p/then #(re-frame/dispatch [:sdk/forward-pagination-complete room-id]))
             (p/catch #(log/error "Forward pagination failed:" %)))
         {:db (assoc-in db [:timeline/loading-forward? room-id] true)})
       {}))))

(re-frame/reg-event-db
 :sdk/forward-pagination-complete
 (fn [db [_ room-id]]
   (assoc-in db [:timeline/loading-forward? room-id] false)))


(re-frame/reg-event-fx
 :room/boot-focused-timeline
 (fn [{:keys [db]} [_ room-id event-id]]
   (let [client (:client db)
         room   (.getRoom client room-id)]
     (when room
       (-> (p/let [focus  (.new (.. sdk -TimelineFocus -Event)
                                #js {:eventId event-id
                                     :numEventsToLoad 40})
                   config (.create sdk/TimelineConfiguration
                                   #js {:focus             focus
                                        :filter            (new (.. sdk -TimelineFilter -All))
                                        :dateDividerMode   (.-Daily sdk/DateDividerMode)
                                        :trackReadReceipts false})
                   timeline (.timelineWithConfiguration room config)
                   listener #js {:onUpdate (fn [diffs]
                                             (apply-timeline-diffs! room-id diffs)
                                             )}
                   tl-handle (.addListener timeline listener)
                   ]
             (re-frame/dispatch [:sdk/save-timeline-sub room-id timeline tl-handle nil nil]))
           (p/catch #(log/error "Focused timeline boot failed:" %))))
     {})))



(re-frame/reg-event-fx
 :room/jump-to-live-bottom
 (fn [{:keys [db]} [_ room-id]]
   {:db (-> db
            (assoc-in [:timeline/ui-state room-id :animating-jump?] true)
            (update :timeline/jump-target-id dissoc room-id))
    :dispatch-later [{:ms 300
                      :dispatch [:room/execute-return-to-live room-id]}]}))

(re-frame/reg-event-fx
 :room/execute-return-to-live
 (fn [{:keys [db]} [_ room-id]]
   {:dispatch-n [[:sdk/cleanup-timeline room-id]
                 [:sdk/boot-timeline room-id]]}))


(defn virtualized-timeline [tr initial-events initial-room-id]
  (let [math-state    (volatile! {:start-index 1000000
                                  :prev-first-id nil
                                  :prev-count 0})
        !at-bottom?   (r/atom true)
        !virtuoso-ref (r/atom nil)]
    (fn [tr events room-id]
      (let [event-array (to-array events)
            cnt         (count event-array)
            first-id    (some-> (aget event-array 0) :id)
            loading?    @(re-frame/subscribe [:timeline/loading-more? room-id])
            jump-target @(re-frame/subscribe [:timeline/jump-target-id room-id])
            focus-mode? @(re-frame/subscribe [:room/is-focused? room-id])

            {:keys [start-index prev-first-id prev-count]} @math-state

            prepended?    (and prev-first-id
                               (not= first-id prev-first-id)
                               (> cnt prev-count))
            diff          (if prepended? (- cnt prev-count) 0)
            current-start (if prepended? (- start-index diff) start-index)

            target-idx    (if jump-target
                            (let [idx (.findIndex event-array #(= (:id %) jump-target))]
                              (if (not= idx -1)
                                (+ current-start idx)
                                (dec (+ current-start cnt))))
                            (dec (+ current-start cnt)))]

        (vreset! math-state {:start-index current-start
                             :prev-first-id first-id
                             :prev-count cnt})

        [:div.timeline-messages
         {:class (when jump-target "jumping-animation")
          :style {:display "flex" :flex-direction "column" :flex 1 :height "100%"}}

         (when loading?
           [:div.timeline-loading-overlay [:div.spinner]])

         (when-not @!at-bottom?
           [:button.jump-to-bottom
            {:on-click (fn []
                         (if focus-mode?
                           (re-frame/dispatch [:room/jump-to-live-bottom room-id])
                           (.scrollToIndex @!virtuoso-ref
                                           #js {:index (dec (+ current-start cnt))
                                                :behavior "auto"})))}
            [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
             (tr [:container.timeline/jump-to-bottom])]])

         (if (zero? cnt)
           [:div.timeline-empty {:style {:padding "20px" :text-align "center"}} "Loading..."]

           [:> Virtuoso
            {:key (str room-id "-" jump-target)
             :ref #(reset! !virtuoso-ref %)
             :style {:height "100%" :flex 1}
             :data event-array
             :firstItemIndex current-start
             :initialTopMostItemIndex target-idx
             :alignToBottom (not focus-mode?)
             :increaseViewportBy #js {:top 400 :bottom 400}
             :atBottomThreshold 100
             :atBottomStateChange #(reset! !at-bottom? %)
             :followOutput (fn [is-at-bottom]
                             (if (and is-at-bottom (not focus-mode?)) "auto" false))

             :startReached (fn []
                             (when-not loading?
                               (re-frame/dispatch [:sdk/back-paginate room-id])))
             :totalListHeightChanged (fn [_]
                                       (when (and @!at-bottom? (not focus-mode?) @!virtuoso-ref)
                                         (.scrollToIndex @!virtuoso-ref
                                                         #js {:index (dec (+ current-start cnt))
                                                              :align "end"
                                                              :behavior "auto"})))

             ;; was brute forcing scroll for testing
            #_ :totalListHeightChanged
             #_(fn [_]
               (when (and @!at-bottom? (not focus-mode?) @!virtuoso-ref)
                 (js/requestAnimationFrame
                  (fn []
                    (.scrollTo @!virtuoso-ref
                               #js {:top 999999999
                                    :behavior "auto"})))))

             :endReached (fn []
                           (when focus-mode?
                             (re-frame/dispatch [:sdk/forward-paginate room-id])))
             :computeItemKey (fn [_ item] (:id item))
             :itemContent (fn [_ item]
                            (r/as-element
                             ^{:key (:id item)}
                             [:li.timeline-item
                              {:class (when (= (:id item) jump-target) "is-jump-target")}
                              [event-tile item]]))}] )]))))


(defn timeline [& {:keys [compact? hide-header?]}]
  (let [active-id    @(re-frame/subscribe [:rooms/active-id])
        room-meta    @(re-frame/subscribe [:rooms/active-metadata])
        events       @(re-frame/subscribe [:timeline/current-events active-id])
        tr           @(re-frame/subscribe [:i18n/tr])
        display-name (when active-id (or (when room-meta (.-name room-meta)) active-id))
       ]
    [:div.timeline-container
       (when-not hide-header?
       [room-header {:display-name display-name
                     :compact?     compact?
                     :active-id    active-id}])

     (if-not active-id
       [:div.timeline-empty "Select a room to start chatting."]
       [:<>
        [virtualized-timeline tr events active-id]
        [message-input]
        [status-indicator active-id]
        ])]))


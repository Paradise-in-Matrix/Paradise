(ns container.timeline.base
  (:require [promesa.core :as p]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [re-frame.db :as db]
            ["react-virtuoso" :refer [Virtuoso]]
            ["virtua" :refer [VList Virtualizer]]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [utils.helpers :refer [mxc->url sanitize-custom-html format-divider-date format-readers join-names get-status-string]]
            [utils.global-ui :refer [avatar long-press-props swipe-to-action-wrapper]]
            [utils.svg :as icons]
            [container.timeline.item :refer [event-tile wrap-item connected-event-tile]]
            [container.reusable :refer [room-header]]
            [input.base :refer [message-input inline-editor]]
            [navigation.rooms.room-summary :refer [build-room-summary]]
            [client.diff-handler :refer [apply-matrix-diffs]]
            ["ffi-bindings" :as sdk :refer [RoomMessageEventContentWithoutRelation MessageType EditedContent TimelineConfiguration]]))

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
 :timeline/raw-events
 (fn [db [_ active-room]]
   (get-in db [:timeline active-room] [])))

(re-frame/reg-sub
 :timeline/sorted-events
 (fn [[_ active-room]]
   (re-frame/subscribe [:timeline/raw-events active-room]))
 (fn [raw-events _]
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
                    (if (= (:type e) :virtual) 0 1)])))))


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
              stable-id    (cond
                             is-virtual? (str "virtual-" (:tag curr) "-" (:ts curr))
                             (:id curr)  (:id curr)
                             :else       (:internal-id curr))
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
 :timeline/current-events
 (fn [[_ active-room]]
   (re-frame/subscribe [:timeline/sorted-events active-room]))
 (fn [sorted-events]
   (enrich-timeline-items sorted-events)))

(re-frame/reg-sub
 :timeline/events-map
 (fn [[_ room-id]]
   (re-frame/subscribe [:timeline/current-events room-id]))
 (fn [events _]
   (into {} (map (juxt :id identity) events))))



(re-frame/reg-event-fx
 :sdk/boot-timeline
 (fn [{:keys [db]} [_ room-id]]
   (if (get-in db [:timeline-subs room-id])
     (do (log/warn "Prevented duplicate timeline boot for:" room-id) {})
     (let [client (:client db)]
       (when-let [room (.getRoom client room-id)]
         (-> (p/let [config (.create TimelineConfiguration
                                     #js {:focus             (new (.. sdk -TimelineFocus -Live) #js {:hideThreadedEvents false})
                                          :filter            (new (.. sdk -TimelineFilter -All))
                                          :internalIdPrefix  js/undefined
                                          :dateDividerMode   (.-Daily sdk/DateDividerMode)
                                          :trackReadReceipts true
                                          :reportUtds        false})
                     timeline        (.timelineWithConfiguration room config)
                     listener        #js {:onUpdate (fn [diffs] (apply-timeline-diffs! room-id diffs))}
                     typing-listener #js {:call (fn [user-ids]
                                                  (re-frame/dispatch [:sdk/update-typing-users room-id user-ids]))}
                     typing-handle   (.subscribeToTypingNotifications room typing-listener)
                     timeline-handle (.addListener timeline listener)
                     pag-listener    #js {:onUpdate #(re-frame/dispatch [:sdk/update-pagination-status room-id %])}
                     pag-handle      (.subscribeToBackPaginationStatus timeline pag-listener)
                     _               (-> (.paginateBackwards timeline 50 js/undefined)
                                         (p/then #(re-frame/dispatch [:sdk/set-timeline-initialized room-id]))
                                         (p/catch #(log/error "Initial paginate failed" %)))]
               (re-frame/dispatch [:sdk/save-timeline-sub room-id timeline timeline-handle nil pag-handle typing-handle]))
             (.catch #(log/error "Boot failed:" %))))
       {}))))



(re-frame/reg-event-db
 :sdk/set-timeline-initialized
 (fn [db [_ room-id]]
   (assoc-in db [:timeline/ui-metadata room-id :initialized?] true)))

(re-frame/reg-event-fx
 :sdk/preload-timelines
 (fn [{:keys [db]} [_ room-ids]]
   (let [active-subs   (get db :timeline-subs {})
         rooms-to-boot (remove #(contains? active-subs %) (take 3 room-ids))]
     (if (seq rooms-to-boot)
       {:dispatch-n (map (fn [rid] [:sdk/boot-timeline rid]) rooms-to-boot)}
       {}))))

(re-frame/reg-event-db
 :sdk/save-timeline-sub
 (fn [db [_ room-id timeline tl-handle pag-handle typing-handle]]
   (assoc-in db [:timeline-subs room-id]
             {:timeline      timeline
              :tl-handle     tl-handle
              :pag-handle    pag-handle
              :typing-handle typing-handle})))

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


(re-frame/reg-sub
 :timeline/latest-readers
 (fn [[_ room-id _]]
   [(re-frame/subscribe [:timeline/current-events room-id])
    (re-frame/subscribe [:sdk/profile])])
 (fn [[events profile] _]
   (log/error events)
   (let [my-id        (:user-id profile)
         actual-events (filter #(= (:type %) :event) events)
         latest-event  (last actual-events)
         read-by       (or (:read-by latest-event) [])
         others        (remove #(= % my-id) read-by)]
     others)))

(defn status-indicator [active-room]
  (let [tr            @(re-frame/subscribe [:i18n/tr])
        typing-info   @(re-frame/subscribe [:room/typing-users active-room])
        reader-ids    @(re-frame/subscribe [:timeline/latest-readers active-room])
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




(re-frame/reg-sub
 :timeline/ui-state
 (fn [db [_ room-id]]
   (get-in db [:timeline/ui-state room-id] {})))

(re-frame/reg-sub
 :timeline/jump-target-id
 (fn [db [_ room-id]]
   (get-in db [:timeline/jump-target-id room-id])))

(re-frame/reg-sub
 :timeline/loading-forward?
 (fn [db [_ room-id]]
   (get-in db [:timeline/loading-forward? room-id] false)))

(re-frame/reg-sub
 :timeline/ui-metadata
 (fn [db [_ room-id]]
   (get-in db [:timeline/ui-metadata room-id] {})))

(re-frame/reg-sub
 :timeline/loading-more?
 (fn [db [_ room-id]]
   (get-in db [:timeline/loading-more? room-id] false)))

(re-frame/reg-sub
 :room/is-focused?
 (fn [db [_ room-id]]
   (some? (get-in db [:timeline/jump-target-id room-id]))))

(defn process-timeline [raw-events]
  (if (empty? raw-events)
    []
    (->> raw-events
         (reduce (fn [acc e]
                   (let [id-key (or (:internal-id e) (:id e))
                         existing (get acc id-key)]
                     (if (and existing (not (:is-edited? e)) (<= (:ts e) (:ts existing)))
                       acc
                       (assoc acc id-key e))))
                 {})
         vals
         (sort-by (fn [e] [(:ts e) (if (= (:type e) :virtual) 0 1)]))
         enrich-timeline-items)))

(re-frame/reg-event-db
 :sdk/pagination-complete
 (fn [db [_ room-id]]
   (-> db
       (assoc-in [:timeline/loading-more? room-id] false)
       (assoc-in [:timeline/ui-metadata room-id :pending-anchor] nil))))

(re-frame/reg-event-fx
 :sdk/back-paginate
 (fn [{:keys [db]} [_ room-id]]
   (let [loading-back?    (get-in db [:timeline/loading-more? room-id])
         loading-forward? (get-in db [:timeline/loading-forward? room-id])
         timeline         (get-in db [:timeline-subs room-id :timeline])
         raw-events       (get-in db [:timeline room-id] [])
         processed        (process-timeline raw-events)
         anchor-id        (:id (first (filter #(= (:type %) :event) processed)))]
     (if (and room-id (not loading-back?) (not loading-forward?) timeline)
       (do
         (log/info "Triggering SDK Back Paginate for" room-id)
         (-> (.paginateBackwards timeline 50 js/undefined)
             (p/then #(re-frame/dispatch [:sdk/pagination-complete room-id]))
             (p/catch (fn [err]
                        (log/error "Back Pagination failed:" err)
                        (re-frame/dispatch [:sdk/pagination-complete room-id]))))
         {:db (-> db
                  (assoc-in [:timeline/loading-more? room-id] true)
                  (assoc-in [:timeline/ui-metadata room-id :pending-anchor] anchor-id))})
       {}))))

(re-frame/reg-event-fx
 :sdk/forward-paginate
 (fn [{:keys [db]} [_ room-id]]
   (let [loading-back?    (get-in db [:timeline/loading-more? room-id])
         loading-forward? (get-in db [:timeline/loading-forward? room-id])
         dead?            (get-in db [:timeline/forward-dead? room-id])
         timeline         (get-in db [:timeline-subs room-id :timeline])]
     (if (and timeline (not loading-back?) (not loading-forward?) (not dead?))
       (do
         (log/info "Triggering SDK Forward Paginate for" room-id)
         (-> (.paginateForwards timeline 50 js/undefined)
             (p/then #(re-frame/dispatch [:sdk/forward-pagination-complete room-id]))
             (p/catch (fn [err]
                        (log/error "Forward Pagination panicked/dead:" err)
                        (re-frame/dispatch [:sdk/forward-pagination-dead room-id]))))
         {:db (assoc-in db [:timeline/loading-forward? room-id] true)})
       {}))))

(re-frame/reg-event-db
 :sdk/forward-pagination-complete
 (fn [db [_ room-id]]
   (assoc-in db [:timeline/loading-forward? room-id] false)))

(re-frame/reg-event-db
 :sdk/forward-pagination-dead
 (fn [db [_ room-id]]
   (-> db
       (assoc-in [:timeline/loading-forward? room-id] false)
       (assoc-in [:timeline/forward-dead? room-id] true))))

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
                                             (apply-timeline-diffs! room-id diffs))}
                   tl-handle (.addListener timeline listener)
                   _ (.paginateForwards timeline 10)
                   _ (.paginateBackwards timeline 5)
                   ]
             (re-frame/dispatch [:sdk/save-timeline-sub room-id timeline tl-handle nil nil]))
           (p/catch #(log/error "Focused timeline boot failed:" %))))
     {})))


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


(re-frame/reg-event-db
 :sdk/update-timeline
 (fn [db [_ room-id new-raw-events]]
   (let [processed (process-timeline new-raw-events)]
     (assoc-in db [:timeline room-id] processed))))

(re-frame/reg-event-db
 :sdk/pagination-complete
 (fn [db [_ room-id]]
   (assoc-in db [:timeline/loading-more? room-id] false)))

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



(defn timeline-loading-overlay []
  [:div {:style {:position "absolute"
                 :top "20px"
                 :width "100%"
                 :display "flex"
                 :justify-content "center"
                 :z-index 100
                 :pointer-events "none"}}
   [:div {:style {:background "var(--bg-color, white)"
                  :padding "8px"
                  :border-radius "50%"
                  :box-shadow "0 2px 10px rgba(0,0,0,0.1)"}}
    [:div.spinner]]])

(defn timeline-empty-state []
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.timeline-empty {:style {:padding "40px" :text-align "center"}}
     (tr [:container.timeline/loading])]))

(defn timeline-jump-button [do-jump! focus-mode?]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:button.jump-to-bottom
     {:style {:position "absolute"
              :bottom "20px"
              :right "24px"
              :z-index 10
              :display "flex"
              :align-items "center"
              :gap "8px"}
      :on-click do-jump!}
     [:span (if focus-mode?
              (tr [:container.timeline/return-to-live])
              (tr [:container.timeline/jump-to-bottom]))]]))



(defn virtualized-timeline [initial-room-id]
  (r/with-let [!virtua-ref    (r/atom nil)
               !at-bottom?    (r/atom true)
               !show-jump?    (r/atom false)
               !prev-first-id (r/atom nil)
               !prev-last-id  (r/atom nil)
               !initialized?  (r/atom false)
               !scroll-timer  (atom nil)]

    (fn [room-id]
      (let [events           @(re-frame/subscribe [:timeline/current-events room-id])
            event-array      (to-array events)
            cnt              (count event-array)
            loading?         @(re-frame/subscribe [:timeline/loading-more? room-id])
            loading-forward? @(re-frame/subscribe [:timeline/loading-forward? room-id])
            jump-target      @(re-frame/subscribe [:timeline/jump-target-id room-id])
            focus-mode?      @(re-frame/subscribe [:room/is-focused? room-id])

            current-first-id (when (pos? cnt) (:id (aget event-array 0)))
            current-last-id  (when (pos? cnt) (:id (aget event-array (dec cnt))))

            did-prepend?     (boolean (and @!prev-first-id
                                           (not= current-first-id @!prev-first-id)
                                           (= current-last-id @!prev-last-id)))

            do-jump! (fn []
                       (if focus-mode?
                         (re-frame/dispatch [:room/jump-to-live-bottom room-id])
                         (some-> @!virtua-ref (.scrollToIndex (dec cnt)))))]

        (when (and (not @!initialized?) (pos? cnt) @!virtua-ref)
          (js/setTimeout #(reset! !initialized? true) 0)
          (let [target-idx (if jump-target
                             (let [idx (.findIndex event-array #(= (:id %) jump-target))]
                               (if (not= idx -1) idx (dec cnt)))
                             (dec cnt))]
            (js/setTimeout #(some-> @!virtua-ref (.scrollToIndex target-idx)) 50)))

        (when (and @!initialized?
                   @!at-bottom?
                   (not= current-last-id @!prev-last-id)
                   (not focus-mode?)
                   (not did-prepend?))
          (js/requestAnimationFrame
           (fn [] (some-> @!virtua-ref (.scrollToIndex (dec cnt))))))

        (js/setTimeout #(do
                          (reset! !prev-first-id current-first-id)
                          (reset! !prev-last-id current-last-id)) 0)

        [:div.virtua-timeline-wrapper
         {:style {:flex 1 :position "relative" :display "flex" :flex-direction "column" :min-height 0}}

         [:div.timeline-messages
          {:class (when jump-target "jumping-animation")
           :style {:display "flex"
                   :flex-direction "column"
                   :flex 1
                   :height "100%"
                   :overflow-y "auto"
                   :overflow-anchor "none"}
           :on-scroll (fn [e]
                        (let [target           (.-currentTarget e)
                              scroll-top       (.-scrollTop target)
                              max-scroll       (- (.-scrollHeight target) (.-clientHeight target))
                              dist-from-bottom (- max-scroll scroll-top)
                              v-ref            @!virtua-ref]
                          (when-not @!scroll-timer
                            (reset! !scroll-timer
                                    (js/setTimeout
                                     (fn []
                                       (reset! !scroll-timer nil)
                                       (reset! !show-jump? (> dist-from-bottom 600))
                                       (reset! !at-bottom? (<= dist-from-bottom 10))

                                       (when (and (<= scroll-top 500) (not loading?))
                                         (re-frame/dispatch [:sdk/back-paginate room-id]))

                                       (when (and focus-mode?
                                                  (<= dist-from-bottom 500)
                                                  (not loading-forward?))
                                         (re-frame/dispatch [:sdk/forward-paginate room-id])))
                                     100)))
                          ))}

          (when loading?
            [timeline-loading-overlay])

          (if (zero? cnt)
            [timeline-empty-state]

            (into [:> Virtualizer
                   {:ref #(reset! !virtua-ref %)
                    :shift did-prepend?}]
                  (for [item event-array]
                    ^{:key (:id item)}
                    [:div.virtua-item-wrapper
                     {:style {:margin "0"
                              :min-height "40px"
                              :width "100%"}}
                     [:div.timeline-item
                      {:class (when (= (:id item) jump-target) "is-jump-target")
                       :style {:margin "0"}}
                      [connected-event-tile room-id item]]])))
          (when (and focus-mode? loading-forward?)
            [:div {:style {:min-height "60px"
                           :display "flex"
                           :align-items "center"
                           :justify-content "center"
                           :padding-bottom "20px"}}
             [:div.spinner]])]
         (when (and @!show-jump? (not @!at-bottom?))
           [timeline-jump-button do-jump! focus-mode?])]))))

(defn timeline [& {:keys [compact? hide-header?]}]
  (let [active-id    @(re-frame/subscribe [:rooms/active-id])
        room-meta    @(re-frame/subscribe [:rooms/active-metadata])
        display-name (when active-id (or (when room-meta (.-name room-meta)) active-id))]
    [:div.timeline-container
     (when-not hide-header?
       [room-header {:display-name display-name
                     :compact?     compact?
                     :active-id    active-id}])

     (if-not active-id
       [:div.timeline-empty "Select a room to start chatting."]
       [:<>
        [virtualized-timeline active-id]
        [message-input]
        [status-indicator active-id]])]))
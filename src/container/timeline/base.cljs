(ns container.timeline.base
  (:require [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [re-frame.db :as db]
            [cljs-workers.core :as main]
            [cljs.core.async :refer [go <!]]
            ["virtua" :refer [Virtualizer]]
            [reagent.core :as r]
            [client.state :as state]
            [reagent.dom.client :as rdom]
            [utils.helpers :refer [mxc->url sanitize-custom-html format-divider-date format-readers join-names get-status-string]]
            [utils.global-ui :refer [avatar long-press-props swipe-to-action-wrapper]]
            [utils.svg :as icons]
            [container.timeline.item :refer [event-tile connected-event-tile]]
            [container.reusable :refer [room-header]]
            [input.base :refer [message-input]]))

(defn get-event-id [e]
  (cond
    (and (= (:type e) :virtual) (str/includes? (str (:tag e)) "Div"))
    (str "virtual-divider-" (format-divider-date (:ts e)))

    (= (:type e) :virtual)
    (str "virtual-" (:tag e) "-" (:ts e))

    (not-empty (:id e))
    (:id e)

    :else
    (:internal-id e)))

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
              stable-id    (get-event-id curr)
              can-merge?   (and curr-is-msg?
                                last-msg
                                (= (:sender-id curr) (:sender-id last-msg))
                                (< (- (:ts curr) (:ts last-msg)) 300000))
              new-item     (assoc curr
                                  :id stable-id
                                  :merge-with-prev? can-merge?)]
          (recur (rest remaining)
                 (conj processed new-item)
                 (if curr-is-msg? new-item nil)))))))


(re-frame/reg-sub
 :timeline/raw-events
 (fn [db [_ room-id]]
   (let [focused-data (get-in db [:timeline-data room-id :focused] [])
         live-data    (get-in db [:timeline-data room-id :live] [])
         has-gap?     live-data]
     live-data
     #_(if has-gap?
       focused-data
       (concat focused-data live-data)))))

(re-frame/reg-event-db
 :sdk/update-timeline
 (fn [db [_ room-id source new-raw-events]]
   (assoc-in db [:timeline-data room-id source] new-raw-events)))



(re-frame/reg-sub
 :timeline/sorted-events
 (fn [[_ active-room]]
   (re-frame/subscribe [:timeline/raw-events active-room]))
 (fn [raw-events _]
   raw-events
   ))

(re-frame/reg-sub
 :timeline/current-events
 (fn [[_ active-room]]
   (re-frame/subscribe [:timeline/sorted-events active-room]))
 (fn [sorted-events]
   (enrich-timeline-items sorted-events)
   ))

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
     (do
       (main/do-with-pool! @state/!engine-pool {:handler :boot-timeline
                                                :arguments {:room-id room-id}})
       {:db (assoc-in db [:timeline-subs room-id] true)}))))

(re-frame/reg-event-fx
 :sdk/cleanup-timeline
 (fn [{:keys [db]} [_ room-id]]
   (main/do-with-pool! @state/!engine-pool {:handler :cleanup-timeline
                                            :arguments {:room-id room-id}})
   {:db (-> db
            (update :timeline-subs dissoc room-id)
            (update :timeline-data dissoc room-id))}))

(re-frame/reg-event-fx
 :sdk/back-paginate
 (fn [{:keys [db]} [_ room-id]]
   (let [loading-back?    (get-in db [:timeline/loading-more? room-id])
         loading-forward? (get-in db [:timeline/loading-forward? room-id])]
     (if (and room-id (not loading-back?) (not loading-forward?))
       (do
         (log/info "Triggering Worker Back Paginate for" room-id)
         (go
           (let [res (<! (main/do-with-pool! @state/!engine-pool
                                             {:handler :paginate-timeline
                                              :arguments {:room-id room-id :direction :back :amount 50}}))]
             (re-frame/dispatch [:sdk/pagination-complete room-id])))
         {:db (assoc-in db [:timeline/loading-more? room-id] true)})
       {}))))

(re-frame/reg-event-db
 :timeline/set-loading
 (fn [db [_ room-id loading?]]
   (assoc-in db [:timeline/loading-more? room-id] loading?)))

(re-frame/reg-event-db
 :sdk/pagination-complete
 (fn [db [_ room-id]]
   (-> db
       (assoc-in [:timeline/loading-more? room-id] false)
       (assoc-in [:timeline/ui-metadata room-id :pending-anchor] nil))))

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
 :sdk/update-pagination-status
 (fn [db [_ room-id status]]
   (assoc-in db [:timeline-pagination room-id] status)))

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

(re-frame/reg-event-fx
 :room/jump-to-present
 (fn [{:keys [db]} [_ room-id]]
   (main/do-with-pool! @state/!engine-pool {:handler :cleanup-timeline
                                            :arguments {:room-id room-id}})
   {:db (-> db
            (update-in [:timeline-subs room-id] dissoc :focused-timeline :focused-handle)
            (assoc-in [:timeline-data room-id :focused] [])
            (assoc-in [:timeline/ui-state room-id :animating-jump?] true))
    :dispatch-later [{:ms 50
                      :dispatch [:room/jump-to-live-bottom room-id]}]}))

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
   (let [my-id         (:user-id profile)
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
               set-virtua-ref #(reset! !virtua-ref %)
               !scroll-ref    (atom nil)
               set-scroll-ref #(reset! !scroll-ref %)
               !current-room  (r/atom initial-room-id)
               !at-bottom?    (r/atom true)
               !show-jump?    (r/atom false)
               !prev-first-id (atom nil)
               !prev-last-id  (atom nil)
               !initialized?  (r/atom false)
               !scroll-timer  (atom nil)]

    (fn [room-id]
      (when (not= room-id @!current-room)
        (reset! !current-room room-id)
        (reset! !initialized? false)
        (reset! !prev-first-id nil)
        (reset! !prev-last-id nil)
        (reset! !at-bottom? true)
        (reset! !show-jump? false))

      (let [events           @(re-frame/subscribe [:timeline/current-events room-id])
            event-array      (to-array events)
            cnt              (count event-array)
            loading?         @(re-frame/subscribe [:timeline/loading-more? room-id])
            loading-forward? @(re-frame/subscribe [:timeline/loading-forward? room-id])
            jump-target      @(re-frame/subscribe [:timeline/jump-target-id room-id])
            focus-mode?      @(re-frame/subscribe [:room/is-focused? room-id])

            real-events      (filter #(not= (:type %) :virtual) events)
            current-first-id (:id (first real-events))
            current-last-id  (:id (last real-events))

            did-prepend?     (boolean (and @!prev-first-id
                                           (not= current-first-id @!prev-first-id)
                                           (= current-last-id @!prev-last-id)))

            new-msg-arrived? (boolean (and @!prev-last-id
                                           (not= current-last-id @!prev-last-id)
                                           (not did-prepend?)))

            check-fill-viewport! (fn []
                                   (when-let [target @!scroll-ref]
                                     (let [max-scroll (- (.-scrollHeight target) (.-clientHeight target))]
                                       (when (and (<= max-scroll 0) (not loading?))
                                         (re-frame/dispatch [:sdk/back-paginate room-id])))))

            do-jump!         (fn []
                               (if focus-mode?
                                 (re-frame/dispatch [:room/jump-to-live-bottom room-id])
                                 (some-> @!virtua-ref (.scrollToIndex (dec cnt) #js {:align "end"}))))]

        (reset! !prev-first-id current-first-id)
        (reset! !prev-last-id current-last-id)

        (when (and (not @!initialized?) (pos? cnt) @!virtua-ref)
          (let [target-idx (if jump-target
                             (let [idx (.findIndex event-array #(= (:id %) jump-target))]
                               (if (not= idx -1) idx (dec cnt)))
                             (dec cnt))
                align-opt  (if jump-target #js {:align "center"} #js {:align "end"})]
            (some-> @!virtua-ref (.scrollToIndex target-idx align-opt))
            (js/setTimeout #(do
                              (some-> @!virtua-ref (.scrollToIndex target-idx align-opt))
                              (reset! !initialized? true)
                              (check-fill-viewport!))
                           300)))

        (when (and @!initialized? did-prepend?)
          (js/setTimeout check-fill-viewport! 100))

        (when (and @!initialized?
                   @!at-bottom?
                   new-msg-arrived?
                   (not focus-mode?))
          (js/requestAnimationFrame
           (fn []
             (some-> @!virtua-ref (.scrollToIndex (dec cnt) #js {:align "end"}))
             (js/setTimeout #(some-> @!virtua-ref (.scrollToIndex (dec cnt) #js {:align "end"})) 200))))

        [:div.virtua-timeline-wrapper
         {:style {:flex 1
                  :position "relative"
                  :display "flex"
                  :flex-direction "column"
                  :min-height 0
                  :opacity (if @!initialized? 1 0)
                  :transition "opacity 0.15s ease-in-out"}}

         [:div.timeline-messages
          {:ref set-scroll-ref
           :class (when jump-target "jumping-animation")
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
                              dist-from-bottom (- max-scroll scroll-top)]
                          (when-not @!scroll-timer
                            (reset! !scroll-timer
                                    (js/setTimeout
                                     (fn []
                                       (reset! !scroll-timer nil)
                                       (reset! !show-jump? (> dist-from-bottom 600))
                                       (reset! !at-bottom? (<= dist-from-bottom 10))

                                       (when (and @!initialized?
                                                  (> max-scroll 0)
                                                  (<= scroll-top 500)
                                                  (not loading?))
                                         (re-frame/dispatch [:sdk/back-paginate room-id]))

                                       (when (and focus-mode?
                                                  (<= dist-from-bottom 500)
                                                  (not loading-forward?))
                                         (re-frame/dispatch [:sdk/forward-paginate room-id])))
                                     10)))))}

          (when loading?
            [timeline-loading-overlay])

          (if (zero? cnt)
            [timeline-empty-state]
            (into [:> Virtualizer
                   {:ref set-virtua-ref
                    :shift did-prepend?}]
                  (for [item event-array]
                    ^{:key (:id item)}
                    [:div.virtua-item-wrapper
                     {:style {:margin "0"
                              :min-height "32px"
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
;;        ^{:key active-id}
        [virtualized-timeline active-id]
        [message-input]
        [status-indicator active-id]])]))
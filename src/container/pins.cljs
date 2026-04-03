(ns container.pins
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [cljs-workers.core :as main]
   [client.state :as state]
   [cljs.core.async :refer [go <!]]
   [reagent.core :as r]
   [utils.svg :as icons]
   [container.reusable :refer [message-preview-item]]))





(re-frame/reg-event-fx
 :room/fetch-pinned-events
 (fn [{:keys [db]} [_ room-id]]
   (go
     (let [pool @state/!engine-pool
           res  (<! (main/do-with-pool! pool {:handler :get-pinned-events
                                              :arguments {:room-id room-id}}))]
       (if (= (:status res) "success")
         (re-frame/dispatch [:room/save-pinned-events room-id (:events res)])
         (log/error "Failed to fetch pinned events:" (:msg res)))))
   {}))


(re-frame/reg-event-db
 :room/save-pinned-events
 (fn [db [_ room-id pinned-events]]
   (assoc-in db [:rooms/data room-id :pinned-events] pinned-events)))

(re-frame/reg-sub
 :room/pinned-events
 (fn [db [_ room-id]]
   (get-in db [:rooms/data room-id :pinned-events] [])))

(re-frame/reg-sub
 :room/pinned-ids
 (fn [db [_ room-id]]
   (let [events (get-in db [:rooms/data room-id :pinned-events] [])]
     (mapv :id events))))

(re-frame/reg-sub
 :room/pinned-event-by-id
 (fn [db [_ room-id event-id]]
   (let [pins (get-in db [:rooms/data room-id :pinned-events] [])]
     (first (filter #(= (:id %) event-id) pins)))))

(defn pins []
  (let [active-room @(re-frame/subscribe [:rooms/active-id])
        pins        @(re-frame/subscribe [:room/pinned-events active-room])
        tr          @(re-frame/subscribe [:i18n/tr])]
    [:div.sidebar-pins-container
     {:style {:display "flex" :flex-direction "column" :height "100%" :padding "16px"}}
     (if (empty? pins)
       [:div.empty-pins
        {:style {:text-align "center" :margin-top "40px" :opacity 0.5}}
        [icons/pins {:width "48px" :height "48px"}]
        [:p (tr [:container.pins/no-pins] "No pinned messages")]]
       [:div.pins-list
        {:style {:flex 1 :overflow-y "auto"}}
        (for [event pins]
          ^{:key (:id event)}
          [message-preview-item active-room event])])]))

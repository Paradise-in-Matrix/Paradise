(ns container.search
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [cljs-workers.core :as main]
   [client.state :as state]
   [cljs.core.async :refer [go <!]]
   [reagent.core :as r]
   [utils.svg :as icons]
   [container.reusable :refer [message-preview-item]]))

(re-frame/reg-event-fx
 :room/execute-search
 (fn [{:keys [db]} [_ room-id query]]
   (if (str/blank? query)
     (log/warn "Search aborted: Empty query")
     (do
       (go
         (let [pool @state/!engine-pool
               res  (<! (main/do-with-pool! pool {:handler :search-room
                                                  :arguments {:room-id room-id :query query}}))]
           (if (= (:status res) "success")
             (re-frame/dispatch [:room/search-success room-id (:results res)])
             (re-frame/dispatch [:room/search-error room-id (:msg res)]))))
       {:db (-> db
                (assoc-in [:search-data room-id :loading?] true)
                (assoc-in [:search-data room-id :query] query)
                (assoc-in [:search-data room-id :results] []))}))))

(re-frame/reg-event-db
 :room/search-success
 (fn [db [_ room-id normalized-hits]]
   (-> db
       (assoc-in [:search-data room-id :results] normalized-hits)
       (assoc-in [:search-data room-id :loading?] false))))

(re-frame/reg-event-db
 :room/search-error
 (fn [db [_ room-id err]]
   (log/error "Search API failed:" err)
   (assoc-in db [:search-data room-id :loading?] false)))

(re-frame/reg-sub
 :room/search-state
 (fn [db [_ room-id]]
   (get-in db [:search-data room-id] {:loading? false :results [] :query ""})))

(defn search []
  (let [active-room  @(re-frame/subscribe [:rooms/active-id])
        search-state @(re-frame/subscribe [:room/search-state active-room])
        loading?     (:loading? search-state)
        results      (:results search-state)
        tr           @(re-frame/subscribe [:i18n/tr])]
    (r/with-let [!local-query (r/atom (:query search-state ""))]
      [:div.sidebar-search-container
       [:div.search-input-wrapper
        [:div.search-input-box
         [icons/search {:width "20px" :height "20px" :style {:margin-right "8px" :opacity 0.5}}]
         [:input.search-input
          {:type         "text"
           :placeholder  (tr [:container.search/placeholder])
           :value        @!local-query
           :on-change    #(reset! !local-query (.. % -target -value))
           :on-key-down  #(when (= (.-key %) "Enter")
                            (re-frame/dispatch [:room/execute-search active-room @!local-query]))}]]]

       [:div.search-results-area
        (cond
          loading?
          [:div.search-status
           [:div.spinner]
           (tr [:container.search/loading])]

          (and (not loading?) (seq results))
          [:div.search-list
           (for [res results]
             ^{:key (:id res)}
             [message-preview-item active-room res])]

          (and (not loading?) (not (str/blank? (:query search-state))) (empty? results))
          [:div.search-status
           [icons/search {:width "48px" :height "48px" :style {:margin-bottom "16px" :opacity 0.3}}]
           [:div (tr [:container.search/no-results])]]

          :else
          [:div.search-status
           [icons/search {:width "48px" :height "48px" :style {:margin-bottom "16px" :opacity 0.3}}]
           [:div (tr [:container.search/initial])]])]])))
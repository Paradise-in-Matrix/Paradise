(ns container.search
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [promesa.core :as p]
   [clojure.string :as str]
   ["react-virtuoso" :refer [Virtuoso GroupedVirtuoso]]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]
   [utils.helpers :refer [mxc->url fetch-state-event]]
   [utils.svg :as icons]
   [container.reusable :refer [make-timeline-item-shim message-preview-item]]
   [container.timeline.base :refer [apply-timeline-diffs!]]
   [container.timeline.item :refer [event-tile]]
   [utils.svg :as icons]
   [utils.global-ui :refer [avatar]]
   ["ffi-bindings" :as sdk :refer [RoomMessageEventContentWithoutRelation MessageType EditedContent TimelineConfiguration]]
   )
  )

(defn msgtype->rust-tag [msgtype]
  (case msgtype
    "m.text"   "Text"
    "m.emote"  "Emote"
    "m.notice" "Notice"
    "m.image"  "Image"
    "m.video"  "Video"
    "m.audio"  "Audio"
    "m.file"   "File"
    "Text"))

(defn normalize-search-result [hit]
  (let [evt          (:result hit)
        raw-content  (:content evt)
        edited?      (some? (:m.new_content raw-content))
        real-content (if edited? (:m.new_content raw-content) raw-content)
        msgtype      (:msgtype real-content)]
    {:id          (:event_id evt)
     :internal-id (:event_id evt)
     :sender-id   (:sender evt)
     :ts          (:origin_server_ts evt)
     :is-edited?  edited?
     :content-tag "MsgLike"
     :content     {:tag "Message"
                   :inner {:tag     (msgtype->rust-tag msgtype)
                           :content real-content}}
     :type        :timeline}))


(re-frame/reg-fx
 :room/matrix-search
 (fn [{:keys [homeserver token room-id query]}]
   (let [base-server  (if (str/starts-with? homeserver "http")
                        homeserver
                        (str "https://" homeserver))
         clean-server (str/replace base-server #"/+$" "")
         url          (str clean-server "/_matrix/client/v3/search")
         body (clj->js {:search_categories
                        {:room_events
                         {:search_term query
                          :order_by    "recent"
                          :filter      {:rooms [room-id]}
                          :event_context {:before_limit 1 :after_limit 1}}}})]
     (-> (js/fetch url
                   #js {:method "POST"
                        :headers #js {"Authorization" (str "Bearer " token)
                                      "Content-Type"  "application/json"}
                        :body (js/JSON.stringify body)})
         (p/then (fn [res]
                   (if (.-ok res)
                     (.json res)
                     (throw (js/Error. (str "Search failed with status: " (.-status res)))))))
         (p/then #(re-frame/dispatch [:room/search-success room-id %]))
         (p/catch #(re-frame/dispatch [:room/search-error room-id %]))))))


(re-frame/reg-event-fx
 :room/execute-search
 (fn [{:keys [db]} [_ room-id query]]
   (let [client     (:client db)
          session    (some-> client (.session))
         homeserver (some-> session (.-homeserverUrl))
         token      (some-> session (.-accessToken))]
     (if (and homeserver token (not (str/blank? query)))
       {:db (-> db
                (assoc-in [:search-data room-id :loading?] true)
                (assoc-in [:search-data room-id :query] query)
                (assoc-in [:search-data room-id :results] []))
        :room/matrix-search {:homeserver homeserver
                             :token      token
                             :room-id    room-id
                             :query      query}}
       (log/warn "Search aborted: Missing session or empty query")))))

(re-frame/reg-event-db
 :room/search-success
 (fn [db [_ room-id response]]
   (log/error response)
   (let [hits       (some-> response
        (.-search_categories)
        (.-room_events)
        (.-results))
         normalized (map #(normalize-search-result  (js->clj % :keywordize-keys true)) hits)]
     (-> db
         (assoc-in [:search-data room-id :results] normalized)
         (assoc-in [:search-data room-id :loading?] false)))))

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

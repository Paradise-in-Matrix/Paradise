(ns container.call.core
  (:require
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [container.call.call-container :refer [primary-iframe-ref backup-iframe-ref]]
   [container.call.call-view :as call-view]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [cljs.core.async :refer [go <!]]
   [cljs-workers.core :as main]
   [client.state :as state]))

(defonce window-listener-attached? (atom false))


(rf/reg-event-fx
 :call/finalize-init
 (fn [{:keys [db]} [_ room-id new-primary? is-encrypted? final-url]]
   {:db (-> db
            (assoc-in [:call :active-room-id] room-id)
            (assoc-in [:call :widget-url] final-url)
            (assoc-in [:call :primary-iframe?] new-primary?)
            (assoc-in [:call :encrypted?] is-encrypted?)
            (assoc-in [:call :loading?] false))
    :dispatch [:call/attach-window-listener]}))



(rf/reg-event-fx
 :call/init-widget
 (fn [{:keys [db]} [_ room-id opts]]
   (let [current-primary? (get-in db [:call :primary-iframe?] true)
         new-primary?     (not current-primary?)
         target-iframe    (if new-primary? @primary-iframe-ref @backup-iframe-ref)
         join-directly?   (:join-directly? opts true)
         local-url        (str (.. js/window -location -origin) "/element-call/index.html")
         pool             @state/!engine-pool]

     (if-not pool
       (do (log/error "Cannot init widget: No worker pool") {})
       (go
         (let [res (<! (main/do-with-pool! pool {:handler :init-call-widget
                                                 :arguments {:room-id        room-id
                                                             :join-directly? join-directly?
                                                             :local-url      local-url}}))]
           (if (= (:status res) "success")
             (let [raw-url        (:raw-url res)
                   is-encrypted?  (:is-encrypted? res)
                   client-user-id (:user-id res)
                   device-id      (:device-id res)
                   hs-url         (get db :homeserver-url)
                   actual-id      (str "element-call-" room-id)
                   base           (str/replace raw-url #"\?.*$|\#.*$" "")
                   intent-str     (if join-directly? "start_call" "join_existing")
                   skip-lobby-str (if join-directly? "true" "false")
                   widget-query   (str "?widgetId=" (js/encodeURIComponent actual-id)
                                       "&parentUrl=" (js/encodeURIComponent (.. js/window -location -origin))
                                       "&userId=" (js/encodeURIComponent client-user-id)
                                       "&deviceId=" (js/encodeURIComponent device-id)
                                       "&baseUrl=" (js/encodeURIComponent hs-url))
                   app-fragment   (str "#/?intent=" intent-str
                                       "&skipLobby=" skip-lobby-str
                                       "&roomId=" (js/encodeURIComponent room-id)
                                       "&perParticipantE2EE=" (if is-encrypted? "true" "false")
                                       "&theme=dark&lang=en")
                   final-url      (str base widget-query app-fragment)]

               (set! (.-src target-iframe) final-url)
               (rf/dispatch [:call/finalize-init room-id new-primary? is-encrypted? final-url]))
             (log/error "Worker failed to init widget:" (:msg res))))))
     {:db (assoc-in db [:call :loading?] true)})))

(rf/reg-event-fx
 :call/attach-window-listener
 (fn [_ _]
   (when-not @window-listener-attached?
     (reset! window-listener-attached? true)
     (.addEventListener js/window "message"
       (fn [event]
         (when (= (.-origin event) (.. js/window -location -origin))
           (let [raw-data   (.-data event)
                 action     (str (.-action raw-data))
                 req-id     (.-requestId raw-data)
                 widget-id  (.-widgetId raw-data)
                 msg-string (js/JSON.stringify raw-data)]

             (when (= action "io.element.device_mute")
               (let [d (.-data raw-data)]
                 (rf/dispatch [:call/update-media-state
                               {:audio (.-audio_enabled d)
                                :video (.-video_enabled d)}])))

             (when (str/starts-with? action "io.element.")
               (let [is-primary? (get-in @rf-db/app-db [:call :primary-iframe?] true)
                     iframe-ref  (if is-primary? primary-iframe-ref backup-iframe-ref)]
                 (when-let [iframe @iframe-ref]
                   (let [ack #js {:api       "toWidget"
                                  :action    action
                                  :requestId req-id
                                  :widgetId  widget-id
                                  :response  #js {}
                                  :data      #js {}}]
                     (.postMessage (.-contentWindow iframe) ack "*")))))

             (if-let [pool @state/!engine-pool]
               (main/do-with-pool! pool {:handler :send-widget-message
                                         :arguments {:msg-string msg-string}})
               (log/error "No worker pool to route iframe message!")))))))
   {}))
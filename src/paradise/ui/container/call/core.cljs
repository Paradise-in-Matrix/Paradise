(ns paradise.ui.container.call.core
  (:require
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [paradise.ui.container.call.call-container :refer [primary-iframe-ref backup-iframe-ref]]
   [paradise.ui.container.call.call-view :as call-view]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [cljs.core.async :refer [go <!]]
   [cljs-workers.core :as main]
   [paradise.shared.client.state :as state]))

(defonce window-listener-attached? (atom false))

(rf/reg-event-fx
 :call/finalize-init
 (fn [{:keys [db]} [_ room-id target-iframe-key is-encrypted? final-url join-directly?]]
   (let [base-call (-> (:call db)
                       (assoc :widget-url final-url)
                       (assoc :visible-iframe target-iframe-key)
                       (assoc-in [:iframes target-iframe-key] room-id)
                       (assoc :encrypted? is-encrypted?)
                       (assoc :loading? false))]
     (if (or join-directly? (nil? (:active-room-id base-call)))
       {:db (assoc db :call (-> base-call
                                (assoc :active-room-id room-id)
                                (assoc :active-iframe target-iframe-key)
                                (assoc :is-active? true)))
        :dispatch [:call/attach-window-listener]}
       {:db (assoc db :call base-call)
        :dispatch [:call/attach-window-listener]}))))

(rf/reg-event-fx
 :call/init-widget
 (fn [{:keys [db]} [_ room-id opts]]
   (let [active-iframe   (get-in db [:call :active-iframe])
         visible-iframe  (get-in db [:call :visible-iframe] :primary)
         iframes         (get-in db [:call :iframes] {:primary nil :backup nil})
         join-directly?  (:join-directly? opts true)
         existing-iframe (cond
                           (= (:primary iframes) room-id) :primary
                           (= (:backup iframes) room-id) :backup
                           :else nil)]

     (if existing-iframe
       {:db (-> db
                (assoc-in [:call :visible-iframe] existing-iframe)
                (assoc-in [:call :loading?] false)
                (cond-> join-directly? (assoc-in [:call :active-room-id] room-id)))}
       (let [target-iframe-key (if active-iframe
                                 (if (= active-iframe :primary) :backup :primary)
                                 (if (= visible-iframe :primary) :backup :primary))
             target-iframe     (if (= target-iframe-key :primary) @primary-iframe-ref @backup-iframe-ref)
             local-url         (str (.. js/window -location -origin) "/element-call/index.html")
             pool              @state/!engine-pool]

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
                       actual-id      (str "element-call-" room-id)
                       base           (str/replace raw-url #"\?.*$|\#.*$" "")
                       intent-str     (if join-directly? "start_call" "join_existing")
                       skip-lobby-str (if join-directly? "true" "false")
                       widget-query   (str "?widgetId=" (js/encodeURIComponent actual-id)
                                           "&parentUrl=" (js/encodeURIComponent (.. js/window -location -origin))
                                           "&userId=" (js/encodeURIComponent (:user-id res))
                                           "&deviceId=" (js/encodeURIComponent (:device-id res))
                                           "&baseUrl=" (js/encodeURIComponent (get db :homeserver-url))
                                           "&_t=" (.now js/Date))
                       app-fragment   (str "#/?intent=" intent-str
                                           "&skipLobby=" skip-lobby-str
                                           "&roomId=" (js/encodeURIComponent room-id)
                                           "&perParticipantE2EE=" (if is-encrypted? "true" "false")
                                           "&theme=dark&lang=en")
                       final-url      (str base widget-query app-fragment)]
                   (set! (.-src target-iframe) "about:blank")
                   (js/setTimeout #(set! (.-src target-iframe) final-url) 10)
                   (rf/dispatch [:call/finalize-init room-id target-iframe-key is-encrypted? final-url join-directly?]))
                 (log/error "Worker failed to init widget:" (:msg res))))))
         {:db (assoc-in db [:call :loading?] true)})))))


(rf/reg-event-fx
 :call/widget-joined
 (fn [{:keys [db]} [_ widget-id]]
   (let [room-id        (str/replace widget-id #"^element-call-" "")
         active-call-id (get-in db [:call :active-room-id])
         iframes        (get-in db [:call :iframes])
         joined-iframe  (if (= (:primary iframes) room-id) :primary :backup)
         active-iframe  (get-in db [:call :active-iframe])]

     (if (and active-call-id (not= active-call-id room-id))
       (let [old-iframe-ref (if (= active-iframe :primary) primary-iframe-ref backup-iframe-ref)]
         (when-let [iframe @old-iframe-ref]
           (let [req-id (str "im.vector.hangup-" (.now js/Date))
                 msg #js {:api "toWidget" :action "im.vector.hangup" :widgetId (str "element-call-" active-call-id) :requestId req-id :data #js {}}]
             (try (.postMessage (.-contentWindow iframe) msg "*") (catch :default _)))
           (set! (.-src iframe) "about:blank"))
         {:db (-> db
                  (assoc-in [:call :active-room-id] room-id)
                  (assoc-in [:call :active-iframe] joined-iframe)
                  (assoc-in [:call :visible-iframe] joined-iframe)
                  (assoc-in [:call :iframes (if (= active-iframe :primary) :primary :backup)] nil)
                  (assoc-in [:call :is-active?] true))})
       {:db (-> db
                (assoc-in [:call :active-room-id] room-id)
                (assoc-in [:call :active-iframe] joined-iframe)
                (assoc-in [:call :visible-iframe] joined-iframe)
                (assoc-in [:call :is-active?] true))}))))

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
                 (rf/dispatch [:call/update-media-state {:audio (.-audio_enabled d) :video (.-video_enabled d)}])))

             (when (or (= action "io.element.join")
                       (and (= action "io.element.call.state")
                            (let [st (.-state (.-data raw-data))] (or (= st "joined") (= st "connected")))))
               (rf/dispatch [:call/widget-joined widget-id]))

             (when (= action "im.vector.hangup")
               (rf/dispatch [:call/handle-widget-hangup widget-id]))

             (when (or (str/starts-with? action "io.element.")
                       (str/starts-with? action "im.vector."))
               (let [ack #js {:api       "toWidget"
                              :action    action
                              :requestId req-id
                              :widgetId  widget-id
                              :response  #js {}
                              :data      #js {}}]
                 (.postMessage (.-source event) ack "*")))

             (if-let [pool @state/!engine-pool]
               (main/do-with-pool! pool {:handler :send-widget-message
                                         :arguments {:msg-string msg-string}})
               (log/error "No worker pool to route iframe message!")))))))
   {}))
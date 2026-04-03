(ns container.call.events
  (:require [re-frame.core :as rf]
            [taoensso.timbre :as log]
            [cljs-workers.core :as main]
            [client.state :as state]
            [container.call.call-container :refer [primary-iframe-ref backup-iframe-ref apply-iframe-sound-state!]]))

(defn click-iframe-button! [iframe-ref test-id]
  (when-let [iframe @iframe-ref]
    (let [doc (or (.-contentDocument iframe)
                  (.. iframe -contentWindow -document))]
      (if-let [btn (.querySelector doc (str "[data-testid='" test-id "']"))]
        (.click btn)
        (log/error "Could not find button with test-id:" test-id)))))

(defn send-widget-action! [db action data]
  (let [is-primary? (get-in db [:call :primary-iframe?] true)
        iframe-ref  (if is-primary? primary-iframe-ref backup-iframe-ref)]
    (when-let [iframe @iframe-ref]
      (let [widget-id  (str "element-call-" (get-in db [:call :active-room-id]))
            req-id     (str action "-" (.now js/Date))
            msg        #js {:api       "toWidget"
                            :action    action
                            :widgetId  widget-id
                            :requestId req-id
                            :data      (clj->js data)}]
        (try
          (.postMessage (.-contentWindow iframe) msg "*")
          (catch :default e
            (log/error "Failed to send action" action "to iframe:" e)))))))

(rf/reg-event-fx
 :call/recv-widget-message
 (fn [{:keys [db]} [_ msg-string]]
   (let [is-primary? (get-in db [:call :primary-iframe?] true)
         iframe-ref  (if is-primary? primary-iframe-ref backup-iframe-ref)]
     (when-let [iframe @iframe-ref]
       (try
         (.postMessage (.-contentWindow iframe) (js/JSON.parse msg-string) "*")
         (catch :default e
           (log/error "Failed to pipe worker message to iframe:" e)))))
   {}))

(rf/reg-event-fx
 :call/hangup
 (fn [{:keys [db]} [_ opts]]
   (log/info "Call: Sending hangup action to widget...")
   (let [wipe?       (get opts :wipe-state? true)
         is-primary? (get-in db [:call :primary-iframe?] true)
         iframe-ref  (if is-primary? primary-iframe-ref backup-iframe-ref)]
     (send-widget-action! db "im.vector.hangup" {})
     (when-let [iframe @iframe-ref]
       (set! (.-src iframe) "about:blank"))
     (if wipe?
       {:dispatch [:call/teardown]}
       {}))))

(rf/reg-event-fx
 :call/toggle-audio
 (fn [{:keys [db]} _]
   (let [audio-enabled? (get-in db [:call :audio-enabled?] true)
         video-enabled? (get-in db [:call :video-enabled?] false)
         new-state      (not audio-enabled?)]
     (send-widget-action! db "io.element.device_mute"
                          {:audio_enabled new-state
                           :video_enabled video-enabled?})
     {:db (assoc-in db [:call :audio-enabled?] new-state)})))

(rf/reg-event-fx
 :call/toggle-video
 (fn [{:keys [db]} _]
   (let [audio-enabled? (get-in db [:call :audio-enabled?] true)
         video-enabled? (get-in db [:call :video-enabled?] false)
         new-state      (not video-enabled?)]
     (send-widget-action! db "io.element.device_mute"
                          {:audio_enabled audio-enabled?
                           :video_enabled new-state})
     {:db (assoc-in db [:call :video-enabled?] new-state)})))

(rf/reg-event-fx
 :call/toggle-deafen
 (fn [{:keys [db]} _]
   (let [currently-deafened? (get-in db [:call :deafened?] false)
         new-deafen-state    (not currently-deafened?)
         is-primary?         (get-in db [:call :primary-iframe?] true)
         iframe-ref          (if is-primary? primary-iframe-ref backup-iframe-ref)]
     (apply-iframe-sound-state! iframe-ref (not new-deafen-state))
     {:db (assoc-in db [:call :deafened?] new-deafen-state)})))

(rf/reg-event-fx
 :call/toggle-screen-share
 (fn [{:keys [db]} _]
   (let [is-primary? (get-in db [:call :primary-iframe?] true)
         iframe-ref  (if is-primary? primary-iframe-ref backup-iframe-ref)]
     (click-iframe-button! iframe-ref "incall_screenshare")
     {})))

(rf/reg-event-fx
 :call/open-settings
 (fn [{:keys [db]} _]
   (let [is-primary? (get-in db [:call :primary-iframe?] true)
         iframe-ref  (if is-primary? primary-iframe-ref backup-iframe-ref)]
     (when-let [iframe @iframe-ref]
       (let [doc (or (.-contentDocument iframe) (.. iframe -contentWindow -document))
             btn (.querySelector doc "button[aria-labelledby=':rt:']")]
         (if btn (.click btn) (log/error "Settings button not found"))))
     {})))

(rf/reg-event-db
 :call/toggle-chat
 (fn [db _]
   (update-in db [:call :chat-open?] not)))

(rf/reg-event-db
 :call/toggle-iframe
 (fn [db _]
   (update-in db [:call :primary-iframe?] not)))

(rf/reg-event-fx
 :call/teardown
 (fn [{:keys [db]} _]
   (log/info "Call: Tearing down persistent call layer")
   (when-let [pool @state/!engine-pool]
     (main/do-with-pool! pool {:handler :teardown-widget}))

   (when-let [p-iframe @primary-iframe-ref] (set! (.-src p-iframe) "about:blank"))
   (when-let [b-iframe @backup-iframe-ref] (set! (.-src b-iframe) "about:blank"))

   {:db (-> db
            (assoc-in [:call :active-room-id] nil)
            (assoc-in [:call :is-active?] false)
            (assoc :main-focus :timeline))}))

(rf/reg-event-db :call/set-active-room
 (fn [db [_ room-id]]
   (log/info "Call: Setting active room to" room-id)
   (-> db
       (assoc-in [:call :active-room-id] room-id)
       (assoc-in [:call :audio-enabled?] true)
       (assoc-in [:call :video-enabled?] false))))

(rf/reg-event-db :call/set-active
 (fn [db [_ active?]]
   (assoc-in db [:call :is-active?] active?)))

(rf/reg-event-db :call/update-media-state
 (fn [db [_ {:keys [audio video]}]]
   (-> db
       (assoc-in [:call :audio-enabled?] audio)
       (assoc-in [:call :video-enabled?] video))))

(rf/reg-sub :call/deafened?        (fn [db _] (get-in db [:call :deafened?] false)))
(rf/reg-sub :call/screen-sharing?  (fn [db _] (get-in db [:call :screen-sharing?] false)))
(rf/reg-sub :call/audio-enabled?   (fn [db _] (get-in db [:call :audio-enabled?] true)))
(rf/reg-sub :call/video-enabled?   (fn [db _] (get-in db [:call :video-enabled?] false)))
(rf/reg-sub :call/chat-open?       (fn [db _] (get-in db [:call :chat-open?] false)))
(rf/reg-sub :call/state            (fn [db _] (:call db)))
(rf/reg-sub :call/active-room      (fn [db _] (get-in db [:call :active-room-id])))
(rf/reg-sub :call/is-active?       (fn [db _] (get-in db [:call :is-active?])))
(rf/reg-sub :call/is-primary-iframe? (fn [db _] (get-in db [:call :primary-iframe?] true)))
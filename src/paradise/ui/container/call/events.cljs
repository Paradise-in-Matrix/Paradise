(ns paradise.ui.container.call.events
  (:require [re-frame.core :as rf]
            [taoensso.timbre :as log]
            [cljs-workers.core :as main]
            [clojure.string :as str]
            [paradise.shared.client.state :as state]
            [paradise.ui.container.call.call-container :refer [primary-iframe-ref backup-iframe-ref apply-iframe-sound-state!]]))

(defn get-active-iframe-ref [db]
  (let [active-iframe (get-in db [:call :active-iframe])
        visible-iframe (get-in db [:call :visible-iframe] :primary)
        target (or active-iframe visible-iframe)]
    (if (= target :primary) primary-iframe-ref backup-iframe-ref)))

(defn get-visible-iframe-ref [db]
  (let [visible-iframe (get-in db [:call :visible-iframe] :primary)]
    (if (= visible-iframe :primary) primary-iframe-ref backup-iframe-ref)))

(defn click-iframe-button! [iframe-ref test-id]
  (when-let [iframe @iframe-ref]
    (let [doc (or (.-contentDocument iframe) (.. iframe -contentWindow -document))]
      (if-let [btn (.querySelector doc (str "[data-testid='" test-id "']"))]
        (.click btn)
        (log/error "Could not find button with test-id:" test-id)))))

(defn send-widget-action! [db action data]
  (let [iframe-ref     (get-active-iframe-ref db)
        active-room-id (get-in db [:call :active-room-id])]
    (when-let [iframe @iframe-ref]
      (let [widget-id  (str "element-call-" active-room-id)
            req-id     (str action "-" (.now js/Date))
            msg        #js {:api "toWidget", :action action, :widgetId widget-id, :requestId req-id, :data (clj->js data)}]
        (try (.postMessage (.-contentWindow iframe) msg "*")
             (catch :default e (log/error "Failed to send action" action "to iframe:" e)))))))

(rf/reg-event-fx
 :call/recv-widget-message
 (fn [{:keys [db]} [_ msg-string]]
   (let [parsed         (js/JSON.parse msg-string)
         widget-id      (.-widgetId parsed)
         target-room-id (when widget-id (str/replace widget-id #"^element-call-" ""))
         iframes        (get-in db [:call :iframes])
         iframe-ref     (cond
                          (= (:primary iframes) target-room-id) primary-iframe-ref
                          (= (:backup iframes) target-room-id)  backup-iframe-ref
                          :else (get-visible-iframe-ref db))]
     (when-let [iframe @iframe-ref]
       (try (.postMessage (.-contentWindow iframe) parsed "*")
            (catch :default e (log/error "Failed to pipe worker message to iframe:" e)))))
   {}))


(rf/reg-event-fx
 :call/hangup
 (fn [{:keys [db]} [_ opts]]
   (let [wipe?          (get opts :wipe-state? true)
         skip-native?   (get opts :skip-native? false)
         target-room-id (or (:room-id opts) (get-in db [:call :active-room-id]))
         iframes        (get-in db [:call :iframes])
         iframe-key     (cond
                          (= (:primary iframes) target-room-id) :primary
                          (= (:backup iframes) target-room-id) :backup
                          :else nil)
         iframe-ref     (cond
                          (= iframe-key :primary) primary-iframe-ref
                          (= iframe-key :backup)  backup-iframe-ref
                          :else nil)]
     (when (and target-room-id (not skip-native?))
       (paradise.ui.container.call.native/end-call! target-room-id))
     (when iframe-ref
       (click-iframe-button! iframe-ref "incall_leave")
       (when-let [iframe @iframe-ref]
         (let [widget-id (str "element-call-" target-room-id)
               req-id    (str "io.element.hangup-" (.now js/Date))
               msg       #js {:api "toWidget" :action "io.element.hangup" :widgetId widget-id :requestId req-id :data #js {}}]
           (try (.postMessage (.-contentWindow iframe) msg "*") (catch :default _)))))
     (if wipe?
       {:db (-> db
                (assoc-in [:call :active-room-id] nil)
                (assoc-in [:call :active-iframe] nil)
                (assoc-in [:call :iframes] {:primary nil :backup nil})
                (assoc-in [:call :is-active?] false))
        :dispatch [:call/teardown]}
       (let [new-db (cond-> db
                      iframe-key (assoc-in [:call :iframes iframe-key] nil)
                      true       (assoc-in [:call :active-room-id] nil))]
         {:db new-db})))))

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
   (let [new-deafen-state (not (get-in db [:call :deafened?] false))]
     (apply-iframe-sound-state! primary-iframe-ref (not new-deafen-state))
     (apply-iframe-sound-state! backup-iframe-ref (not new-deafen-state))
     {:db (assoc-in db [:call :deafened?] new-deafen-state)})))


(rf/reg-event-fx
 :call/toggle-screen-share
 (fn [{:keys [db]} _] (click-iframe-button! (get-active-iframe-ref db) "incall_screenshare") {}))

(rf/reg-event-fx
 :call/open-settings
 (fn [{:keys [db]} _]
   (when-let [iframe @(get-visible-iframe-ref db)]
     (let [doc (or (.-contentDocument iframe) (.. iframe -contentWindow -document))
           btn (.querySelector doc "button[aria-labelledby=':rt:']")]
       (if btn (.click btn) (log/error "Settings button not found")))) {}))


(rf/reg-event-db
 :call/toggle-chat
 (fn [db _]
   (update-in db [:call :chat-open?] not)))

(rf/reg-event-db
 :call/toggle-iframe
 (fn [db _]
   (let [current-vis (get-in db [:call :visible-iframe] :primary)]
     (assoc-in db [:call :visible-iframe] (if (= current-vis :primary) :backup :primary)))))

(rf/reg-event-fx
 :call/handle-widget-hangup
 (fn [{:keys [db]} [_ widget-id]]
   (let [target-room-id (when widget-id (str/replace widget-id #"^element-call-" ""))
         active-room-id (get-in db [:call :active-room-id])]
     (when target-room-id (paradise.ui.container.call.native/end-call! target-room-id))

     (if (and active-room-id (= target-room-id active-room-id))
       {:dispatch [:call/teardown]}
       {}))))

(rf/reg-event-fx
 :call/teardown
 (fn [{:keys [db]} _]
   (when-let [pool @state/!engine-pool]
     (main/do-with-pool! pool {:handler :teardown-widget}))
   (when-let [p-iframe @primary-iframe-ref]
     (js/setTimeout #(set! (.-src p-iframe) "about:blank") 5000))
   (when-let [b-iframe @backup-iframe-ref]
     (js/setTimeout #(set! (.-src b-iframe) "about:blank") 5000))

   {:db (-> db
            (assoc-in [:call :active-room-id] nil)
            (assoc-in [:call :active-iframe] nil)
            (assoc-in [:call :iframes] {:primary nil :backup nil})
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
(rf/reg-sub :call/is-primary-iframe? (fn [db _] (= (get-in db [:call :visible-iframe] :primary) :primary)))
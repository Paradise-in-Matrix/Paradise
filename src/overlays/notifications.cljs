(ns overlays.notifications
(:require [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [promesa.core :as p]
            [cljs.core.async :refer [go <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            ["@capacitor/core" :refer [Capacitor registerPlugin]]
            ["@capacitor/push-notifications" :refer [PushNotifications]]
            [client.state :refer [!config] :as state]
            [cljs-workers.core :as main]))

(def shadow-device (registerPlugin "ShadowDevice"))

(re-frame/reg-event-fx
 :push/create-sleepy-shadow
 (fn [_ [_ credentials]]
   (log/info "1. Creating Sleepy Shadow Device...")
   (try
     (-> (.createSleepyShadow shadow-device
           #js {:homeserverUrl (:homeserver credentials)
                :username (:username credentials)
                :password (:password credentials)})
         (.then #(log/info "2. Sleepy Shadow SUCCESS:" (.-status %)))
         (.catch #(log/error "2. Sleepy Shadow FAILED:" %)))
     (catch js/Error e
       (log/error "Synchronous CLJS Crash in createSleepyShadow:" e)))
   {}))

(re-frame/reg-event-fx
 :push/activate-shadow
 (fn [_ [_ token]]
   (log/info "Activating Shadow Device with FCM Token...")
   (try
     (-> (.activateShadow shadow-device
           #js {:pushToken token
                :pushUrl (:push-url @!config)})
         (.then (fn [res]
                  (log/info "Shadow Activated SUCCESS:" (.-status res))
                  (re-frame/dispatch [:push/set-status :enabled])))
         (.catch #(log/error "Shadow Activated FAILED:" %)))
     (catch js/Error e
       (log/error "Synchronous CLJS Crash in activateShadow:" e)))
   {}))

(re-frame/reg-event-fx
 :push/deactivate-shadow
 (fn [{:keys [db]} _]
   (when-let [token (:native-token db)]
     (try
       (-> (.deactivateShadow shadow-device
             #js {:pushToken token})
           (.then (fn [res]
                    (log/info "Shadow Deactivated SUCCESS:" (.-status res))
                    (re-frame/dispatch [:push/set-status :disabled])))
           (.catch #(log/error "Shadow Deactivated FAILED:" %)))
       (catch js/Error e
         (log/error "Synchronous CLJS Crash in deactivateShadow:" e))))
   {}))



(re-frame/reg-event-fx
 :push/store-native-token
 (fn [{:keys [db]} [_ token]]
   {:db (assoc db :native-token token)
    :fx [[:dispatch [:push/activate-shadow token]]]}))

(re-frame/reg-sub
 :push/native-token
 (fn [db _]
   (:native-token db)))

(defn init! []
  (when (.isNativePlatform Capacitor)
    (.addListener PushNotifications "registration"
      (fn [token-res]
        (let [token (.-value token-res)]
          (log/info "Native Push Token Received:" token)
          (re-frame/dispatch [:push/store-native-token token]))))
    (.addListener PushNotifications "registrationError"
      (fn [err]
        (log/error "Native Push Registration Error:" (.-error err))))
    (.addListener PushNotifications "pushNotificationReceived"
      (fn [notification]
        (log/info "Foreground Push Received:" notification)))
    (.addListener PushNotifications "pushNotificationActionPerformed"
      (fn [action]
        (let [data    (.. action -notification -data)
              room-id (.-room_id data)]
          (when room-id
            (log/info "User tapped notification for room:" room-id)
            (re-frame/dispatch [:space/select-room room-id])))))))

(defn url-base64->uint8-array [base64-string]
  (let [padding (.repeat "=" (mod (- 4 (mod (.-length base64-string) 4)) 4))
        base64 (-> (.replace base64-string (js/RegExp. "-" "g") "+")
                   (.replace (js/RegExp. "_" "g") "/")
                   (str padding))
        raw-data (js/atob base64)
        output-array (js/Uint8Array. (.-length raw-data))]
    (dotimes [i (.-length raw-data)]
      (aset output-array i (.charCodeAt raw-data i)))
    output-array))

(defn- clean-url [base path]
  (str (str/replace base #"/+$" "")
       "/"
       (str/replace path #"^/+" "")))

(re-frame/reg-event-fx
 :push/register-pusher
 (fn [_ [_ subscription]]
   (if-let [pool @state/!engine-pool]
     (let [sub-json (.toJSON subscription)
           p256dh   (.. sub-json -keys -p256dh)
           auth     (.. sub-json -keys -auth)
           endpoint (.-endpoint sub-json)
           app-id   (:app-id @!config)
           push-url (:push-url @!config)]
       (cond
         (not app-id)   (log/error "Missing app-id!")
         (not push-url) (log/error "Missing push-url!")
         (not p256dh)   (log/error "Missing p256dh key from browser subscription!")
         (not auth)     (log/error "Missing auth key from browser subscription!")
         :else
         (go
           (let [res (<! (main/do-with-pool! pool
                                             {:handler :register-pusher
                                              :arguments {:p256dh   p256dh
                                                          :auth     auth
                                                          :endpoint endpoint
                                                          :app-id   app-id
                                                          :push-url push-url
                                                          :app-name (:app-name @!config)
                                                          :lang     (or js/navigator.language "en")}}))]
             (if (= (:status res) "success")
               (do
                 (log/info "Web Pusher registered successfully!")
                 (re-frame/dispatch [:push/set-status :enabled]))
               (log/error "HS Rejected Web Pusher:" (:msg res)))))))
     (log/error "Cannot register pusher: no engine pool"))
   {}))

(re-frame/reg-event-db
 :push/set-status
 (fn [db [_ status]]
   (assoc db :push-status status)))

(re-frame/reg-sub
 :push/status
 (fn [db _]
   (:push-status db :disabled)))

(re-frame/reg-event-fx
 :push/enable
 (fn [_ _]
   (if (.isNativePlatform Capacitor)
     (go
       (try
         (let [perm (<p! (.requestPermissions PushNotifications))]
           (if (= (.-receive perm) "granted")
             (.register PushNotifications)
             (log/warn "Native notification permission denied.")))
         (catch js/Error e
           (log/error "Native Push setup failed:" e))))
     (if-not (and (exists? js/window.PushManager) (exists? js/navigator.serviceWorker))
       (do (log/error "Push messaging is not supported.") {})
       (-> (p/let [permission (js/Notification.requestPermission)]
             (if (= permission "granted")
               (p/let [reg (.-ready js/navigator.serviceWorker)
                       existing-sub (.getSubscription (.-pushManager reg))
                       sub (if existing-sub
                             existing-sub
                             (.subscribe (.-pushManager reg)
                                         #js {:userVisibleOnly true
                                              :applicationServerKey (url-base64->uint8-array (:vapid-key @!config))}))]
                 (re-frame/dispatch [:push/register-pusher sub]))
               (log/warn "Notification permission denied.")))
           (p/catch #(log/error "Push setup failed:" %)))))
   {}))
(ns container.call.core
  (:require
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [container.call.call-container :refer [primary-iframe-ref backup-iframe-ref]]
   [container.call.call-view :as call-view]
   ["generated-compat" :as sdk]
   [taoensso.timbre :as log]
   [clojure.string :as str]))


(def capabilities-provider
  #js {:acquireCapabilities
       (fn [requested-caps]
         (try
           (let [db @rf-db/app-db
                 client (:client db)
                 user-id (.userId client)
                 device-id (or (some-> client .session .-deviceId) "paradise-web")]
             (log/debug "UniFFI is calling synchronously for permissions...")
             (sdk/getElementCallRequiredPermissions user-id device-id))
           (catch :default e
             (log/error "Capabilities provider crashed!" e)
             #js {:read #js [] :send #js []})))})

(rf/reg-event-fx
 :call/init-widget
 (fn [{:keys [db]} [_ room-id opts]]
   (let [client           (:client db)
         room-obj         (or (get-in db [:rooms :active-room-obj])
                              (when client (.getRoom client room-id)))

         current-primary? (get-in db [:call :primary-iframe?] true)
         new-primary?     (not current-primary?)
         target-iframe    (if new-primary? @primary-iframe-ref @backup-iframe-ref)
         join-directly?   (:join-directly? opts true)]

     (if-not room-obj
       (do (log/error "Cannot init widget: Room object is null") {})
       (let [is-encrypted? (if (fn? (.-isEncrypted room-obj))
                             (.isEncrypted room-obj)
                             (.-isEncrypted room-obj))
             encryption-sys (if is-encrypted?
                              (.-PerParticipantKeys sdk/EncryptionSystem)
                              (.-Unencrypted sdk/EncryptionSystem))
             intent-str     (if join-directly? "start_call" "join_existing")
             skip-lobby-str (if join-directly? "true" "false")
             local-url      (str (.. js/window -location -origin) "/element-call/index.html")
             props          #js {:elementCallUrl local-url
                                 :widgetId       (str "element-call-" room-id)
                                 :encryption     (new encryption-sys)}
             config         #js {:intent (if join-directly?
                                           (.-StartCall sdk/Intent)
                                           js/undefined)
                                 :hideHeader true}
             settings       (sdk/newVirtualElementCallWidget props config)
             driver-bundle  (sdk/makeWidgetDriver settings)
             driver         (.-driver driver-bundle)
             handle         (.-handle driver-bundle)]

         (-> (.run driver room-obj capabilities-provider)
             (.then #(log/info "Widget driver stopped."))
             (.catch #(log/error "Widget driver crashed:" %)))
         (let [raw-url        (.-rawUrl settings)
               client-user-id (.userId client)
               hs-url         (get-in db [:homeserver-url] nil)
               device-id      (or (some-> client .session .-deviceId) "paradise-web")
               actual-id      (str "element-call-" room-id)
               base           (str/replace raw-url #"\?.*$|\#.*$" "")
               widget-query   (str "?widgetId=" (js/encodeURIComponent actual-id)
                                   "&parentUrl=" (js/encodeURIComponent (.. js/window -location -origin))
                                   "&userId=" (js/encodeURIComponent client-user-id)
                                   "&deviceId=" (js/encodeURIComponent device-id)
                                   "&baseUrl=" (js/encodeURIComponent hs-url))
               app-fragment (str "#/?intent=" intent-str
                                 "&skipLobby=" skip-lobby-str
                                 "&roomId=" (js/encodeURIComponent room-id)
                                 "&perParticipantE2EE=" (if is-encrypted? "true" "false")
                                 "&theme=dark&lang=en")
               final-url      (str base widget-query app-fragment)]
           (when final-url
             (set! (.-src target-iframe) final-url))
           {:db (-> db
                    (assoc-in [:call :active-room-id] room-id)
                    (assoc-in [:call :widget-handle] handle)
                    (assoc-in [:call :widget-url] final-url)
                    (assoc-in [:call :primary-iframe?] new-primary?)
                    (assoc-in [:call :encrypted?] is-encrypted?))
            :dispatch [:call/start-native-message-pump room-id new-primary?]}))))))

(rf/reg-event-fx
 :call/start-native-message-pump
 (fn [{:keys [db]} [_ room-id is-primary?]]
   (let [handle (get-in db [:call :widget-handle])]
     (if-not handle
       (do (log/error "No widget handle found!") {})
       (do
         (letfn [(recv-loop []
                   (-> (.recv handle)
                       (.then (fn [msg-string]
                                (if (and msg-string (not= msg-string "null"))
                                  (do
                                    (when-let [iframe (if is-primary? @primary-iframe-ref @backup-iframe-ref)]
                                      (.postMessage (.-contentWindow iframe)
                                                    (js/JSON.parse msg-string)
                                                    "*"))
                                    (js/setTimeout recv-loop 0))
                                  (js/setTimeout recv-loop 50))))
                       (.catch #(log/error "Recv loop crashed:" %))))]
           (recv-loop))
         (.addEventListener js/window "message"
                            (fn [event]
                              (when (= (.-origin event) (.. js/window -location -origin))
                                (let [raw-data   (.-data event)
                                      action     (str (.-action raw-data))
                                      req-id     (.-requestId raw-data)
                                      widget-id  (.-widgetId raw-data)
                                      msg-string (js/JSON.stringify raw-data)]
                                  (cond
                                    ;; --- NEW: Sync Media State ---
                                    (= action "io.element.device_mute")
                                    (let [d (.-data raw-data)]
                                      (rf/dispatch [:call/update-media-state
                                                    {:audio (.-audio_enabled d)
                                                     :video (.-video_enabled d)}]))

                                    (str/starts-with? action "io.element.")
                                    (do
                                      (log/debug "Manually ACKing custom action:" action)
                                      (when-let [iframe (if is-primary? @primary-iframe-ref @backup-iframe-ref)]
                                        (let [ack #js {:api       "toWidget"
                                                       :action    action
                                                       :requestId req-id
                                                       :widgetId  widget-id
                                                       :response  #js {}
                                                       :data      #js {}}]
                                          (.postMessage (.-contentWindow iframe) ack "*")))
                                      (-> (.send handle msg-string)
                                          (.catch #(log/error "Rust rejected custom action:" %))))

                                    :else
                                    (-> (.send handle msg-string)
                                        (.catch #(log/error "Rust rejected standard message:" %))))))))
         {})))))
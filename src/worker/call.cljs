(ns worker.call
  (:require [cljs-workers.worker :as worker]
            [taoensso.timbre :as log]
            [worker.state :as state]
            ["ffi-bindings" :as sdk])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs.core.async.interop :refer [<p!]]))

(defonce !widget-handle (atom nil))

(def capabilities-provider
  #js {:acquireCapabilities
       (fn [_requested-caps]
         (try
           (let [client    @state/!client
                 user-id   (.userId client)
                 device-id (or (some-> client .session .-deviceId) "paradise-web")]
             (log/debug "Worker: Acquiring capabilities for widget...")
             (sdk/getElementCallRequiredPermissions user-id device-id))
           (catch :default e
             (log/error "Capabilities provider crashed!" e)
             #js {:read #js [] :send #js []})))})


(worker/register :init-call-widget
  (fn [{:keys [room-id join-directly? local-url]}]
    (go
      (try
        (if-let [room (.getRoom @state/!client room-id)]
          (let [is-encrypted?  (if (fn? (.-isEncrypted room))
                                 (<p! (.isEncrypted room))
                                 (.-isEncrypted room))
                encryption-sys (if is-encrypted?
                                 (.-PerParticipantKeys sdk/EncryptionSystem)
                                 (.-Unencrypted sdk/EncryptionSystem))
                props          #js {:elementCallUrl local-url
                                    :widgetId       (str "element-call-" room-id)
                                    :encryption     (new encryption-sys)}
                config         #js {:intent     (if join-directly? (.-StartCall sdk/Intent) js/undefined)
                                    :hideHeader true}
                settings       (sdk/newVirtualElementCallWidget props config)
                driver-bundle  (sdk/makeWidgetDriver settings)
                driver         (.-driver driver-bundle)
                handle         (.-handle driver-bundle)
                recv-loop      (fn loop-fn []
                                 (when @!widget-handle
                                   (-> (.recv handle)
                                       (.then (fn [msg-string]
                                                (when (and msg-string (not= msg-string "null"))
                                                  (worker/stream! {:type "widget-message" :data msg-string}))
                                                (js/setTimeout loop-fn 10)))
                                       (.catch #(log/error "Recv loop crashed:" %)))))]

            (-> (.run driver room capabilities-provider)
                (.then #(log/info "Worker: Widget driver stopped."))
                (.catch #(log/error "Worker: Widget driver crashed:" %)))

            (reset! !widget-handle handle)
            (recv-loop)

            {:status        :success
             :raw-url       (.-rawUrl settings)
             :is-encrypted? is-encrypted?
             :user-id       (.userId @state/!client)
             :device-id     (or (some-> @state/!client .session .-deviceId) "paradise-web")})
          {:status :error :msg "Room not found for call"})
        (catch :default e
          {:status :error :msg (str e)})))))

(worker/register :send-widget-message
  (fn [{:keys [msg-string]}]
    (go
      (if-let [handle @!widget-handle]
        (try
          (<p! (.send handle msg-string))
          {:status :success}
          (catch :default e
            {:status :error :msg (str e)}))
        {:status :error :msg "No widget handle active in worker"}))))

(worker/register :teardown-widget
  (fn [_]
    (reset! !widget-handle nil)
    {:status :success}))
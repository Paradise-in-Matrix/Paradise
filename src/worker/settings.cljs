(ns worker.settings
  (:require
   [cljs-workers.worker :as worker]
   [client.session-store :refer [SessionStore]]
   [worker.state :as state]
   [utils.net :refer [set-auth-context!] :as net]
   [promesa.core :as p]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   ["ffi-bindings" :as sdk]
   [cljs.core.async.interop :refer-macros [<p!]]
   [cljs.core.async :refer [go]]
   )
  )

(worker/register :recover-session
                 (fn [{:keys [recovery-key]}]
                   (go
                     (try
                       (if-let [client @state/!client]
                         (let [encryption-module (.encryption client)]
                           (log/info "Attempting session recovery with key length:" (count recovery-key))
                           (<p! (.recover encryption-module recovery-key))
                           (log/info "Session recovery successful!")
                           {:status :success})
                         {:status :error :msg "No active client"})
                       (catch :default e
                         (let [err-str (or (.-message e) (str e))]
                           (log/error "Recovery FFI Panic:" err-str)
                           {:status :error :msg err-str}))))))

(defonce !recovery-listener-handle (atom nil))

(defn setup-encryption-listeners! [client]
  (let [encryption-module (.encryption client)
        listener-obj #js {:onUpdate
                          (fn [state-enum]
                            (let [state-kw (cond
                                             (or (= state-enum 1) (= state-enum "Enabled"))    :enabled
                                             (or (= state-enum 2) (= state-enum "Disabled"))   :disabled
                                             (or (= state-enum 3) (= state-enum "Incomplete")) :incomplete
                                             :else :unknown)]
                              (log/info "Worker stream: Recovery state changed to:" state-enum "->" state-kw)
                              (worker/stream! {:type "recovery-state-update"
                                               :state state-kw})))}
        handle (.recoveryStateListener encryption-module listener-obj)]
    (reset! !recovery-listener-handle handle)))

(worker/register :get-available-accounts
                 (fn [_]
                   (go
                     (try
                       (<p! (p/let [store (SessionStore.)
                                    sessions (.loadSessions store)]
                              (let [accs (for [uid (js/Object.keys sessions)
                                               :let [data (aget sessions uid)
                                                     session (.-session data)]]
                                           {:userId uid
                                            :hs_url (.-homeserverUrl session)})]
                                {:status :success :accounts (vec accs)})))
                       (catch :default e
                         {:status :error :msg (str e)})))))

(worker/register :get-account-data
                 (fn [{:keys [event-type]}]
                   (go
                     (try
                       (if-let [client @state/!client]
                         (let [data (<p! (.accountData client event-type))]
                           {:status :success :data data})
                         {:status :error :msg "No active client"})
                       (catch :default e
                         {:status :error :msg (str e)})))))

(worker/register :register-pusher
  (fn [{:keys [p256dh auth endpoint app-id push-url app-name lang pushkey is-native platform]}]
    (go
      (try
        (let [client     @state/!client
              hs-url     (.homeserver client)
              token      (.-accessToken (.session client))
              clean-base (str/replace hs-url #"/+$" "")
              url        (str clean-base "/_matrix/client/v3/pushers/set")
              payload    (cond
                           (and is-native (= platform "ios"))
                           {:kind "http"
                            :app_id app-id
                            :pushkey pushkey
                            :app_display_name app-name
                            :device_display_name "iOS Device"
                            :lang lang
                            :append false
                            :data {:url push-url
                                   :format "event_id_only"
                                   :default_payload {:aps {:alert {:loc-key "New Message"}
                                                           :sound "default"
                                                           :badge 1
                                                           :content-available 1}}}}
                           (and is-native (= platform "android"))
                           {:kind "http"
                            :app_id app-id
                            :pushkey pushkey
                            :app_display_name app-name
                            :device_display_name "Android Device"
                            :lang lang
                            :append false
                            :data {:url push-url
                                   :format "event_id_only"
                                   }}
                           :else
                           {:kind "http"
                            :app_id app-id
                            :pushkey p256dh
                            :app_display_name app-name
                            :device_display_name "Web Browser"
                            :lang lang
                            :append false
                            :data {:url push-url
                                   :events_only true
                                   :endpoint endpoint
                                   :p256dh p256dh
                                   :auth auth}})
              resp       (<p! (net/fetch url #js {:method "POST"
                                                  :headers #js {"Authorization" (str "Bearer " token)
                                                                "Content-Type" "application/json"}
                                                  :body (js/JSON.stringify (clj->js payload))}))]
          (if (.-ok resp)
            {:status "success"}
            (let [err-body (<p! (.json resp))]
              {:status "error" :msg err-body})))
        (catch :default e
          {:status "error" :msg (str e)})))))

(worker/register :remove-pusher
  (fn [{:keys [pushkey app-id]}]
    (go
      (try
        (let [client      @state/!client
              identifiers #js {:pushkey pushkey :appId app-id}]
          (<p! (.deletePusher client identifiers))
          {:status "success"})
        (catch :default e
          {:status "error" :msg (str e)})))))


(worker/register :clear-all-pushers
  (fn [_]
    (go
      (try
        (let [client     @state/!client
              hs-url     (.homeserver client)
              token      (.-accessToken (.session client))
              clean-base (str/replace hs-url #"/+$" "")
              url        (str clean-base "/_matrix/client/v3/pushers")
              resp       (<p! (net/fetch url #js {:method "GET"
                                                  :headers #js {"Authorization" (str "Bearer " token)}}))]
          (if-not (.-ok resp)
            (let [err-body (<p! (.json resp))]
              {:status "error" :msg err-body})
            (let [data    (<p! (.json resp))
                  pushers (:pushers (js->clj data :keywordize-keys true))]
              (doseq [p pushers]
                (let [pushkey (:pushkey p)
                      app-id  (:app_id p)
                      identifiers #js {:pushkey pushkey :appId app-id}]
                  (try
                    (<p! (.deletePusher client identifiers))
                    (catch :default e
                      (log/warn "Failed to delete pusher" pushkey ":" e)))))
              {:status "success"})))
        (catch :default e
          {:status "error" :msg (str e)})))))

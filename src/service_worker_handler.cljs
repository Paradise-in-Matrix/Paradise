(ns service-worker-handler
  (:require
   [taoensso.timbre :as log]
   [client.config :refer [check-remote-version]]
   [re-frame.db :as db]
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [cljs-workers.core :as main]
   [client.state :as state]
   [cljs.core.async :refer [go <!]]))

(defn register-sw! []
  (when (exists? js/navigator.serviceWorker)
    (let [sw-container js/navigator.serviceWorker
          try-sync!    (fn []
                         (when (.-controller sw-container)
                           (re-frame/dispatch [:auth/sync-sw-with-worker])))]

      (-> (.register sw-container "/sw.js" #js {:type "module"})
          (.then (fn [reg]
                   (when (.-waiting reg)
                     (.postMessage (.-waiting reg) #js {:type "SKIP_WAITING"})))))
      (try-sync!)
      (.addEventListener sw-container "controllerchange"
                         (fn []
                           (log/debug "re-frame: SW Controller changed, checking version...")
                           (try-sync!)
                           (-> (check-remote-version)
                               (p/then (fn [remote-v]
                                         (let [db @db/app-db
                                               current-v (get-in db [:config :version] "0.0.0")]
                                           (when (and remote-v
                                                      (not= (str remote-v) (str current-v))
                                                      (not= (str current-v) "0.0.0"))
                                             (log/info "SW Update: Version mismatch detected" remote-v "vs" current-v)
                                             (re-frame/dispatch [:app/update-detected])))))
                               (p/catch (fn [err]
                                          (log/error "SW Update: Failed to check version" err))))))
      (.addEventListener sw-container "message"
                         (fn [event]
                           (let [data (.-data event)]
                             (when (= (.-type data) "NAVIGATE_TO_ROOM")
                               (let [room-id (.-room_id data)]
                                 (log/debug "SW requested navigation to room:" room-id)
                                 (re-frame/dispatch [:rooms/select room-id])))))))))


(re-frame/reg-event-fx
 :auth/sync-sw-with-worker
 (fn [{:keys [db]} _]
   (if-let [pool @state/!engine-pool]
     (go
       (let [res (<! (main/do-with-pool! pool {:handler :get-client-context}))]
         (when (= (:status res) "success")
           (re-frame/dispatch [:auth/sync-sw-only (:session res)]))))
     (log/warn "Engine pool not ready for SW sync"))
   {}))

(re-frame/reg-event-fx
 :auth/sync-sw-only
 (fn [_ [_ session]]
   {:sync-sw-auth session}))

(re-frame/reg-fx
 :sync-sw-auth
 (fn [session]
   (if-let [sw-controller (.-controller js/navigator.serviceWorker)]
     (try
       (let [origin (.-origin (js/URL. (:homeserverUrl session)))
             payload (assoc session :origin origin)]
         (.postMessage sw-controller #js {:type "SET_SESSION"
                                          :session (clj->js payload)})
         (js/console.log "re-frame: Session synced for" (:userId session) "at" origin))
       (catch js/Error e
         (js/console.error "re-frame: Failed to parse homeserver URL" e)))
     (js/console.warn "re-frame: SW controller not found"))))
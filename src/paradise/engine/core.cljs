(ns paradise.engine.core
  (:require
   [cljs-workers.worker :as worker]
   [promesa.core :as p]
   [taoensso.timbre :as log]
   [paradise.shared.client.session-store :as session-store :refer [SessionStore]]
   ["ffi-bindings" :as sdk]
   [cljs.core.async.interop :refer-macros [<p!]]
   [cljs.core.async :refer [go]]
   [net :refer [set-auth-context!] :as net]
#_   [eve.alpha :as eve]
   #_ [eve.atom :as ea]
   #_ [eve.mem :as mem]
   #_ [re-frame.db :as db]
   #_ [eve.wasm-mem :as wasm-mem]
 #_  [eve.deftype-proto.alloc :as alloc]
   [paradise.engine.state :as state]
   [paradise.engine.spaces :as spaces]
   [paradise.engine.settings :as settings :refer [setup-encryption-listeners!]]
   [paradise.engine.timeline]
#_   [worker.media]
   [paradise.engine.members]
   [paradise.engine.composer]
   [paradise.engine.media-previews :as previews]
   [paradise.engine.call]
   [paradise.engine.rooms :as rooms]
   [paradise.shared.sci-runner.engine :as sci]))


#_(worker/register :bind-app-db
  (fn [{:keys [eve-payload]}]
    (let [root-sab      (:root-sab eve-payload)
          rmap-sab      (:rmap-sab eve-payload)
          slab-sabs     (:slab-sabs eve-payload)
          atom-slot-idx (:atom-slot-idx eve-payload)]
      (alloc/init-worker-slabs! slab-sabs root-sab nil)

      (let [root-r       (mem/js-sab-region root-sab)
            rmap-r       (mem/js-sab-region rmap-sab)
            slot-idx     (ea/register-worker! {:root-r root-r} 2)
            domain-state {:root-r root-r :rmap-r rmap-r :base-path nil
                          :slot-idx slot-idx :retire-q (atom [])
                          :flush-ts (doto (make-array 1) (aset 0 0))}
            eve-atom     (ea/->MmapAtom domain-state atom-slot-idx)]

        (db/set-eve-atom! eve-atom)
        (js/setInterval
          #(js/console.log "Worker Interval DB Keys:" (pr-str (keys @eve-atom)))
          3000)
        (js/setInterval #(ea/update-heartbeat! domain-state slot-idx) 5000)
        {:status :db-bound}))))


(worker/register :init-wasm
                 (fn [_]
                   (go
                     (try
                       (<p! (sdk/uniffiInitAsync))
                       {:status :success}
                       (catch :default e
                         {:status :error :msg (str e)})))))

(defn maybe-local-session []
  (p/let [store (SessionStore.)
          sessions (.loadSessions store)
          user-id (first (js/Object.keys sessions))]
    (when user-id (aget sessions user-id))))

(defn get-specific-session [target-user-id]
  (p/let [store (SessionStore.)
          sessions (.loadSessions store)]
    (aget sessions target-user-id)))


(defn build-client [hs passphrase? store-id? restore-or-login!]
  (p/let [sdk-root (if (.-ClientBuilder sdk) sdk (.-default sdk))
          ClientBuilder (.-ClientBuilder sdk-root)
          SSVBuilder    (.-SlidingSyncVersionBuilder sdk-root)
          IDBBuilder    (.-IndexedDbStoreBuilder sdk-root)
          store (SessionStore.)
          store-id   (or store-id? (.generateStoreId store))
          passphrase (or passphrase? (.generatePassphrase store))
          store-name (.getStoreName store store-id)
          store-config (-> (new IDBBuilder store-name)
                           (.passphrase passphrase))
          builder (-> (new ClientBuilder)
                      (.serverNameOrHomeserverUrl hs)
                      (.indexeddbStore store-config)
                      (.autoEnableCrossSigning true)
                      (cond-> (nil? passphrase?)
                        (.slidingSyncVersionBuilder (.-DiscoverNative SSVBuilder))))
          client  (.build builder)
          _ (restore-or-login! client)
          session (.session client)
          _ (or passphrase? (.save store session passphrase store-id))]
     client))

(worker/register :bootstrap
  (fn [{:keys [target-user-id]}]
    (go
      (try
        (<p! (p/let [data? (if target-user-id
                             (get-specific-session target-user-id)
                             (maybe-local-session))]
               (if data?
                 (p/let [session (.-session data?)
                         hs-url  (.-homeserverUrl session)
                         uid     (.-userId session)
                         token   (.-accessToken session)
                         dev-id  (.-deviceId session)
                         client  (build-client hs-url
                                               (.-passphrase data?)
                                               (.-storeId data?)
                                               #(.restoreSession % session))]
                   (reset! state/!media-cache nil)
                   (reset! state/!client client)
                   (set-auth-context! token hs-url)
                   {:status :success
                    :user-id uid
                    :hs-url hs-url
                    :session-data {:accessToken token
                                   :homeserverUrl hs-url
                                   :userId uid
                                   :deviceId dev-id}})
                 {:status :empty})))
        (catch :default e
          {:status :error :msg (str e)})))))


(worker/register :login
                 (fn [{:keys [hs user pass]}]
                   (go
                     (try
                       (<p! (p/let [client  (build-client hs nil nil #(.login % user pass))
                                    session (.session client)
                                    uid     (.-userId session)
                                    hs-url  (.-homeserverUrl session)
                                    token   (.-accessToken session)
                                    dev-id  (.-deviceId session)]
                              (reset! state/!client client)
                              (reset! state/!media-cache nil)
                              {:status :success
                               :user-id uid
                               :hs-url hs-url
                               :session-data {:accessToken token
                                              :homeserverUrl hs-url
                                              :userId uid
                                              :deviceId dev-id}}))
                       (catch :default e
                         {:status :error :msg (str e)})))))


(worker/register :start-sync
                 (fn [_]
                   (go
                     (try
                       (<p! (p/let [client       @state/!client
                                    sync-service (-> (.syncService client) (.withOfflineMode) (.finish))
                                    rls-instance (.roomListService sync-service)
                                    room-list    (.allRooms rls-instance)
                                    space-service (.spaceService client)]
                              (rooms/start-room-list-sync! client room-list space-service)
                              (.start sync-service)
                              (spaces/init-space-service! client)
                              (setup-encryption-listeners! client)
                              (previews/attach-media-preview-listener! client)
                              ))
                       {:status :success}
                       (catch :default e
                         {:status :error :msg (str e)})))))

(worker/register :fetch-profile
                 (fn [_]
                   (go
                     (try
                       (let [client  @state/!client
                             user-id (.-userId (.session client))
                             profile (<p! (.getProfile client user-id))]
                         {:status :success
                          :profile {:user-id user-id
                                    :display-name (.-displayName profile)
                                    :avatar-url   (.-avatarUrl profile)}})
                       (catch :default e
                         {:status :error :msg (str e)})))))

(worker/register :get-client-context
                 (fn [_]
                   (go
                     (try
                       (if-let [client @state/!client]
                         (let [session (.session client)]
                           {:status :success
                            :session {:userId        (.-userId session)
                                      :deviceId      (.-deviceId session)
                                      :accessToken   (.-accessToken session)
                                      :homeserverUrl (.-homeserverUrl session)}})
                         {:status :error :msg "No active client"})
                       (catch :default e
                         {:status :error :msg (str e)})))))

(worker/register :logout-client
  (fn [{:keys [user-id]}]
    (go
      (try
        (when-let [client @state/!client]
          (<p! (.logout client))
          (when (fn? (.-free client))
            (.free client))
          (reset! state/!client nil))

        (<p! (p/delay 1500))

        (when user-id
          (let [store (session-store/->SessionStore)]
            (<p! (.clear store user-id))
            (log/info "Worker successfully wiped OPFS session for:" user-id)))

        {:status "success"}
        (catch :default e
          (log/error "Worker Logout Panic:" e)
          {:status "error" :msg (str e)})))))



(worker/register :evaluate-worker-form
  (fn [{:keys [plugin-id code]}]
      (sci/evaluate-worker-form plugin-id code)))

(worker/bootstrap)

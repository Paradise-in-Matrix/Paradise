(ns client.login
  (:require
   [taoensso.timbre :as log]
   [reagent.core :as r]
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [client.state :refer [sdk-world]]
   [client.session-store :refer [SessionStore]]
   [navigation.spaces.bar :refer [init-space-service!]]
   [navigation.rooms.room-list :as rl];;:refer [parse-room apply-diffs! create-room-update-listener setup-room-list-adapter!]]
   ["ffi-bindings" :as sdk :refer [RoomListEntriesDynamicFilterKind]]
   )
  (:require-macros [utils.macros :refer [ocall oget]]))

(defonce sdk-ready? (r/atom false))

(defn init-sdk! []
    (-> (p/let [_ (sdk/uniffiInitAsync)]
          (log/debug "WASM loaded")
          (reset! sdk-ready? true)
          (swap! sdk-world assoc :loading? false))
        (p/catch (fn [e]
                   (log/error "WASM Load Failed:" e)
                   (swap! sdk-world assoc :loading? false)))))


(defn start-sync! [client]
  (p/let [sync-service (-> (.syncService client) (.withOfflineMode) (.finish))
          rls-instance (.roomListService sync-service)
          room-list (.allRooms rls-instance)
          _ (rl/start-room-list-sync! room-list)
          _ (.start sync-service)
         ;; cached-rooms (try (.rooms client) (catch :default _ []))
        ;;  top-rooms    (take 5 cached-rooms)
            _ (init-space-service! client)
          ]
    #_(doseq [room top-rooms]
      (let [room-id (or (try (.roomId room) (catch :default _ nil))
                        (try (.id room) (catch :default _ nil)))]
        (when room-id
          (log/debug "HIT")
          (re-frame/dispatch [:sdk/preload-timeline-safe room-id]))))
    ))

(defn build-client [hs passphrase? store-id? restore-or-login!]
  (-> (p/let [
              ;; Rust internal components
              sdk-root (if (.-ClientBuilder sdk) sdk (.-default sdk))
              ClientBuilder (.-ClientBuilder sdk-root)
              SSVBuilder    (.-SlidingSyncVersionBuilder sdk-root)
              IDBBuilder    (.-IndexedDbStoreBuilder sdk-root)
              ;;
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
              _ (or passphrase? (.save store session passphrase store-id))
              ]
        client)
      (p/catch (fn [e]
                 (log/error  e)
                 (log/warn "Login failed, returning nil")
                 nil))))

(defn login! [hs user pass]
   (p/let [client (build-client hs nil nil #(.login % user pass))]
           client))

(defn restore-client! [session passphrase store-id]
  (p/let [client (build-client (.-homeserverUrl session) passphrase store-id #(.restoreSession % session))]
    client))

(defn maybe-local-session []
  (p/let [store (SessionStore.)
          sessions (.loadSessions store)
          user-id (first (js/Object.keys sessions))]
    (or (aget sessions user-id) nil)))


(defn get-specific-session [target-user-id]
  (p/let [store    (SessionStore.)
          sessions (.loadSessions store)]
    (or (aget sessions target-user-id) nil)))

(defn bootstrap! 
  ([on-complete]
   (bootstrap! nil on-complete))
  ([target-user-id on-complete]
   (-> (p/let [_      (init-sdk!)
               data?  (if target-user-id
                        (get-specific-session target-user-id)
                        (maybe-local-session))
               client (when data?
                        (restore-client! (.-session data?)
                                         (.-passphrase data?)
                                         (.-storeId data?)))]
         (log/debug "Restored session for:" (if data? (.. data? -session -userId) "None"))
         (on-complete client data?))
       (p/catch (fn [e]
                  (log/error "Bootstrap/Restore failed:" e)
                  (on-complete nil))))))

(ns client.session-store
  (:require [promesa.core :as p]
            [taoensso.timbre :as log]
            ["ffi-bindings" :as sdk]))

(def ^:private file-name "mx_session_v3.json")

(defn- get-session-class []
  (let [sdk-root (if (.-Session sdk) sdk (.-default sdk))]
    (if sdk-root
      (.-Session sdk-root)
      (log/error "FATAL: Could not find Session class on root SDK object."))))

(defn- load-raw-sessions-opfs []
  (p/let [dir (.. js/navigator -storage getDirectory)
          file-handle (.getFileHandle dir file-name #js {:create true})
          sync-handle (.createSyncAccessHandle file-handle)]
    (try
      (let [size (.getSize sync-handle)]
        (if (zero? size)
          #js {}
          (let [buffer (js/Uint8Array. size)]
            (.read sync-handle buffer #js {:at 0})
            (let [decoder (js/TextDecoder.)
                  text (.decode decoder buffer)]
              (.parse js/JSON text)))))
      (finally
        (.close sync-handle)))))

(defn- save-raw-sessions-opfs! [sessions-obj]
  (p/let [dir (.. js/navigator -storage getDirectory)
          file-handle (.getFileHandle dir file-name #js {:create true})
          sync-handle (.createSyncAccessHandle file-handle)]
    (try
      (let [encoder (js/TextEncoder.)
            text (.stringify js/JSON sessions-obj)
            buffer (.encode encoder text)]
        (.truncate sync-handle 0)
        (.write sync-handle buffer #js {:at 0})
        (.flush sync-handle))
      (finally
        (.close sync-handle)))))

(defn generate-passphrase []
  (let [array (js/Uint8Array. 32)]
    (.getRandomValues js/crypto array)
    (js/btoa (.apply js/String.fromCharCode nil array))))

(defn- generate-uuid []
  (if (and (exists? js/crypto) (exists? (.-randomUUID js/crypto)))
    (.randomUUID js/crypto)
    (let [template "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"]
      (.replace template
                (js/RegExp. "[xy]" "g")
                (fn [c]
                  (let [r (bit-or (* (js/Math.random) 16) 0)
                        v (if (= c "x") r (bit-or (bit-and r 0x3) 0x8))]
                    (.toString v 16)))))))

(defn- delete-store-impl! [store-id]
  (let [store-name (str "paradise-store-" store-id)]
    (js/Promise.
     (fn [resolve _]
       (let [request (.deleteDatabase js/indexedDB store-name)]
         (set! (.-onsuccess request) #(resolve true))
         (set! (.-onerror request) #(resolve false))
         (set! (.-onblocked request) #(resolve false)))))))

#_(defn- sync-to-sw-vault! [sessions-obj]
  (let [user-keys (js/Object.keys sessions-obj)]
    (when (pos? (.-length user-keys))
      (let [request (.open js/indexedDB "sw-vault" 1)]
        (set! (.-onupgradeneeded request)
              (fn [e]
                (let [db (.. e -target -result)]
                  (.createObjectStore db "tokens" #js {:keyPath "userId"}))))
        (set! (.-onsuccess request)
              (fn [e]
                (let [db (.. e -target -result)
                      tx (.transaction db #js ["tokens"] "readwrite")
                      store (.objectStore tx "tokens")]
                  (.clear store)
                  (doseq [user-id user-keys]
                    (let [data (aget sessions-obj user-id)
                          session (aget data "session")
                          token (aget session "accessToken")
                          device-id (aget session "deviceId")
                          hs-url (aget session "homeserverUrl")]
                      (.put store #js {:userId    user-id
                                       :token      token
                                       :deviceId  device-id
                                       :hsUrl     hs-url
                                       :updatedAt (js/Date.now)}))))))))))




(defn- load-sessions-impl []
  (p/let [sessions (load-raw-sessions-opfs)
          Session (get-session-class)]
    (when Session
      (doseq [user-id (js/Object.keys sessions)]
        (let [data (aget sessions user-id)]
          (aset sessions user-id
                #js {:session (.new Session (aget data "session"))
                     :passphrase (aget data "passphrase")
                     :storeId (aget data "storeId")}))))
    sessions))

(defn- save-session-impl! [session passphrase store-id]
  (p/let [user-id (.-userId session)
          sessions (load-raw-sessions-opfs)
          existing (aget sessions user-id)
          final-pass (or passphrase (when existing (aget existing "passphrase")))
          final-id   (or store-id   (when existing (aget existing "storeId")))]
    (when (and final-pass final-id)
      (aset sessions user-id #js {:session session
                                  :passphrase final-pass
                                  :storeId final-id})
      (save-raw-sessions-opfs! sessions))))

#_(defn- clear-sw-vault-user! [user-id]
  (let [request (.open js/indexedDB "sw-vault" 1)]
    (set! (.-onsuccess request)
          (fn [e]
            (let [db (.. e -target -result)
                  tx (.transaction db #js ["tokens"] "readwrite")
                  store (.objectStore tx "tokens")]
              (.delete store user-id))))))

(defn- clear-session-impl! [user-id]
  (p/let [sessions (load-raw-sessions-opfs)
          data (aget sessions user-id)]
    (when-let [sid (and data (aget data "storeId"))]
      (delete-store-impl! sid))
    (js-delete sessions user-id)
    (save-raw-sessions-opfs! sessions)))

(deftype SessionStore []
  Object
  (loadSessions [this]
    (load-sessions-impl))

  (save [this session passphrase store-id]
    (save-session-impl! session passphrase store-id))

  (generatePassphrase [this]
    (generate-passphrase))

  (generateStoreId [this]
    (generate-uuid))

  (getStoreName [this store-id]
    (str "paradise-store-" store-id))

  (clear [this user-id]
    (clear-session-impl! user-id)))

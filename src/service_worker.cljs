(ns service-worker
  (:require [clojure.string :as str]
            [promesa.core :as p]
            ["workbox-precaching" :refer [precacheAndRoute cleanupOutdatedCaches]]))

(defonce active-sessions (atom {}))
(defonce session-resolvers (atom []))
(def MAX_CACHE_ITEMS 50)
(def cache-name "matrix-media-v1")
(let [manifest (or js/self.__WB_MANIFEST #js [])]
  (precacheAndRoute manifest))

(.addEventListener js/self "message"
  (fn [event]
    (let [data (.-data event)
          source-id (.. event -source -id)]
      (when (= (.-type data) "SET_SESSION")
        (let [session-data (:session (js->clj data :keywordize-keys true))
              full-session (assoc session-data :client-id source-id)]
          (swap! active-sessions assoc source-id full-session)
          (js/console.log "SW: Session synced for" source-id)
          (doseq [resolve-fn @session-resolvers]
            (resolve-fn true))
          (reset! session-resolvers []))))))

(defn wait-for-session! []
  (if (not-empty @active-sessions)
    (p/resolved true)
    (js/Promise. (fn [resolve _]
                   (swap! session-resolvers conj resolve)))))

(defn- get-token-from-vault [user-id]
  (p/create
   (fn [resolve _]
     (let [req (.open js/indexedDB "sw-vault" 1)]
       (set! (.-onsuccess req)
             (fn [e]
                 (let [db (.. e -target -result)
                       tx (try (.transaction db #js ["tokens"] "readonly") (catch :default _ nil))
                       store (when tx (.objectStore tx "tokens"))
                       get-req (if (and store user-id)
                                 (.get store user-id)
                                 (when store (.getAll store)))]
                   (if-not get-req
                     (resolve nil)
                     (set! (.-onsuccess get-req)
                           (fn [res]
                             (let [result (.. res -target -result)]
                               (if (array? result)
                                 (resolve (some-> (first result) .-token))
                                 (resolve (some-> result .-token))))))))))
       (set! (.-onerror req) #(resolve nil))))))

(defn prune-cache! [name max-items]
  (p/let [cache (js/caches.open name)
          keys  (.keys cache)]
    (when (> (.-length keys) max-items)
      (p/let [_ (.delete cache (first keys))]
        (prune-cache! name max-items)))))

(js/self.addEventListener "fetch"
  (fn [event]
    (let [request      (.-request event)
          method       (.-method request)
          request-url  (js/URL. (.-url request))
          path         (.-pathname request-url)
          auth-path?   (str/includes? path "/_matrix/client/v1/media/")
          legacy-path? (or (str/includes? path "/_matrix/media/v3/")
                           (str/includes? path "/_matrix/media/v1/"))]
      (when (= method "GET")
        (cond
          auth-path?
          (.respondWith event
            (p/let [cache  (js/caches.open cache-name)
                    cached (.match cache request)]
              (if cached
                cached
                (p/let [_       (wait-for-session!)
                        session (or (get @active-sessions (.-clientId event))
                                    (first (vals @active-sessions)))
                        token   (or (:accessToken session) (:token session))
                        hs-url  (or (:homeserverUrl session) (:hsUrl session) (:hs_url session))]
                  (if (or (not token)
                          (not hs-url)
                          (not= (.-origin request-url) (.-origin (js/URL. hs-url))))
                    (js/Response.error)
                    (let [headers (js/Headers. (.-headers request))]
                      (.set headers "Authorization" (str "Bearer " token))
                      (p/let [resp (js/fetch request-url #js {:headers headers
                                                              :mode "cors"
                                                              :credentials "omit"})]
                        (when (and (.-ok resp) (< (js/parseInt (.get (.-headers resp) "content-length") 10) 10485760))
                          (.put cache request (.clone resp)))
                        resp)))))))

          legacy-path?
          (.respondWith event
            (p/let [cache  (js/caches.open cache-name)
                    cached (.match cache request)]
              (if cached
                cached
                (p/let [resp (js/fetch request-url #js {:mode "cors"
                                                        :credentials "omit"})]
                  (when (and (.-ok resp) (< (js/parseInt (.get (.-headers resp) "content-length") 10) 10485760))
                    (.put cache request (.clone resp)))
                  resp))))
          :else nil)))))



(js/self.addEventListener "install"
  (fn [event]
    (.skipWaiting js/self)))

(js/self.addEventListener "activate"
  (fn [event]
    (.waitUntil event
      (p/all [(.clients.claim js/self)
              (prune-cache! cache-name MAX_CACHE_ITEMS)]))))

(def default-icon "/public/res/apple/apple-touch-icon-180x180.png")
(def default-badge "/public/res/apple/apple-touch-icon-72x72.png")

#_(defn- get-session-from-vault [user-id]
  (p/create
   (fn [resolve reject]
     (let [req (.open js/indexedDB "sw-vault" 1)]
       (set! (.-onsuccess req)
             (fn [e]
               (let [db    (.. e -target -result)
                     tx    (.transaction db #js ["tokens"] "readonly")
                     store (.objectStore tx "tokens")]
                 (if user-id
                   (let [get-req (.get store user-id)]
                     (set! (.-onsuccess get-req) #(resolve (.. % -target -result)))
                     (set! (.-onerror get-req) #(resolve nil)))
                   (let [all-req (.getAll store)]
                     (set! (.-onsuccess all-req)
                           (fn [ae]
                             (let [results (vec (.. ae -target -result))]
                               (if (empty? results)
                                 (resolve nil)
                                 (resolve (->> results
                                               (sort-by #(.-updated_at %) >)
                                               first)))))))))))))))

(defn- get-session-from-opfs [user-id]
  (p/let [dir         (.. js/navigator -storage getDirectory)
          file-handle (p/catch (.getFileHandle dir "mx_session_v3.json") (fn [_] nil))]
    (if-not file-handle
      nil
      (p/let [file     (.getFile file-handle)
              text     (.text file)
              sessions (try (.parse js/JSON text) (catch :default _ #js {}))
              keys     (js/Object.keys sessions)
              target-id (or user-id (when (pos? (.-length keys)) (aget keys 0)))]

        (if target-id
          (let [data (aget sessions target-id)]
            (if data
              (let [session (aget data "session")]
                #js {:token  (aget session "accessToken")
                     :hs_url (aget session "homeserverUrl")})
              nil))
          nil)))))

(defn- fetch-avatar-url [hs-url token mxc-uri]
  (if (and hs-url token mxc-uri)
    (let [media-id (clojure.string/replace mxc-uri "mxc://" "")
          thumb-url (str hs-url "/_matrix/media/v3/thumbnail/" media-id
                         "?width=96&height=96&method=crop")]
      (-> (js/fetch thumb-url #js {:headers #js {:Authorization (str "Bearer " token)}
                                   :mode "cors"
                                   :credentials "omit"})
          (p/then (fn [resp]
                    (if (.-ok resp)
                      (p/let [blob (.blob resp)]
                        (js/URL.createObjectURL blob))
                      nil)))
          (p/catch (fn [_] nil))))
    (p/resolved nil)))

(defn- fetch-event-content [hs-url token room-id event-id]
  (let [url (str hs-url "/_matrix/client/v3/rooms/" (js/encodeURIComponent room-id)
                 "/context/" (js/encodeURIComponent event-id) "?limit=0")]
    (-> (js/fetch url #js {:headers #js {:Authorization (str "Bearer " token)}
                           :mode "cors"
                           :credentials "omit"})
        (p/then (fn [resp]
                  (if (.-ok resp)
                    (.json resp)
                    (throw (js/Error. "Failed to fetch event context")))))
        (p/then (fn [json]
                  (let [event (.-event json)]
                    {:body (get-in (js->clj event) ["content" "body"])
                     :sender (or (.-sender event) "Someone")}))))))

(defn- show-smart-notification [push-data token hs-url]
  (let [sender        (or (.-sender_display_name push-data) (.-sender push-data) "Someone")
        room-name     (.-room_name push-data)
        room-id       (.-room_id push-data)
        unread-count  (.-unread push-data)
        encrypted?    (= (.-type push-data) "m.room.encrypted")
        avatar-mxc    (or (some-> push-data .-content .-avatar_url)
                          (some-> push-data .-sender_avatar_url))
        body          (if encrypted?
                        "🔒 Encrypted Message"
                        (or (some-> push-data .-content .-body) "New Message"))
        title         (if room-name (str sender " in " room-name) sender)]

    (when (exists? js/navigator.setAppBadge)
      (if (and unread-count (pos? unread-count))
        (.setAppBadge js/navigator unread-count)
        (when (= unread-count 0)
          (.clearAppBadge js/navigator))))

    (p/let [;icon-url (fetch-avatar-url hs-url token avatar-mxc)
            ]
      (.showNotification js/self.registration title
        #js {:body   body
             :icon   (or icon-url default-icon)
             :badge  default-badge
             :tag    (or room-id "matrix-chat")
             :data   #js {:room_id room-id}}))))



(js/self.addEventListener "push"
  (fn [event]
    (let [data (try (.. event -data json) (catch :default _ nil))]
      (when data
        (let [user-id (.-user_id data)]
          (.waitUntil event
            (p/let [session (get-session-from-opfs user-id)
                    token   (some-> session .-token)
                    hs-url  (some-> session .-hs_url)]
              (show-smart-notification data token hs-url))))))))

(js/self.addEventListener "notificationclick"
  (fn [event]
    (let [notification (.-notification event)
          room-id      (.. notification -data -room_id)]
      (.close notification)

      (.waitUntil event
        (p/let [clients (.matchAll js/self.clients #js {:type "window" :includeUncontrolled true})
                tab (first clients)]
          (if tab
            (do
              (.focus tab)
              (.postMessage tab #js {:type "NAVIGATE_TO_ROOM"
                                     :room_id room-id}))
            (.openWindow js/self.clients "/")))))))
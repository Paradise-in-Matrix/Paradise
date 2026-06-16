(ns paradise.media.component
  (:require [reagent.core :as r]
            ["react" :as react]
            [re-frame.db :as db]
            [cljs.core.async :refer [go <!]]
            [paradise.shared.client.state :as state]
            [cljs-workers.core :as main]
            [cljs-workers.mesh :as mesh]
            [taoensso.timbre :as log]
            [goog.object]
            [clojure.string :as str]
            [promesa.core :as p]
            [net :refer [!auth-context] :as net]))

(defn mxc->url
  ([mxc-url] (mxc->url mxc-url {}))
  ([mxc-url {:keys [homeserver type width height method] :or {type :download}}]
   (when (and (string? mxc-url) (str/starts-with? mxc-url "mxc://"))
     (let [db       (try @re-frame.db/app-db (catch :default _ {}))
           base-url (or homeserver (:homeserver-url db))]
       (when base-url
         (let [server-base (str/replace base-url #"/+$" "")
               resource    (str/replace mxc-url #"^mxc://" "")
               base-path   (str "/_matrix/client/v1/media/" (name type) "/" resource)]
           (if (= type :thumbnail)
             (str server-base base-path
                  "?width="  (or width 48)
                  "&height=" (or height 48)
                  "&method=" (or method "crop"))
             (str server-base base-path))))))))


(defn url->mxc [url]
  (if (and (string? url) (str/includes? url "/_matrix/"))
    (let [parts (str/split url #"/media/(?:download|thumbnail)/")]
      (if (= (count parts) 2)
        (str "mxc://" (second parts))
        url))
    url))

(defonce !ui-blob-cache (r/atom {}))
(defonce !in-flight (atom #{}))

(defn request-mxc! [mxc]
  (when (and mxc
             (not (get @!ui-blob-cache mxc))
             (not (contains? @!in-flight mxc)))
    (swap! !in-flight conj mxc)
    (let [url (if (str/starts-with? mxc "mxc://")
                (mxc->url mxc)
                mxc)]
      (if-not url
        (do
          (swap! !ui-blob-cache assoc mxc :error)
          (swap! !in-flight disj mxc))
        (-> (p/let [resp (net/fetch url)
                    buf  (.arrayBuffer resp)]
              (let [blob    (js/Blob. #js [buf])
                    obj-url (js/URL.createObjectURL blob)]
                (swap! !ui-blob-cache assoc mxc obj-url)))
            (p/catch (fn [e]
                       (js/console.error "MXC Fetch Error:" e)
                       (swap! !ui-blob-cache assoc mxc :error)))
            (p/finally (fn []
                         (swap! !in-flight disj mxc))))))))


(defonce !native-media-cache (js/Map.))

(defn execute-media-fetch! [node spinner-node cache-key mxc source-map mime-type fallback-url room-id event-id]
  (go
    (try
      (let [db   @re-frame.db/app-db
            res  (<! (mesh/do-with-thread! :media-pool
                                           {:handler :get-media
                                            :arguments {:room-id room-id
                                                        :event-id event-id
                                                        :source mxc
                                                        :source-map source-map
                                                        :hs-url (:auth/hs-url db)
                                                        :token (:auth/token db)}}))]
        (case (:status res)
          "unencrypted"
          (let [url (:url res)]
            (if-let [existing (.get !native-media-cache cache-key)]
              (do
                (set! (.-refs existing) (+ (.-refs existing) 1))
                (set! (.-src node) (.-url existing)))
              (do
                (.set !native-media-cache cache-key #js {:url url :refs 1})
                (set! (.-src node) url)))
            (when (and spinner-node (.-style spinner-node))
              (set! (.-display (.-style spinner-node)) "none")))

          "success"
          (let [buffer (:bytes res)
                blob   (js/Blob. #js [buffer] #js {:type mime-type})
                url    (js/URL.createObjectURL blob)]
            (if-let [existing (.get !native-media-cache cache-key)]
              (do
                (js/URL.revokeObjectURL url)
                (set! (.-refs existing) (+ (.-refs existing) 1))
                (set! (.-src node) (.-url existing)))
              (do
                (.set !native-media-cache cache-key #js {:url url :refs 1})
                (set! (.-src node) url)))
            (when (and spinner-node (.-style spinner-node))
              (set! (.-display (.-style spinner-node)) "none")))

          "error"
          (do
            (js/console.error "Media load failed:" (:msg res))
            (when fallback-url
              (set! (.-src node) fallback-url)))

          (js/console.error "Unknown status from worker:" res)))
      (catch :default e
        (js/console.error "GO BLOCK CRASH:" e)))))

(defn media-ref [node props]
  (let
      [mxc          (:mxc props)
        source-map   (or (:source-map props) (:sourceMap props))
        mime-type    (or (:mime-type props) (:mimeType props) "image/png")
        fallback-url (or (:fallback-url props) (:fallbackUrl props))
        room-id      (or (:room-id props) (:roomId props))
        event-id     (or (:event-id props) (:eventId props))
        cache-key    (or (get-in source-map [:file :url]) mxc)
        mounted?     (atom false)]
      (if node
    (when-not @mounted?
      (reset! mounted? true)
      (let [spinner-node (.-previousElementSibling node)]
        (if-let [cached (.get !native-media-cache cache-key)]
          (do
            (set! (.-refs cached) (+ (.-refs cached) 1))
            (set! (.-src node) (.-url cached))
            (when (and spinner-node (.-style spinner-node))
              (set! (.-display (.-style spinner-node)) "none")))
          (execute-media-fetch! node spinner-node cache-key mxc source-map mime-type fallback-url room-id event-id))))
    (when @mounted?
      (reset! mounted? false)
      (when-let [cached (.get !native-media-cache cache-key)]
        (set! (.-refs cached) (- (.-refs cached) 1))
        (when (<= (.-refs cached) 0)
          (js/setTimeout
           (fn []
             (let [latest-cached (.get !native-media-cache cache-key)]
               (when (and latest-cached (<= (.-refs latest-cached) 0))
                 (when (clojure.string/starts-with? (str (.-url latest-cached)) "blob:")
                   (js/URL.revokeObjectURL (.-url latest-cached)))
                 (.delete !native-media-cache cache-key))))
           10)))))))


(defn ^:react media-native [props]
  (let [tag-type     (or (:tag-type props) (:tagType props) "img")
        class        (or (:class props) (:className props) (:class-name props))
        style        (:style props)
        alt          (:alt props)
        on-error     (or (:on-error props) (:onError props))
        on-click     (or (:on-click props) (:onClick props))
        controls     (:controls props)]
    (react/createElement tag-type
                         #js {:className class
                              :style (clj->js style)
                              :alt alt
                              :controls controls
                              :onClick on-click
                              :loading (when (= tag-type "img") "lazy")
                              :decoding (when (= tag-type "img") "async")
                              :onError on-error
                              :ref #(media-ref % props)})))

#_(swap! paradise.shared.client.registry/!components assoc
       "comp:paradise.media.component/media-native" media-native)

#_(goog.object/set media-native "$native_comp" "comp:paradise.media.component/media-native")


(defn ^:ui media [props]
  [media-native
   props])
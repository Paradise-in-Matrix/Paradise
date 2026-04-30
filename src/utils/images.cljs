(ns utils.images
  (:require [reagent.core :as r]
            ["react" :as react]
            [re-frame.db :as db]
            [cljs.core.async :refer [go <!]]
            [client.state :as state]
            [cljs-workers.core :as main]
            [clojure.string :as str]
            [promesa.core :as p]
            [utils.net :refer [!auth-context] :as net]
            ))

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

(defn mxc-image [{:keys [mxc class style alt fallback-url on-error]}]
  (let [cached-val (get @!ui-blob-cache mxc)]
    (when (and mxc (not cached-val))
      (request-mxc! mxc))
    (cond
      (= cached-val :error)
      (if fallback-url
        [:img {:src fallback-url :class class :style style :alt alt :on-error on-error}]
        (do
          (when on-error
            (js/setTimeout on-error 0))
          [:div {:style {:display "none"}}]))
      (string? cached-val)
      [:img {:src cached-val :class class :style style :alt alt :on-error on-error}]
      :else
      [:span.spinner-placeholder {:class class :style (merge {:display "inline-block"} style)}])))

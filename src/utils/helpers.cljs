(ns utils.helpers
  (:require
   [utils.net :as net]
   [clojure.string :as str]
   [promesa.core :as p]
   [hickory.core :as h]
   [hickory.render :as hr]
   [clojure.walk :as walk]
   [taoensso.timbre :as log]
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


(def max-tag-nesting 100)

(def permitted-tags
  #{:font :del :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :p :a :ul :ol :sup :sub
    :li :b :i :u :strong :em :strike :s :code :hr :br :div :table :thead
    :tbody :tr :th :td :caption :pre :span :img :details :summary})

(def url-schemes #{"https" "http" "ftp" "mailto" "magnet"})

(def permitted-attrs
  {:font #{:style :data-mx-bg-color :data-mx-color :color}
   :span #{:style :data-mx-bg-color :data-mx-color :data-mx-spoiler :data-mx-maths :data-mx-pill :data-mx-ping :data-md}
   :div  #{:data-mx-maths}
   :blockquote #{:data-md}
   :h1 #{:data-md} :h2 #{:data-md} :h3 #{:data-md} :h4 #{:data-md} :h5 #{:data-md} :h6 #{:data-md}
   :pre  #{:data-md :class}
   :ol   #{:start :type :data-md}
   :ul   #{:data-md}
   :a    #{:name :target :href :rel :data-md}
   :img  #{:width :height :alt :title :src :data-mx-emoticon}
   :code #{:class :data-md}
   :strong #{:data-md} :i #{:data-md} :em #{:data-md} :u #{:data-md} :s #{:data-md} :del #{:data-md}})

(def non-text-tags #{:style :script :textarea :option :noscript :mx-reply})

(defn- parse-style-str
  "Converts 'color: red; margin-top: 10px' into {:color 'red' :margin-top '10px'}"
  [style-str]
  (if (string? style-str)
    (->> (str/split style-str #";")
         (remove str/blank?)
         (map #(str/split % #":"))
         (filter #(= 2 (count %)))
         (map (fn [[k v]] [(keyword (str/trim k)) (str/trim v)]))
         (into {}))
    style-str))

(defn- transform-font-span [attrs]
  (let [bg (get attrs :data-mx-bg-color)
        fg (get attrs :data-mx-color)
        existing-style (parse-style-str (get attrs :style))
        style-map (cond-> (or existing-style {})
                    bg (assoc :background-color bg)
                    fg (assoc :color fg))]
    (if (seq style-map)
      (assoc attrs :style style-map)
      attrs)))

(defn- transform-a [attrs]
  (assoc attrs :rel "noopener" :target "_blank"))

(defn- transform-img [attrs]
  (let [src (get attrs :src "")]
    (if (and (string? src) (str/starts-with? src "mxc://"))
      {:tag :img
       :attrs (-> attrs (assoc :class "timeline-emotes") (assoc :src (mxc->url src)))}
      {:tag :a
       :attrs {:href src :rel "noopener" :target "_blank"}
       :content [(or (get attrs :alt) src)]})))

(defn- filter-code-classes [attrs]
  (if-let [cls (:class attrs)]
    (let [classes (str/split cls #"\s+")
          valid (filter #(str/starts-with? % "language-") classes)]
      (if (seq valid)
        (assoc attrs :class (str/join " " valid))
        (dissoc attrs :class)))
    attrs))

(defn- valid-url? [href]
  (if (string? href)
    (let [scheme (-> href (str/split #":" 2) first str/lower-case)]
      (contains? url-schemes scheme))
    false))

(defn sanitize-nodes [nodes depth]
  (if (> depth max-tag-nesting)
    []
    (mapcat
     (fn [node]
       (cond
         (string? node)
         [node]

         (and (map? node) (= (:type node) :element))
         (let [{:keys [tag attrs content]} node]
           (cond
             (contains? non-text-tags tag)
             []

             (contains? permitted-tags tag)
             (let [allowed-keys (get permitted-attrs tag #{})
                   clean-attrs (select-keys attrs allowed-keys)
                   [final-tag final-attrs transformed-content]
                   (case tag
                     (:font :span) [tag (transform-font-span clean-attrs) nil]
                     :a            [tag (transform-a clean-attrs) nil]
                     :code         [tag (filter-code-classes clean-attrs) nil]
                     :img          (let [{t :tag a :attrs c :content} (transform-img clean-attrs)]
                                     [t a c])
                     [tag clean-attrs nil])
                   final-attrs (if (and (= final-tag :a)
                                        (not (valid-url? (:href final-attrs))))
                                 (dissoc final-attrs :href)
                                 final-attrs)
                   children (or transformed-content
                                (sanitize-nodes content (inc depth)))]
               [(if (seq final-attrs)
                  (into [final-tag final-attrs] children)
                  (into [final-tag] children))])

             :else
             (sanitize-nodes content depth)))

         :else []))
     nodes)))

(defn sanitize-custom-html [raw-html]
  (when raw-html
    (let [html-str (str raw-html)
          raw-fragments (h/parse-fragment html-str)
          hickory-maps  (map h/as-hickory raw-fragments)]
      (sanitize-nodes hickory-maps 0))))

(defn sanitize-text [raw-text]
  (when raw-text
    (str/escape (str raw-text) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"})))


(defn linkify-text [text]
  (if (str/blank? text)
    text
    (let [pattern #"https?://[^\s]+"
          parts (str/split text pattern -1)
          matches (re-seq pattern text)]
      (if (empty? matches)
        text
        (into [:span]
              (map-indexed
               (fn [i part]
                 (let [url (nth matches i nil)]
                   (if url
                     [:<> part [:a {:href url :target "_blank" :rel "noopener noreferrer"} url]]
                     part)))
               parts))))))


(defn format-divider-date [ts]
  (let [date         (js/Date. ts)
        today        (js/Date.)
        is-today     (= (.toDateString date) (.toDateString today))

        yesterday    (doto (js/Date.) (.setDate (- (.getDate today) 1)))
        is-yesterday (= (.toDateString date) (.toDateString yesterday))]

    (cond
      is-today
      (.format (js/Intl.RelativeTimeFormat. js/undefined #js {:numeric "auto"}) 0 "day")

      is-yesterday
      (.format (js/Intl.RelativeTimeFormat. js/undefined #js {:numeric "auto"}) -1 "day")

      :else
      (.toLocaleDateString date js/undefined
                           #js {:month "long"
                                :day "numeric"
                                :year "numeric"}))))


(defn format-time [ts]
  (when ts
    (let [date (js/Date. ts)]
      (.toLocaleTimeString date js/undefined #js {:hour "numeric" :minute "2-digit"}))))

(defn truncate-name [name max-len]
  (if (> (count name) max-len)
    (str (subs name 0 max-len) "...")
    name))

(defn join-names [names]
  (let [cnt (count names)]
    (case cnt
      0 ""
      1 (first names)
      2 (str (first names) " and " (second names))
      3 (str (first names) ", " (second names) ", and " (first (last names)))
      (str (first names) ", " (second names) ", and " (- cnt 2) " others"))))


(defn format-readers [names]
  (let [joined (join-names names)]
    (if (empty? joined)
      nil
      (str joined (if (= 1 (count names))
                    " is following the conversation"
                    " are following")))))

(defn get-status-string [tr type names]
  (let [cnt (count names)
        base-path (if (= type :typing)
                    "container.timeline.status.typing"
                    "container.timeline.status.reading")]
    (case cnt
      0 ""
      1 (tr [(keyword base-path "one")] [(truncate-name (first names) 16)])
      2 (tr [(keyword base-path "two")] [(truncate-name (first names) 16) (truncate-name (second names) 16)])
      3 (tr [(keyword base-path "three")] [(truncate-name (first names) 16) (truncate-name (second names) 16) (truncate-name (nth names 2) 16)])
      (tr [(keyword base-path "many")] [(truncate-name (first names) 16) (truncate-name (second names) 16) (- cnt 2)]))))


(defn fetch-state-event [homeserver token room-id event-type state-key]
  (let [clean-hs (clojure.string/replace homeserver #"/+$" "")
        key-path (if (empty? state-key) "" (str "/" state-key))
        url      (str clean-hs "/_matrix/client/v3/rooms/" room-id "/state/" event-type key-path)]
    (-> (p/let [resp (net/fetch url #js {:headers #js {:Authorization (str "Bearer " token)}})]
          (when (.-ok resp)
            (.json resp)))
        (p/catch (constantly nil)))))

(defn fetch-room-state
  "Fetches room state and applies an optional transformation function (predicate/transducer).
   - If event-type is nil, fetches the full state array.
   - xf is a function that receives the clojurized data."
  ([homeserver token room-id]
   (fetch-room-state homeserver token room-id nil nil identity))
  ([homeserver token room-id event-type state-key xf]
   (let [clean-hs (str/replace homeserver #"/+$" "")
         key-path (if (empty? state-key) "" (str "/" state-key))
         url      (str clean-hs "/_matrix/client/v3/rooms/" room-id "/state"
                       (when event-type (str "/" event-type key-path)))]
     (-> (p/let [resp (net/fetch url #js {:headers #js {:Authorization (str "Bearer " token)}})]
           (when (.-ok resp)
             (p/let [json (.json resp)
                     data (js->clj json :keywordize-keys true)]
               (xf data))))
         (p/catch (fn [err]
                    (log/error "State fetch failed:" url err)
                    nil))))))
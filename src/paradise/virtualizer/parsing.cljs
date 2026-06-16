(ns paradise.virtualizer.parsing
  (:require ["@xmldom/xmldom" :as xmldom]
            ["@chenglou/pretext" :refer [prepareWithSegments]]
            [goog.object]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [paradise.ui.container.timeline.item :refer [event-tile-render]]
            [paradise.virtualizer.state :as state]
            [paradise.shared.utils.helpers :refer [process-raw-event sanitize-custom-html linkify-text]]))

(set! js/globalThis.DOMParser
      (fn []
        (this-as this
          (let [real-parser (new (.-DOMParser xmldom))]
            (set! (.-parseFromString this)
                  (fn [html mime-type]
                    (let [safe-html (if (and (string? html) (not (str/includes? html "<body")))
                                      (str "<html><body>" html "</body></html>")
                                      (or html ""))
                          doc (.parseFromString real-parser safe-html mime-type)
                          body (aget (.getElementsByTagName doc "body") 0)]
                      (set! (.-body doc) body)
                      doc)))
            this))))

(set! js/globalThis.document
      #js {:createElement (fn [tag]
                            (if (= tag "canvas")
                              (js/OffscreenCanvas. 1 1)
                              (.createElement (.parseFromString (new (.-DOMParser xmldom)) "<html/>" "text/xml") tag)))
           :body #js {:childNodes #js []}})


(defn extract-first-url [text]
  (when text
    (when-let [match (re-find #"https?://[^\s\"'<>]+" text)]
      (str/replace match #"[.,:;!?]$" ""))))

(def sanitize-cache (atom {}))

(defn memoized-sanitize [html]
  (if-let [hit (get @sanitize-cache html)]
    hit
    (let [res (sanitize-custom-html html)]
      (swap! sanitize-cache (fn [m]
                              (let [m' (assoc m html res)]
                                (if (> (count m') 300)
                                  (dissoc m' (first (keys m')))
                                  m'))))
      res)))


(defn camel-case [s]
  (if (string? s)
    (str/replace s #"-([a-z])" (fn [[_ c]] (str/upper-case c)))
    s))

(defn expand-hiccup [node]
  (cond
    (and (vector? node) (fn? (first node)))
    (let [comp-fn (first node)
          react-boundary (or (.-$react_boundary comp-fn) (goog.object/get comp-fn "$react_boundary"))]
      (if react-boundary
        (let [has-attrs? (map? (second node))
              attrs      (if has-attrs? (second node) nil)
              children   (if has-attrs? (drop 2 node) (drop 1 node))]
          (if attrs
            (into [react-boundary attrs] (map expand-hiccup children))
            (into [react-boundary] (map expand-hiccup children))))
        (let [args   (rest node)
              result (apply comp-fn args)]
          (if (fn? result)
            (expand-hiccup (apply result args))
            (expand-hiccup result)))))

    (and (vector? node) (fn? (first node)))
    (let [comp-fn (first node)
          args    (rest node)
          result  (apply comp-fn args)]
      (if (fn? result)
        (expand-hiccup (apply result args))
        (expand-hiccup result)))

    (and (vector? node) (keyword? (first node)))
    (let [tag (first node)
          has-attrs? (map? (second node))
          attrs (if has-attrs? (second node) nil)
          children (if has-attrs? (drop 2 node) (drop 1 node))]
      (if attrs
        (into [tag attrs] (map expand-hiccup children))
        (into [tag] (map expand-hiccup children))))

    (seq? node) (map expand-hiccup node)
    :else node))


(defn process-props [attrs]
  (let [js-props #js {}
        process-kv (fn [k-name v]
                     (let [is-handler? (boolean (re-find #"^on(?:-[a-z]|[A-Z])" k-name))]
                       (cond
                         is-handler?
                         (let [ptr (when (and (some? v) (or (object? v) (fn? v)))
                                     (or (.-$fn_ptr v) (goog.object/get v "$fn_ptr")))]
                           (cond
                             ptr
                             (let [env (or (.-$env v) (goog.object/get v "$env"))
                                   fn-str (str ptr)
                                   clean-ptr (if (clojure.string/starts-with? fn-str ":")
                                               (subs fn-str 1)
                                               fn-str)]
                               (aset js-props (camel-case k-name) #js {"$fn_ptr" clean-ptr
                                                                       "$env"    (clj->js env)}))

                             (or (fn? v) (= "function" (goog/typeOf v)))
                             (let [fn-source (try (.toString v) (catch :default _ "unknown"))]
                               (log/error "FATAL: Unlifted function detected on" k-name "\nSource:" fn-source)
                               (aset js-props (camel-case k-name) nil))

                             (nil? v)
                             (aset js-props (camel-case k-name) nil)

                             :else
                             (do
                               (log/error "FATAL: Invalid handler value on" k-name "Value:" v)
                               (aset js-props (camel-case k-name) nil))))

                         (= k-name "style")
                         (let [style-obj #js {}
                               style-map (if (map? v) v (js->clj v))]
                           (doseq [[sk sv] style-map]
                             (aset style-obj (camel-case (name sk)) sv))
                           (aset js-props "style" style-obj))

                         (or (= k-name "class") (= k-name "className"))
                         (aset js-props "className" (if (coll? v) (clojure.string/join " " v) v))

                         (and (clojure.string/includes? k-name "-")
                              (not (clojure.string/starts-with? k-name "data-"))
                              (not (clojure.string/starts-with? k-name "aria-")))
                         (aset js-props (camel-case k-name) (if (map? v) (clj->js v) v))

                         :else
                         (if (or (fn? v)
                                 (and (some? v)
                                      (or (object? v) (fn? v))
                                      (or (.-$fn_ptr v) (goog.object/get v "$fn_ptr"))))
                           (do
                             (log/error "FATAL: Uncaught function leaked into standard props:" k-name)
                             (aset js-props k-name nil))
                           (aset js-props k-name (if (map? v) (clj->js v) v))))))]
    (if (map? attrs)
      (doseq [[k v] attrs] (process-kv (name k) v))
      (when (object? attrs)
        (let [keys-arr (js/Object.keys attrs)
              len (.-length keys-arr)]
          (loop [i 0]
            (when (< i len)
              (let [k (aget keys-arr i)]
                (process-kv k (goog.object/get attrs k))
                (recur (inc i))))))))
    js-props))

(defn hiccup->pojo [node]
  (cond
    (nil? node) nil
    (boolean? node) node

    (vector? node)
    (if (or (keyword? (first node))
            (and (string? (first node)) (.startsWith (first node) "comp:"))
            (fn? (first node))
            (= "<>" (first node))
            (and (object? (first node)) (not (string? (first node)))))
      (let [tag-kw (first node)
            tag-name (cond
                       (keyword? tag-kw) (name tag-kw)
                       (string? tag-kw) tag-kw
                       (object? tag-kw) (or (.-displayName tag-kw) (.-name tag-kw) "unknown-object")
                       (fn? tag-kw) (if-let [ptr (or (.-$fn_ptr tag-kw) (goog.object/get tag-kw "$fn_ptr"))]
                                      (str "comp:" (if (keyword? ptr) (subs (str ptr) 1) (str ptr)))
                                      (or (.-displayName tag-kw) (.-name tag-kw) "unknown-fn"))
                       :else "div")

            is-comp? (or (and (keyword? tag-kw) (= (namespace tag-kw) "comp"))
                         (and (string? tag-name) (.startsWith tag-name "comp:")))

            [tag & classes] (if is-comp?
                              [tag-name]
                              (clojure.string/split tag-name #"\."))

            has-attrs? (map? (second node))
            attrs (if has-attrs? (second node) {})
            children (if has-attrs? (drop 2 node) (drop 1 node))
            js-props (process-props attrs)]

        (when (seq classes)
          (let [existing (goog.object/get js-props "className")
                joined-classes (clojure.string/join " " classes)
                final-class (if existing (str joined-classes " " existing) joined-classes)]
            (aset js-props "className" final-class)))

        #js {:type (cond
                     is-comp? (if (.startsWith tag "comp:") tag (str "comp:" tag))
                     (empty? tag) "div"
                     :else tag)
             :props (if (= 0 (alength (js/Object.keys js-props))) nil js-props)
             :children (to-array (map hiccup->pojo children))})

      (to-array (map hiccup->pojo node)))

    (seq? node) (to-array (map hiccup->pojo node))
    (string? node) node
    (number? node) (str node)

    (object? node)
    (if (goog.object/containsKey node "_owner")
      (let [raw-props (.-props node)
            children (goog.object/get raw-props "children")
            js-props (process-props raw-props)]
        (js/Reflect.deleteProperty js-props "children")
        #js {:type (.-type node)
             :props (if (= 0 (alength (js/Object.keys js-props))) nil js-props)
             :children (cond
                         (array? children) (.map children hiccup->pojo)
                         (some? children) #js [(hiccup->pojo children)]
                         :else #js [])})
      node)

    :else ""))

(defn extract-lambda-paths [js-ast]
  (let [paths #js []]
    (letfn [(crawl [obj current-path]
              (when (and obj (not (string? obj)) (not (number? obj)) (not (boolean? obj)))
                (if (or (goog.object/get obj "$fn_ptr") (goog.object/get obj "$fn-ptr"))
                  (do
                    (.push paths current-path)
                    (when-let [env (goog.object/get obj "$env")]
                      (let [env-path (.slice current-path)]
                        (.push env-path "$env")
                        (crawl env env-path))))
                  (if (js/Array.isArray obj)
                    (loop [i 0 len (.-length obj)]
                      (when (< i len)
                        (let [next-path (.slice current-path)]
                          (.push next-path i)
                          (crawl (aget obj i) next-path))
                        (recur (inc i) len)))
                    (let [keys-arr (js/Object.keys obj)
                          len (.-length keys-arr)]
                      (loop [i 0]
                        (when (< i len)
                          (let [k (aget keys-arr i)
                                next-path (.slice current-path)]
                            (.push next-path k)
                            (crawl (goog.object/get obj k) next-path))
                          (recur (inc i)))))))))]
      (crawl js-ast #js [])
      (.reverse paths)
      paths)))

(defn build-ast-tree [laid-out]
  (let [render-fn (or (get @state/!worker-overrides :event-tile-render)
                      (get @state/!worker-components :event-tile-render)
                      event-tile-render)]
    (if render-fn
      (mapv (fn [item]
              (let [outer-res  (render-fn item nil false nil nil)
                    hiccup-ast (if (fn? outer-res)
                                 (outer-res item nil false nil nil)
                                 outer-res)
                    expanded   (expand-hiccup hiccup-ast)
                    pojo       (hiccup->pojo expanded)]
                (assoc item :worker-data pojo)))
            laid-out)
      (do
        (log/error "FATAL: event-tile-render NOT FOUND registry!")
        laid-out))))

(defn precompile-event-data [e source]
  (let [processed (process-raw-event e source)
        id (:id processed)
        content   (:content processed)
        inner     (or (get-in content [:inner :content]) content)
        body      (or (:body inner) (:body content) (:caption inner) (:caption content) "")
        html      (or (:html inner) (:html content) "")
        txt-str   (str (or (:clean-text processed) body))
        html-txt  (str (or (:clean-html processed) html))
        has-url?  (boolean (re-find #"https?://[^\s]+" txt-str))
        measure-str (if has-url?
                      (str/replace txt-str #"https?://[^\s]+"
                                   (fn [url-match]
                                     (let [url (if (coll? url-match) (first url-match) url-match)]
                                       (str/replace url #"([/=?&._:-])" "$1\u200B"))))
                      txt-str)
        is-quote?       (str/includes? html-txt "<blockquote")
        has-code-block? (or (str/includes? txt-str "```") (str/includes? html-txt "<pre>"))
        has-html?       (boolean (seq html-txt))
        first-url       (extract-first-url txt-str)
        is-edited?      (or (:is-edited? content) (get-in content [:inner :content :is-edited?]))
        pre-compiled-hiccup (if has-html?
                              (into [:span.body.formatted] (memoized-sanitize html))
                              (into [:span.body] (linkify-text body)))
        font-str
        (if has-code-block?
                   "13.68px 'fira code', monospace"
                   "15.2px Inter, sans-serif")
;;        font-str (if has-code-block?
  ;;                 (:code-font theme-metrics "13.68px 'fira code', monospace")
    ;;               (:font theme-metrics "15.2px Inter, sans-serif"))
        pretext-prep (try
                       (prepareWithSegments measure-str font-str #js {:whiteSpace "pre-wrap" :wordBreak "normal"})
                       (catch js/Error _ nil))
        pojo-content (hiccup->pojo pre-compiled-hiccup)]
    (assoc processed
           :pre-compiled-hiccup pre-compiled-hiccup
           :worker-pojo         pojo-content
           :id                  id
           :pretext-prep        pretext-prep
           :first-url           first-url
           :measure-str         measure-str
           :is-quote?           is-quote?
           :has-code-block?     has-code-block?
           :has-html?           has-html?
           :has-url?            has-url?
           :is-edited-calc?     is-edited?)))

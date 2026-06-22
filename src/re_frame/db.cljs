(ns re-frame.db
  (:require [re-frame.interop :refer [ratom]]
            [cognitect.transit :as t]
            [editscript.core :as e]
            [editscript.edit :as edit]))

(defonce !reactive-state (ratom {}))
(defonce !eve-ref (atom nil))

(defonce !async-broadcaster (atom nil))
(def writer (t/writer :json))
(def reader (t/reader :json))

(defn set-async-broadcaster! [send-fn]
  (reset! !async-broadcaster send-fn))


(defn get-encoded-state []
  (t/write writer @!reactive-state))

(defn apply-remote-patch!
  [encoded-patch]
  (let [edits (t/read reader encoded-patch)]
    (cljs.core/swap! !reactive-state e/patch (edit/edits->script edits))))

(defn broadcast-async!
  "Calculates the exact mutations and sends them to the worker."
  [old-state new-state]
  (when-let [broadcast @!async-broadcaster]
    (let [edits (e/get-edits (e/diff old-state new-state {:algo :quick}))]
      (when (seq edits)
        (try
          (broadcast (t/write writer edits))
          (catch :default e
            (js/console.error "FATAL: Transit serialization failed! Dropping patch.")
            (js/console.error "The un-serializable edits were:" edits)
            (throw e)))))))

(defn start-sab-sync-loop! []
  (let [sync-fn (fn []
                  (when-let [ea @!eve-ref]
                    (let [latest-state @ea]
                      (when-not (identical? @!reactive-state latest-state)
                        (cljs.core/reset! !reactive-state latest-state)))))]
    (if (exists? js/window)
      (let [tick (fn tick []
                   (sync-fn)
                   (js/requestAnimationFrame tick))]
        (tick))
      (js/setInterval sync-fn 16))))

(defn set-eve-atom! [eve-atom]
  (cljs.core/swap! eve-atom
                   (fn [sab-state]
                     (reduce-kv assoc (or sab-state {}) @!reactive-state)))
  (reset! !eve-ref eve-atom)
  (cljs.core/reset! !reactive-state @eve-atom)
  (start-sab-sync-loop!))

(extend-type js/BigInt
  cljs.core/IEquiv
  (-equiv [this other]
    (js* "~{} === ~{}" this other))

  cljs.core/IHash
  (-hash [this]
    (hash (.toString this))))

(def app-db
  (reify
    cljs.core/IDeref
    (-deref [_]
      (cljs.core/deref !reactive-state))

    cljs.core/IReset
    (-reset! [_ new-val]
      (if-let [ea @!eve-ref]
        (let [res (cljs.core/reset! ea new-val)]
          (cljs.core/reset! !reactive-state res)
          res)
        (let [old-state @!reactive-state
              res       (cljs.core/reset! !reactive-state new-val)]
          (broadcast-async! old-state res)
          res)))

    cljs.core/ISwap
    (-swap! [_ f]
      (if-let [ea @!eve-ref]
        (let [res (cljs.core/swap! ea f)]
          (cljs.core/reset! !reactive-state res)
          res)
        (let [old-state @!reactive-state
              res       (cljs.core/swap! !reactive-state f)]
          (broadcast-async! old-state res)
          res)))

    (-swap! [_ f a]
      (if-let [ea @!eve-ref]
        (let [res (cljs.core/swap! ea f a)]
          (cljs.core/reset! !reactive-state res)
          res)
        (let [old-state @!reactive-state
              res       (cljs.core/swap! !reactive-state f a)]
          (broadcast-async! old-state res)
          res)))

    (-swap! [_ f a b]
      (if-let [ea @!eve-ref]
        (let [res (cljs.core/swap! ea f a b)]
          (cljs.core/reset! !reactive-state res)
          res)
        (let [old-state @!reactive-state
              res       (cljs.core/swap! !reactive-state f a b)]
          (broadcast-async! old-state res)
          res)))

    (-swap! [_ f a b xs]
      (if-let [ea @!eve-ref]
        (let [res (apply cljs.core/swap! ea f a b xs)]
          (cljs.core/reset! !reactive-state res)
          res)
        (let [old-state @!reactive-state
              res       (apply cljs.core/swap! !reactive-state f a b xs)]
          (broadcast-async! old-state res)
          res)))

    cljs.core/IWatchable
    (-notify-watches [_ old new] (cljs.core/-notify-watches !reactive-state old new))
    (-add-watch [_ key f] (cljs.core/-add-watch !reactive-state key f))
    (-remove-watch [_ key] (cljs.core/-remove-watch !reactive-state key))))
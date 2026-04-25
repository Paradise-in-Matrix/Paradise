(ns utils.macros
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cljs.analyzer.api :as ana]
            [cljs.env :as env]
            ))

(defmacro ocall
  [obj method & args]
  `(let [obj# ~obj
         m# ~(name method)
         f# (js/goog.object.get obj# m#)]
     (if (fn? f#)
       (.apply f# obj# (cljs.core/array ~@args))
       (throw (js/Error. (str "Method " m# " is not a function (is it a property?)"))))))


(defmacro oget
  "Property access for WASM objects that hides behind prototypes."
  [obj prop]
  `(js/goog.object.get ~obj ~(name prop)))




(defmacro expose-ns [ns-sym]
  (let [public-vars (keys (ana/ns-publics env/*compiler* ns-sym))]
    (into {}
          (map (fn [v]
                 [`(quote ~v) (symbol (str ns-sym "/" v))])
               public-vars))))

(defmacro defoverride [comp-name args & body]
  (list 'do
        (list* 'defn comp-name args body)
        (list 'swap! 'client.state/!active-overrides 'assoc (keyword (name comp-name))
              (list 'hash-map :plugin-id (list 'deref 'sci-runner/!current-eval-plugin) :fn comp-name))))


(defmacro defui [comp-name args & body]
  (let [kw           (keyword comp-name)
        default-name (symbol (str comp-name "-default"))]
    `(do
       (defn ~default-name ~args ~@body)
       (swap! client.state/!components #(if (contains? % ~kw) % (assoc % ~kw ~default-name)))
       (defn ~comp-name [& args#]
         (let [override# (get @client.state/!active-overrides ~kw)
               live#     (if override#
                           (:fn override#)
                           (get @client.state/!components ~kw))]
           (into [live#] args#))))))


(defmacro gen-translation-map [dir-path]
  (let [files (filter #(.endsWith (.getName %) ".json") 
                      (file-seq (io/file dir-path)))
        mapping (into {} (for [f files]
                           (let [lang (second (re-find #"([^/]+)\.json$" (.getPath f)))]
                             [lang `(fn [] (js/import ~(.getPath f)))])))]
    mapping))

(defmacro load-static-config [path]
  (edn/read-string (slurp path)))
(ns utils.macros
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn
            ]))

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

(defmacro gen-translation-map [dir-path]
  (let [files (filter #(.endsWith (.getName %) ".json") 
                      (file-seq (io/file dir-path)))
        mapping (into {} (for [f files]
                           (let [lang (second (re-find #"([^/]+)\.json$" (.getPath f)))]
                             [lang `(fn [] (js/import ~(.getPath f)))])))]
    mapping))

(defmacro load-static-config [path]
  (edn/read-string (slurp path)))

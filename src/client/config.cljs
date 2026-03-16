(ns client.config
  (:require [cljs.reader :refer [read-string]]
            [promesa.core :as p]))

(defn load-config []
  (-> (js/fetch "/config.edn")
      (p/then #(.text %))
      (p/then #(read-string %))))
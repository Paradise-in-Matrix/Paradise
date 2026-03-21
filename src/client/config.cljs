(ns client.config
  (:require [cljs.reader :refer [read-string]]
   [taoensso.timbre :as log]
            [promesa.core :as p]))

(defn load-config []
  (-> (js/fetch "./config.edn"  #js {:cache "no-store"} )
      (p/then #(.text %))
      (p/then #(read-string %))))

(defn check-remote-version []
  (let [url (str "./config.edn?t=" (.getTime (js/Date.)))]
    (-> (js/fetch url #js {:cache "no-store"})
        (p/then #(.text %))
        (p/then #(read-string %))
        (p/then :version)
        (p/catch (fn [_] nil)))))
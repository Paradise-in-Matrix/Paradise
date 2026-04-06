(ns client.config
  (:require [cljs.reader :refer [read-string]]
            [taoensso.timbre :as log]
            [re-frame.core :as re-frame]
            [promesa.core :as p]))

(defn load-config []
  (-> (js/fetch "./config.edn"  #js {:cache "no-store"} )
      (p/then #(.text %))
      (p/then #(read-string %))))

(defn load-i18n []
  (-> (js/fetch "./i18n.edn" #js {:cache "no-store"})
      (p/then #(.text %))
      (p/then (fn [raw]
                (let [dict (cljs.reader/read-string raw)]
                  (re-frame/dispatch-sync [:i18n/set-dictionary dict])
                  dict)))))


(defn check-remote-version []
  (let [url (str "./config.edn?t=" (.getTime (js/Date.)))]
    (-> (js/fetch url #js {:cache "no-store"})
        (p/then #(.text %))
        (p/then #(read-string %))
        (p/then :version)
        (p/catch (fn [_] nil)))))
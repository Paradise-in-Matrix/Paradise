(ns utils.logger
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.core :as appenders]))

(def json-appender
  {:enabled? true
   :async? false
   :min-level :info
   :fn (fn [{:keys [level ?err msg_ timestamp_ ?ns-str ?file ?line]}]
         (let [entry {:level     (name level)
                      :timestamp (force timestamp_)
                      :ns        ?ns-str
                      :file      ?file
                      :line      ?line
                      :message   (force msg_)
                      :error     (when ?err (str ?err))}]
           (js/console.log (js/JSON.stringify (clj->js entry)))))})

(defn init! []
  (timbre/merge-config!
   {:level :info
    :appenders
    {:console (appenders/console-appender)
     :json    json-appender}})
  (when ^boolean js/goog.DEBUG
    (timbre/merge-config!
     {:appenders {:json {:enabled? false}}})))

(def trace  timbre/trace)
(def debug  timbre/debug)
(def info   timbre/info)
(def warn   timbre/warn)
(def error  timbre/error)
(def fatal  timbre/fatal)

(defn plugin-debug [p-id line & args] (timbre/with-context {:plugin-id p-id :plugin-line line} (timbre/debug (str/join " " args))))
(defn plugin-info  [p-id line & args] (timbre/with-context {:plugin-id p-id :plugin-line line} (timbre/info  (str/join " " args))))
(defn plugin-warn  [p-id line & args] (timbre/with-context {:plugin-id p-id :plugin-line line} (timbre/warn  (str/join " " args))))
(defn plugin-error [p-id line & args] (timbre/with-context {:plugin-id p-id :plugin-line line} (timbre/error (str/join " " args))))

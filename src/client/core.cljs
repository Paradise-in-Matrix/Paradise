(ns client.core
  (:require
   [taoensso.timbre :as log]
   [utils.logger :as logger]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]
   [client.login :as login]
   [re-frame.core :as re-frame]
   [service-worker-handler :refer [register-sw!]]
   [client.state :as state :refer [sdk-world]]
   [promesa.core :as p]
   [app :as app])
  (:require-macros [utils.macros :refer [ocall oget load-static-config]]))

(re-frame/reg-event-db
 :sdk/set-client
 (fn [db [_ raw-client]]
   (assoc db :client raw-client)))

(re-frame/reg-sub
 :sdk/client
 (fn [db _]
   (:client db)))


(defn ^:export init []
  (app/init)
  (logger/init!)
  (log/debug  "Entering Paradise!")
  )
(ns paradise.shared.client.core
  (:require
   [paradise.shared.utils.logger :as logger]
   [re-frame.core :as re-frame]
   [goog.object]
   [paradise.ui.app :as app]
   ["react" :as react]
   [re-frame.db :as db]
   [reagent.core :as r]
   [promesa.core :as p]
   [paradise.binding.core :as binding]
   [paradise.binding.streams :as streams]
   [paradise.shared.client.state :as state]
   [paradise.shared.client.config :refer [eve-enabled?]]
   [paradise.shared.client.session-store :as store]
   [cljs-workers.core :as main]
   [cljs-workers.mesh :as mesh]
   [cljs.core.async.interop :refer-macros [<p!]]
   [cljs.core.async :refer [go <!]]
   [taoensso.timbre :as log]))

(defn init-worker! []
  (when-not @state/!engine-pool
    (reset! state/!engine-pool
            (main/create-pool 1 "engine.js"
                              {:worker-opts #js {:type "module"}
                               :on-stream binding/handle-worker-stream!})))
  (when-not @state/!media-pool
    (reset! state/!media-pool
            (main/create-pool 3 "media.js"
                              {:worker-opts #js {:type "module"}})))

  (when-not @state/!virtualizer-pool
    (reset! state/!virtualizer-pool
            (main/create-pool 1 "virtualizer.js"
                              {:worker-opts #js {:type "module"}
                               :on-stream binding/handle-worker-stream!}))))

(defn bind-workers! [engine-pool media-pool virtualizer-pool app-db-payload]
  (let [ev-chan  (js/MessageChannel.)
        ev-port1 (.-port1 ev-chan)
        ev-port2 (.-port2 ev-chan)
        vm-chan  (js/MessageChannel.)
        vm-port1 (.-port1 vm-chan)
        vm-port2 (.-port2 vm-chan)
        mv-chan  (js/MessageChannel.)
        mv-port1 (.-port1 mv-chan)
        mv-port2 (.-port2 mv-chan)
        vv-chan  (js/MessageChannel.)
        vv-port1 (.-port1 vv-chan)
        vv-port2 (.-port2 vv-chan)
        mm-chan  (js/MessageChannel.)
        mm-port1 (.-port1 mm-chan)
        mm-port2 (.-port2 mm-chan)
        me-chan  (js/MessageChannel.)
        me-port1 (.-port1 me-chan)
        me-port2 (.-port2 me-chan)
        db-chan  (js/MessageChannel.)
        db-port1 (.-port1 db-chan)
        db-port2 (.-port2 db-chan)
        ee-chan  (js/MessageChannel.)
        ee-port1 (.-port1 ee-chan)
        ee-port2 (.-port2 ee-chan)]

    (if eve-enabled?
      (main/do-with-pool! virtualizer-pool
                          {:handler   :bind-app-db
                           :arguments {:eve-payload app-db-payload}})
      (do
        (db/set-async-broadcaster! (fn [payload] (.postMessage db-port1 payload)))
        (set! (.-onmessage db-port1) (fn [e] (db/apply-remote-patch! (.-data e))))

        (main/do-with-pool! virtualizer-pool
                            {:handler   :bind-app-db
                             :arguments {:eve-payload {:mode :async
                                                       :initial-state (db/get-encoded-state)}
                                         :port        db-port2}
                             :transfer  [:port]})))

    (main/do-with-pool! engine-pool
                        {:handler   :register-port
                         :arguments {:identity-id :virtualizer-pool :port ev-port1}
                         :transfer  [:port]})
    (main/do-with-pool! virtualizer-pool
                        {:handler   :register-port
                         :arguments {:identity-id :engine-pool :port ev-port2}
                         :transfer  [:port]})

    (main/do-with-pool! media-pool
                        {:handler   :register-port
                         :arguments {:identity-id :virtualizer-pool :port vm-port1}
                         :transfer  [:port]})
    (main/do-with-pool! virtualizer-pool
                        {:handler   :register-port
                         :arguments {:identity-id :media-pool :port vm-port2}
                         :transfer  [:port]})

    (mesh/register-thread! :media-pool mm-port1)
    (main/do-with-pool! media-pool
                        {:handler   :register-port
                         :arguments {:identity-id :main-thread :port mm-port2}
                         :transfer  [:port]})

    (mesh/register-thread! :engine-pool me-port1)
    (main/do-with-pool! engine-pool
                        {:handler   :register-port
                         :arguments {:identity-id :main-thread :port me-port2}
                         :transfer  [:port]})

    (mesh/register-thread! :virtualizer-pool mv-port1)
    (main/do-with-pool! virtualizer-pool
                        {:handler   :register-port
                         :arguments {:identity-id :main-thread :port mv-port2}
                         :transfer  [:port]})

    (main/do-with-pool! virtualizer-pool
                        {:handler   :register-port
                         :arguments {:identity-id :virtualizer-pool :port vv-port1}
                         :transfer  [:port]})
    (main/do-with-pool! virtualizer-pool
                        {:handler   :register-port
                         :arguments {:identity-id :virtualizer-loopback :port vv-port2}
                         :transfer  [:port]})

    (main/do-with-pool! engine-pool
                        {:handler   :register-port
                         :arguments {:identity-id :engine-pool :port ee-port1}
                         :transfer  [:port]})
    (main/do-with-pool! engine-pool
                        {:handler   :register-port
                         :arguments {:identity-id :engine-loopback :port ee-port2}
                         :transfer  [:port]})))

(extend-type eve.vec/EveVector
  IReversible
  (-rseq [coll]
    (let [c (count coll)]
      (when (pos? c)
        (map #(nth coll %) (range (dec c) -1 -1))))))

(extend-type eve.map/EveHashMap
  IEquiv
  (-equiv [this other]
    (and (instance? eve.map/EveHashMap other)
         (= (.-offset__ this) (.-offset__ other))))
  IHash
  (-hash [this]
    (hash (.-offset__ this))))

(extend-type eve.vec/EveVector
  IEquiv
  (-equiv [this other]
    (and (instance? eve.vec/EveVector other)
         (= (.-offset__ this) (.-offset__ other))))
  IHash
  (-hash [this]
    (hash (.-offset__ this))))

(re-frame/reg-event-fx
 :app/thread-boot
 (fn [_]
   (init-worker!)
     (let [app-db-payload   @state/!eve-app-db-payload
           engine-pool      @state/!engine-pool
           media-pool       @state/!media-pool
           virtualizer-pool @state/!virtualizer-pool]
       (bind-workers! engine-pool media-pool virtualizer-pool app-db-payload)
       )
   {}
   ))

(re-frame/reg-event-fx
 :app/bootstrap
 (fn [_ [_ target-user-id engine-id]]
   (go
       (try
         (let [boot-res  (<! (mesh/do-with-thread! :engine-pool
                                                 {:handler :bootstrap
                                                  :arguments {:target-user-id target-user-id}}))
               status-kw (keyword (:status boot-res))]
           (case status-kw
             :success (re-frame/dispatch [:auth/login-success (assoc boot-res :engine-id engine-id)])
             :empty   (re-frame/dispatch [:auth/set-status :logged-out])
             :error   (log/error "Bootstrap failed:" (or (:msg boot-res) (:message boot-res) boot-res))))
         (catch :default e
           (log/error "Fatal bootstrap error:" e))))
   {}))

(defn register-engine [protocol-str]
  (go
    (let [res (<! (mesh/do-with-thread! :engine-pool
                                        {:handler :register-engine
                                         :arguments {:engine-id protocol-str}}))]
      (if (= "success" (:status res))
        (js/console.log "Engine loaded successfully:" protocol-str)
        (js/console.error "Failed to load engine:" (:msg res))))))

(re-frame/reg-event-fx
 :engine/load-protocol
 (fn [_ [_ protocol-str]]
   (register-engine protocol-str)
   {}))

(re-frame/reg-event-fx
 :sdk/start-sync
 (fn [_ _]
   (go
     (let [pool @state/!engine-pool
           res  (<! (main/do-with-pool! pool {:handler :start-sync}))]
       (if (= (:status res) "success")
         (log/info "Main sync loop started successfully.")
         (log/error "Failed to start sync:" (:msg res)))))
   {}))

(re-frame/reg-event-fx
 :app/cold-boot
 (fn [_ [_ target-user-id requested-engine]]
   (go
     (let [saved-engine (<p! (store/get-setting "last-active-engine"))
           engine-id    (when-let [e (or requested-engine saved-engine)]
                          (keyword e))]

       (when engine-id
         (<! (register-engine engine-id))
         (binding/set-active-engine! engine-id)
         (streams/init-engine-streams! engine-id))

       (log/info "Cold boot sequence initiated. Targeting engine:" engine-id)

       (if (and target-user-id engine-id)
         (re-frame/dispatch [:app/bootstrap target-user-id engine-id])
         (re-frame/dispatch [:auth/set-status :logged-out]))))
   {}))

(re-frame/reg-event-fx
 :sdk/ignite-session
 (fn [_ _]
   {:fx [[:dispatch [:sdk/start-sync]]
         [:dispatch [:sdk/fetch-own-profile]]
         [:dispatch [:sdk/fetch-all-emotes]]]}))

(defn ^:export init []
  (binding/init-middleware!)
  (init-worker!)
  (app/init)
  (logger/init!)
  (log/debug  "Entering Paradise!")
  )
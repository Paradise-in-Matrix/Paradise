(ns client.core
  (:require
   [utils.logger :as logger]
   [re-frame.core :as re-frame]
   [app :as app]
   [client.state :as state]
   [cljs-workers.core :as main]
   [cljs.core.async :refer [go <!]]
   [taoensso.timbre :as log]))

(defn handle-worker-stream! [data]
  (let [msg-type (:type data)]
    (case msg-type
      "home-rooms-diff"      (re-frame/dispatch [:room-list/set-home-rooms-sync (:rooms data)])
      "bg-rooms-diff"        (re-frame/dispatch [:room-list/set-bg-rooms-sync (:rooms data)])
      "global-spaces-diff"   (re-frame/dispatch [:sdk/set-spaces-list-sync (:spaces data)])
      "space-rooms-diff"     (re-frame/dispatch [:sdk/update-space-view (:space-id data) (:rooms data)])
      "room-parent-resolved" (re-frame/dispatch [:rooms/apply-parent-resolution (:room-id data) (:first-parent-id data)])
      "room-preview-resolved"  (re-frame/dispatch [:rooms/set-preview (:room-id data) (:preview data)])
      "timeline-diff"
      (let [source (keyword (:source data))
            events (mapv (fn [e]
                           (-> e
                               (assoc :timeline-source source)
                               (update :type keyword)))
                         (:events data))]
        (re-frame/dispatch [:sdk/update-timeline (:room-id data) source events]))

      "widget-message"    (re-frame/dispatch [:call/recv-widget-message (:data data)])
      "recovery-state-update"  (re-frame/dispatch [:sdk/handle-recovery-stream (keyword (:state data))])
      "timeline-loading"  (re-frame/dispatch [:timeline/set-loading (:room-id data) (:loading? data)])
      "typing-update"     (re-frame/dispatch [:sdk/update-typing-users (:room-id data) (:users data)])
      "pagination-status" (re-frame/dispatch [:sdk/update-pagination-status (:room-id data) (:status data)])
      nil)))

(defn init-worker! []
  (when-not @state/!engine-pool
    (reset! state/!engine-pool
            (main/create-pool 1 "engine.js"
                              {:worker-opts #js {:type "module"}
                               :on-stream handle-worker-stream!}))))

(re-frame/reg-event-fx
 :app/bootstrap
 (fn [_ [_ target-user-id]]
   (log/debug "Bootstrapping:" target-user-id)
   (init-worker!)
   (go
     (let [pool @state/!engine-pool
           init-res (<! (main/do-with-pool! pool {:handler :init-wasm}))]
       (if (= (:status init-res) "success")
         (let [boot-res (<! (main/do-with-pool! pool {:handler :bootstrap
                                                      :arguments {:target-user-id target-user-id}}))]
           (case (:status boot-res)
             "success" (re-frame/dispatch [:auth/login-success boot-res])
             "empty"   (re-frame/dispatch [:auth/set-status :logged-out])
             "error"   (log/error "Bootstrap failed:" (:msg boot-res))))
         (log/error "WASM failed to load!" (:msg init-res)))))
   {}))

(re-frame/reg-event-fx
 :sdk/start-sync
 (fn [_ _]
   (go
     (let [pool @state/!engine-pool
           res  (<! (main/do-with-pool! pool {:handler :start-sync}))]
       (if (= (:status res) "success")
         (log/info "Matrix sync loop started successfully.")
         (log/error "Failed to start sync:" (:msg res)))))
   {}))



(defn ^:export init []
  (app/init)
  (logger/init!)
  (log/debug  "Entering Paradise!")
  )
(ns paradise.virtualizer.base
  (:require [cljs-workers.worker :as worker]
            [paradise.shared.sci-runner.virtualizer :as sci-runner]
            [taoensso.timbre :as log]
            [paradise.virtualizer.state :as state]
            [eve.alpha :as eve]
            [eve.atom :as ea]
            [eve.mem :as mem]
            [re-frame.db :as db]
            [re-frame.core :as re-frame]
            [eve.wasm-mem :as wasm-mem]
            [cognitect.transit :as t]
            [editscript.core :as e]
            [editscript.edit :as edit]
            [eve.deftype-proto.alloc :as alloc]
            [goog.object]
            [paradise.ui.container.timeline.base]
            [paradise.ui.navigation.rooms.room-list]
            [paradise.ui.app]
            [paradise.ui.container.timeline.item :refer [event-tile-render]]
            [paradise.ui.container.members]
            [paradise.virtualizer.parsing :as parsing]
            [paradise.virtualizer.measurements :as measurements]))



(re-frame.loggers/set-loggers! {:warn (fn [& _])})

(def !event-cache (atom {}))
(def !layout-context (atom {}))
(def !room-events (atom {}))
(defonce !applying-remote-patch? (atom false))

(defn recalculate-and-stream! [room-id source]
  (let [raw-events (get-in @!room-events [room-id source])
        ctx        (get @!layout-context room-id {:width 400 :theme {:font "16px sans-serif" :line-height 22.8} :measured {}})
        layout     (measurements/apply-layout raw-events (:width ctx) (:theme ctx) (:measured ctx))
        laid-out   (measurements/strip-layout-metadata (:items layout))
        ast-nodes  (parsing/build-ast-tree laid-out)
        clean-ast  (mapv #(select-keys % [:id :bottom :height :worker-data :type :ts :unread? :read-by]) ast-nodes)
        js-ast     (to-array
                    (map (fn [node]
                           #js {"id"          (:id node)
                                "bottom"      (:bottom node)
                                "height"      (:height node)
                                "type"        (:type node)
                                "ts"          (:ts node)
                                "unread?"     (:unread? node)
                                "read-by"     (to-array (:read-by node []))
                                "worker-data" (:worker-data node)})
                         clean-ast))
        lambda-paths (parsing/extract-lambda-paths js-ast)]
    (worker/stream!
     js/self
     #js {"type"         "timeline-ready"
          "room-id"      room-id
          "source"       source
          "ast-nodes"    js-ast
          "lambda-paths" lambda-paths}
     true)))

(defn trigger-defer-check! []
  (re-frame/clear-subscription-cache!)
  (js/setTimeout
   (fn []
     (let [changed-ids (parsing/check-deferred-components!)]
       (when (seq changed-ids)
         (swap! parsing/!ast-node-cache #(apply dissoc % changed-ids))
         (swap! parsing/!deferred-component-cache #(apply dissoc % changed-ids))
         (doseq [r-id (keys @!room-events) src (keys (get @!room-events r-id))]
           (recalculate-and-stream! r-id src)))))
   0))


(defn process-timeline-redraw! []
  (parsing/flush-ast-cache!)
  (doseq [room-id (keys @!room-events)
          source  (keys (get @!room-events room-id))]
    (recalculate-and-stream! room-id source)))

(defn precompile-and-cache-event [e source room-id]
  (let [eid    (measurements/extract-id e)
        cached (get @!event-cache eid)]
    (if (and cached (= (:raw cached) e))
      cached
      (let [new-event (parsing/precompile-event-data e source room-id)]
        (swap! !event-cache assoc eid (assoc new-event :raw e))
        new-event))))


(defn process-timeline-events [events source room-id]
  (mapv #(precompile-and-cache-event % source room-id) events))


(worker/register :bind-app-db
  (fn [{:keys [eve-payload ports]}]
    (let [mode          (keyword (:mode eve-payload))
          initial-state (:initial-state eve-payload)
          db-port       (first ports)]
      (cond
        (= mode :async)
        (let [reader    (t/reader :json)
              writer    (t/writer :json)
              start-db  (if initial-state (t/read reader initial-state) {})
              !local-db (atom start-db)]
          (set! (.-onmessage db-port)
                (fn [msg]
                  (let [edits (t/read reader (.-data msg))]
                    (reset! !applying-remote-patch? true)
                    (try
                      (swap! !local-db e/patch (edit/edits->script edits))
                      (finally
                        (reset! !applying-remote-patch? false))))))

          (add-watch !local-db :unified-ast-sync
                     (fn [_ _ old-state new-state]
                       (when-not @!applying-remote-patch?
                         (let [edits (e/get-edits (e/diff old-state new-state {:algo :quick}))]
                           (when (seq edits)
                             (.postMessage db-port (t/write writer edits)))))
                       (re-frame/clear-subscription-cache!)
                       (js/setTimeout
                        (fn []
                          (let [changed-ids (parsing/check-deferred-components!)]
                            (when (seq changed-ids)
                              (swap! parsing/!ast-node-cache #(apply dissoc % changed-ids))
                              (swap! parsing/!deferred-component-cache #(apply dissoc % changed-ids))
                              (doseq [r-id (keys @!room-events) src (keys (get @!room-events r-id))]
                                (recalculate-and-stream! r-id src)))))
                        0)))

          (db/set-eve-atom! !local-db)
          {:status :db-bound-async})

        :else
        (let [root-sab      (:root-sab eve-payload)
              rmap-sab      (:rmap-sab eve-payload)
              slab-sabs     (:slab-sabs eve-payload)
              atom-slot-idx (:atom-slot-idx eve-payload)]
          (alloc/init-worker-slabs! slab-sabs root-sab nil)
          (let [root-r       (mem/js-sab-region root-sab)
                rmap-r       (mem/js-sab-region rmap-sab)
                slot-idx     (ea/register-worker! {:root-r root-r} 2)
                domain-state {:root-r root-r :rmap-r rmap-r :base-path nil
                              :slot-idx slot-idx :retire-q (atom [])
                              :flush-ts (doto (make-array 1) (aset 0 0))}
                eve-atom     (ea/->MmapAtom domain-state atom-slot-idx)]

            (add-watch eve-atom :timeline-sync-watch
                       (fn [_ _ old-state new-state]
                         (re-frame/clear-subscription-cache!)
                         (js/setTimeout
                          (fn []
                            (let [changed-ids (parsing/check-deferred-components!)]
                              (when (seq changed-ids)
                                (swap! parsing/!ast-node-cache #(apply dissoc % changed-ids))
                                (swap! parsing/!deferred-component-cache #(apply dissoc % changed-ids))
                                (doseq [r-id (keys @!room-events) src (keys (get @!room-events r-id))]
                                  (recalculate-and-stream! r-id src)))))
                          0)))

            (db/set-eve-atom! eve-atom)
            (js/setInterval #(ea/update-heartbeat! domain-state slot-idx) 5000)
            {:status :db-bound-sab}))))))

(worker/register :recalculate-timeline
                 (fn [{:keys [room-id]}]
                   (let [sources (keys (get @!room-events room-id))]
                     (doseq [source sources]
                       (recalculate-and-stream! room-id source))
                     {:status :success})))


(worker/register :set-viewport
                 (fn [{:keys [visible-ids]}]
                   (reset! parsing/!visible-active-ids (set visible-ids))
                   (trigger-defer-check!)
                   {:status :success}))

(worker/register :set-scrolling-state
                 (fn [{:keys [scrolling?]}]
                   (reset! parsing/!is-scrolling? scrolling?)
                   (when (and (not scrolling?) @parsing/!pending-defer-check?)
                     (reset! parsing/!pending-defer-check? false)
                     (trigger-defer-check!))
                   {:status :success}))

;; still using old call form
;; need to globally update all to use mesh form
(worker/register :update-layout-context
                 (fn [{:keys [arguments]}]
                   (let [{:keys [room-id width theme measured]} arguments
                         old-ctx (get @!layout-context room-id)]
                     (when (or (not= width (:width old-ctx)) (not= theme (:theme old-ctx)))
                       (reset! measurements/!pretext-cache {}))

                     (swap! !layout-context assoc room-id {:width width :theme theme :measured measured})
                     (let [sources (keys (get @!room-events room-id))]
                       (doseq [source sources]
                         (recalculate-and-stream! room-id source)))

                     {:status :success})))

(worker/register :process-timeline-diff
                 (fn [{:keys [events room-id source]}]
                   (let [processed-events (process-timeline-events events source room-id)
                         enriched-events  (measurements/enrich-timeline-items processed-events)]
                     (swap! !room-events assoc-in [room-id source] enriched-events)
                     (recalculate-and-stream! room-id source)
                     {:status :success})))

(worker/register :recalculate-items
                 (fn [{:keys [room-id item-ids]}]
                   (doseq [id item-ids]
                     (swap! parsing/!item-revisions update id (fnil inc 0))
                     (swap! parsing/!ast-node-cache dissoc id)
                     (swap! parsing/!deferred-component-cache dissoc id))
                   (doseq [src (keys (get @!room-events room-id))]
                     (recalculate-and-stream! room-id src))
                   {:status :success}))


(worker/register :eval-virtualizer-plugin
  (fn [{:keys [plugin-id code]}]
      (sci-runner/eval-virtualizer-plugin! plugin-id code)))

(worker/bootstrap)
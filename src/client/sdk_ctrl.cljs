(ns client.sdk-ctrl
  (:require [client.state :refer [sdk-world]]
            [promesa.core :as p]
            [taoensso.timbre :as log]
            ["ffi-bindings" :as sdk])
  (:require-macros [utils.macros :refer [ocall oget]]))

(declare room-update-handler)

(declare loading-state-handler)


  (defonce has-setup-entries? (atom false))
  (defonce diff-queue (atom (p/resolved nil)))

(defn setup-entries! [rls]
  (when-not @has-setup-entries?
    (reset! has-setup-entries? true)
    (log/info "State is LOADED. Initializing Entries...")
    (let [FK (.. sdk -RoomListEntriesDynamicFilterKind)]
      (p/let [res (ocall rls :entriesWithDynamicAdapters 200 #js {:onUpdate room-update-handler})
              ctrl (ocall res :controller)]
        (swap! sdk-world assoc :ctrl ctrl)
        (swap! sdk-world assoc-in [:handles :entries] res)
        (let [filter (new (.-All FK)
                          #js {:filters #js [(new (.-NonLeft FK))
                                             (new (.-DeduplicateVersions FK))]})]
          (ocall ctrl :setFilter filter))
        (ocall ctrl :addOnePage)))))



(defn loading-state-handler [s rls]
  (let [tag (.-tag ^js s)]
    (log/debug "State Monitor:" tag)
    (case tag
      "NotLoaded" (swap! sdk-world assoc :loading? true)
      "Loaded"    (do (swap! sdk-world assoc :loading? false)
                      (setup-entries! rls))
      nil)))

(defn setup-room-list! [rls]
  (let [res (ocall rls :loadingState #js {:onUpdate #(loading-state-handler % rls)})
        state (oget res :state)]
    (swap! sdk-world assoc-in [:handles :loading] res)
    (loading-state-handler state rls)))
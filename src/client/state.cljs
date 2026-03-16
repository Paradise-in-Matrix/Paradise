  (ns client.state
    (:require [reagent.core :as r]
              [clojure.string :as str]
              [cljs.spec.alpha :as s]
              [taoensso.timbre :as log]

              )
    (:require-macros [utils.macros :refer [ocall oget]]))

(defprotocol IMatrixClient
  "Structured access to the Matrix Rust SDK Client"
  (get-sync-service [this])
  (get-rooms [this])
  (get-user-id [this])
  (is-alive? [this]))

(defprotocol IDisposable
  "Lifecycle management for WASM memory"
  (dispose! [this]))

(deftype MatrixClient [raw-client]
  IMatrixClient
  (get-sync-service [_] (ocall raw-client :syncService))
  (get-rooms [_] (ocall raw-client :rooms))
  (get-user-id [_] (ocall raw-client :userId))
  (is-alive? [_] (not (nil? raw-client)))

  IDisposable
  (dispose! [_]
    (try
      (ocall raw-client :uniffiDestroy)
      (log/debug "WASM Client Destroyed")
      (catch js/Error _ nil)))
  Object
  (toString [_] (str "#<MatrixClient " (try (ocall raw-client :userId) (catch js/Error _ "unknown")) ">")))

(defrecord SDKWorld [client rooms active-room ctrl handles loading?])

(s/def ::matrix-client #(satisfies? IMatrixClient %))
(s/def ::sdk-world (s/keys :req-un [::matrix-client]))

(defn make-world
  "Factory to ensure we never inject raw WASM into the state."
  [params]
  (let [new-world (map->SDKWorld params)]
    (if (s/valid? ::sdk-world new-world)
      new-world
      (throw (js/Error. (str "Invalid SDK World State: " (s/explain-str ::sdk-world new-world)))))))

(defonce sdk-world
  (r/atom (map->SDKWorld
            {:client nil
             :rooms []
             :spaces []
             :active-room nil
             :ctrl nil
             :i18n nil
             :handles {:entries nil :loading nil}
             :loading? true})))

(add-watch sdk-world :type-checker
           (fn [_ _ _ new-state]
             (let [c (:client new-state)]
               (when (and c (not (satisfies? IMatrixClient c)))
                 (log/error "TYPE VIOLATION: Raw WASM object detected in sdk-world!
                           Did you forget to wrap it in (MatrixClient. obj)?")))))



(defn tl
  "Translates a key using the global SDK i18n instance."
  ([key] (t key nil))
  ([key opts]
   (if-let [api (:i18n @sdk-world)]
     (.translate api key (clj->js opts))
     key)))

(defn hook-vm!
  "Connects an npm ViewModel to our Reagent world."
  [vm atom-path]
  (let [sync-fn #(swap! sdk-world assoc-in atom-path (.getSnapshot vm))
        _ (sync-fn)
        unsub (.subscribe vm sync-fn)]
    (fn []
      (unsub)
      (.dispose vm))))

(defn mount-vm!
  "Plugs an SDK ViewModel into our global brain."
  [id raw-vm]
  (let [
        update-snap! (fn []
                       (let [snap (.getSnapshot raw-vm)
                             clj-data (js->clj snap :keywordize-keys true)]
                         (swap! sdk-world assoc-in [:snapshots id] clj-data)))
        unsub (.subscribe raw-vm update-snap!)]
    (update-snap!)
    (swap! sdk-world assoc-in [:vms id]
           {:instance raw-vm
            :unsub unsub
            :dispose (fn []
                       (unsub)
                       (.dispose raw-vm))})
    (log/debug (str "Mounted VM: " id))))

(defn unmount-vm!
  "Safely unplugs a ViewModel and clears its memory."
  [id]
  (when-let [vm-map (get-in @sdk-world [:vms id])]
    ((:dispose vm-map))
    (swap! sdk-world update :vms dissoc id)
    (swap! sdk-world update :snapshots dissoc id)
    (log/debug (str "Unmounted VM: " id))))

(defn ^:export debug-world []
    (let [data (clj->js @sdk-world)]
      (log/debug "SDK WORLD DUMP:" data)
      data))

(defn set-client! [raw-wasm-obj]
  (when-let [old-client (:client @sdk-world)]
    (dispose! old-client))
  (swap! sdk-world assoc :client (MatrixClient. raw-wasm-obj)))

(set! (.-dump js/window) debug-world)
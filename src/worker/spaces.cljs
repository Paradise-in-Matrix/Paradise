(ns worker.spaces
  (:require
   [cljs-workers.worker :as worker]
   [promesa.core :as p]
   [taoensso.timbre :as log]
   [client.diff-handler :refer [apply-matrix-diffs]]
   [worker.state :as state]
   [worker.rooms :refer [process-parent-queue!]]
   [cljs.core.async :refer [<!]]
   [cljs.core.async.interop :refer-macros [<p!]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

(defonce !space-service (atom nil))
(defonce !global-space-sub (atom nil))
(defonce !space-list-subs (atom {}))

(defonce !global-spaces-array (atom #js []))
(defonce !global-space-mutex (atom (p/resolved nil)))

(defonce !space-rooms-arrays (atom {}))
(defonce !space-rooms-mutexes (atom {}))

(defn parse-space-child [obj]
  (let [id       (or (.-roomId obj) (.-id obj))
        name     (or (.-displayName obj) (.-name obj) "Unknown")
        type-tag (some-> obj .-roomType .-tag)]
    (when id
      {:id         id
       :name       name
       :avatar-url (.-avatarUrl obj)
       :is-space?  (= type-tag "Space")
       })))

(defn boot-space-list! [space-id]
  (when-not (get @!space-list-subs space-id)
    (-> (.spaceRoomList @!space-service space-id)
        (p/then (fn [space-list]
                  (let [listener #js {:onUpdate #(apply-space-rooms-diffs! space-id %)}]
                    (try
                      (let [sub-handle (.subscribeToRoomUpdate space-list listener)]
                        (swap! !space-list-subs assoc space-id {:list space-list :sub sub-handle})
                        (.paginate space-list))
                      (catch :default e
                        (log/error "FFI Space Subscription Panic:" e))))))
        (p/catch (fn [err]
                   (log/error "Failed to boot space list for" space-id ":" err))))))


(defn apply-space-rooms-diffs! [space-id updates]
  (swap! !space-rooms-mutexes update space-id #(or % (atom (p/resolved nil))))
  (let [!mutex (get @!space-rooms-mutexes space-id)]
    (swap! !mutex
           (fn [prev-promise]
             (p/then prev-promise
                     (fn []
                       (let [current-rooms (get @!space-rooms-arrays space-id #js [])]
                         (-> (apply-matrix-diffs current-rooms updates parse-space-child)
                             (p/then (fn [next-rooms]
                                       (swap! !space-rooms-arrays assoc space-id next-rooms)
                                       (worker/stream! {:type "space-rooms-diff"
                                                        :space-id space-id
                                                        :rooms next-rooms})))
                             (p/catch #(log/error "Space rooms diff panic:" %))))))))))


(defn apply-global-spaces-diffs! [updates]
  (swap! !global-space-mutex
         (fn [prev-promise]
           (p/then prev-promise
                   (fn []
                     (-> (apply-matrix-diffs @!global-spaces-array updates parse-space-child)
                         (p/then (fn [next-spaces]
                                   (reset! !global-spaces-array next-spaces)
                                   (worker/stream! {:type "global-spaces-diff"
                                                    :spaces next-spaces})
                                   (doseq [s next-spaces]
                                     (when (:id s)
                                       (boot-space-list! (:id s))))))
                         (p/catch #(log/error "Global spaces diff panic:" %))))))))

(defn init-space-service! [client]
  (p/let [space-service (.spaceService client)
          _             (reset! !space-service space-service)
          listener      #js {:onUpdate #(apply-global-spaces-diffs! %)}
          sub           (.subscribeToTopLevelJoinedSpaces space-service listener)]
    (reset! !global-space-sub sub)
    (process-parent-queue! client space-service)))

(worker/register :paginate-space
  (fn [{:keys [space-id]}]
    (if-let [space-list (get-in @!space-list-subs [space-id :list])]
      (do (.paginate space-list)
          {:status :success})
      {:status :error :msg "Space list not booted"})))
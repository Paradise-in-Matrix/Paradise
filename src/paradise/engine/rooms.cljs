(ns paradise.engine.rooms
  (:require
   [cljs-workers.worker :as worker]
   [clojure.string :as str]
   [promesa.core :as p]
   [taoensso.timbre :as log]
   ["ffi-bindings" :as sdk :refer [RoomListEntriesDynamicFilterKind RoomListFilterCategory]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [paradise.shared.client.diff-handler :refer [apply-matrix-diffs]]
   [net :as net]
   [paradise.engine.state :as state])
  (:require-macros
   [paradise.shared.utils.macros :refer [ocall oget]]
   [cljs.core.async.macros :refer [go]]))

(defonce !ui-controller (atom nil))
(defonce !bg-controller (atom nil))

(defonce !ui-list-result (atom nil))
(defonce !bg-list-result (atom nil))

(defonce !home-rooms (atom #js []))
(defonce !home-mutex (atom (p/resolved nil)))

(defonce !bg-rooms-array (atom #js []))
(defonce !bg-room-mutex (atom (p/resolved nil)))

(defonce !parent-queue (atom {}))
(defonce !parent-cache (atom {}))

(defonce !preview-promises (atom {}))



(defn build-room-summary [room room-info latest-event]
  (let [
        num-notifications (js/Number (oget room-info :numUnreadNotifications))
        num-mentions      (js/Number (oget room-info :numUnreadMentions))
        num-unread        (js/Number (oget room-info :numUnreadMessages))
        membership        (oget room-info :membership)
        invited           (= membership "Invited")
        is-marked-unread  (oget room-info :isMarkedUnread)
        notification-state
        #js {:isMention                    (> num-mentions 0)
             :isNotification               (or (> num-notifications 0) is-marked-unread)
             :isActivityNotification       (and (> num-unread 0) (<= num-notifications 0))
             :hasAnyNotificationOrActivity (or (> num-unread 0) (> num-notifications 0) invited is-marked-unread)
             :invited                      invited}
        display-name (or (some-> (oget room-info :displayName) str/trim)
                         (oget room-info :id))
        avatar-url (oget room-info :avatarUrl)]
    #js {:room                       room
         :id                         (oget room-info :id)
         :name                       display-name
         :avatar                     avatar-url
         ;; TODO Add message preview handling here
         :messagePreview             nil
         :pinnedEventIds               (oget room-info :pinnedEventIds)
         :activeRoomCallParticipants (oget room-info :activeRoomCallParticipants)
         :showNotificationDecoration (oget notification-state :hasAnyNotificationOrActivity)
         :notificationState          notification-state
         :hasRoomCall       (boolean (oget room-info :hasRoomCall))
         :isBold                     (oget notification-state :hasAnyNotificationOrActivity)
         :unreadMessagesCount        num-unread
         :unreadMentionsCount        num-mentions
         :unreadNotificationsCount   num-notifications
         :membership                 membership
         :isDirect                   (oget room-info :isDirect)
         :isSpace                    (oget room-info :isSpace)
         :isFavourite                (oget room-info :isFavourite)
         :isMarkedUnread             is-marked-unread
         }))

(defn enqueue-parent-check! [room-id]
  (swap! !parent-queue update room-id #(if % % 0)))


(defn process-parent-queue! [client space-service]
  (let [queue @!parent-queue]
    (when (and space-service (seq queue))
      (-> (p/all
           (map (fn [[id attempts]]
                  (if-let [room (some #(when (= (:id %) id) %) @!bg-rooms-array)]
                    (-> (.joinedParentsOfChild space-service id)
                        (p/then (fn [parents]
                                  (if (pos? (alength parents))
                                    {:id id :status :success :parents parents :room room}
                                    {:id id :status :retry :attempts (inc attempts)})))
                        (p/catch (fn [e]
                                   (js/console.error "Queue FFI fail for" id e)
                                   {:id id :status :retry :attempts (inc attempts)})))
                    (p/resolved {:id id :status :drop})))
                queue))
          (p/then (fn [results]
                    (doseq [res results]
                      (when (= (:status res) :success)
                        (let [parents (:parents res)
                              first-parent-id (.-roomId (aget parents 0))]

                          (swap! !parent-cache assoc (:id res)
                                 {:parents parents
                                  :first-parent-id first-parent-id})

                          (swap! !bg-rooms-array
                                 (fn [rooms]
                                   (mapv (fn [r]
                                           (if (= (:id r) (:id res))
                                             (assoc r :first-parent-id first-parent-id)
                                             r))
                                         rooms)))

                          (worker/stream! {:type "room-parent-resolved"
                                           :room-id (:id res)
                                           :first-parent-id first-parent-id}))))

                    (let [to-keep (reduce (fn [acc res]
                                            (if (and (= (:status res) :retry) (< (:attempts res) 3))
                                              (assoc acc (:id res) (:attempts res))
                                              acc))
                                          {} results)]
                      (reset! !parent-queue to-keep)
                      (when (seq to-keep)
                        (js/setTimeout #(process-parent-queue! client space-service) 2000)))))))))

(defn- extract-preview [preview-ffi]
  (when preview-ffi
    (let [is-call? (try
                     (let [info (.info preview-ffi)
                           type (some-> info .-roomType)]
                       (boolean
                        (and (= "Custom" (some-> type .-tag))
                             (= "org.matrix.msc3417.call" (some-> type .-inner .-value)))))
                     (catch :default _ false))]
      {:is-call? is-call?})))


(worker/register :get-room-preview
                 (fn [{:keys [room-id]}]
                   (go
                     (try
                       (if-let [preview-p (get @!preview-promises room-id)]
                         (let [preview-ffi (<p! preview-p)]
                           (if preview-ffi
                             {:status :success :preview (extract-preview preview-ffi)}
                             {:status :error :msg "Preview resolved to nil"}))
                         {:status :error :msg "No preview promise retained for this room"})
                       (catch :default e
                         {:status :error :msg (str e)})))))

(defn parse-room [client space-service room-interface]
  (p/let [room-info     (if (fn? (.-roomInfo room-interface)) (.roomInfo room-interface) nil)
          latest-event  (.latestEvent room-interface)
          room-id       (if room-info (.-id room-info) "unknown-room")]
    (try
      (let [_ (when (and client (exists? (.-getRoomPreviewFromRoomId client)) (not= room-id "unknown-room"))
                (when-not (contains? @!preview-promises room-id)
                  (let [preview-p (try
                                    (p/promise (.getRoomPreviewFromRoomId client room-id #js []))
                                    (catch :default e
                                      (p/resolved nil)))]
                    (swap! !preview-promises assoc room-id preview-p)
                    (-> preview-p
                        (p/then (fn [ffi]
                                  (when-let [clean-preview (extract-preview ffi)]
                                    (worker/stream! {:type "room-preview-resolved"
                                                     :room-id room-id
                                                     :preview clean-preview}))))
                        (p/catch #(log/error "Background preview fetch failed:" %))))))
            summary       (build-room-summary room-interface room-info latest-event)
            cached-parent (get @!parent-cache room-id)
            first-parent  (:first-parent-id cached-parent)]
        (-> (js->clj summary :keywordize-keys true)
            (assoc :id room-id)
            (cond-> first-parent (assoc :first-parent-id first-parent))))
      (catch :default e
        (log/error "Failed to parse room:" room-id e)
        {:id room-id :name "Error parsing room"}))))


(defn get-rust-filter [filter-id]
  (case filter-id
    "unread"
    (RoomListEntriesDynamicFilterKind.All.
     #js {:filters #js [(RoomListEntriesDynamicFilterKind.Unread.)
                        (RoomListEntriesDynamicFilterKind.DeduplicateVersions.)]})
    "people"
    (RoomListEntriesDynamicFilterKind.All.
     #js {:filters #js [(RoomListEntriesDynamicFilterKind.Category. #js {:expect RoomListFilterCategory.People})
                        (RoomListEntriesDynamicFilterKind.Joined.)
                        (RoomListEntriesDynamicFilterKind.DeduplicateVersions.)]})
    "favourite"
    (RoomListEntriesDynamicFilterKind.All.
     #js {:filters #js [(RoomListEntriesDynamicFilterKind.Favourite.)
                        (RoomListEntriesDynamicFilterKind.DeduplicateVersions.)]})

    "invites"
    (RoomListEntriesDynamicFilterKind.All.
     #js {:filters #js [(RoomListEntriesDynamicFilterKind.Invite.)
                        (RoomListEntriesDynamicFilterKind.DeduplicateVersions.)]})
    "other"
    (RoomListEntriesDynamicFilterKind.All.
     #js {:filters #js [(RoomListEntriesDynamicFilterKind.Category. #js {:expect RoomListFilterCategory.Group})
                        (RoomListEntriesDynamicFilterKind.Joined.)
                        (RoomListEntriesDynamicFilterKind.DeduplicateVersions.)]})

    (RoomListEntriesDynamicFilterKind.All.
     #js {:filters #js [(RoomListEntriesDynamicFilterKind.Joined.)
                        (RoomListEntriesDynamicFilterKind.DeduplicateVersions.)]})))


(worker/register :set-room-filter
  (fn [{:keys [filter-id]}]
    (if-let [ctrl @!ui-controller]
      (do
        (.setFilter ctrl (get-rust-filter filter-id))
        (.addOnePage ctrl)
        {:status :success})
      {:status :error :msg "Controller not initialized"})))

(defn apply-home-diffs-async! [client space-service updates]
  (swap! !home-mutex
         (fn [prev-promise]
           (p/then prev-promise
                   (fn []
                     (-> (apply-matrix-diffs @!home-rooms updates #(parse-room client space-service %))
                         (p/then (fn [next-rooms]
                                   (reset! !home-rooms next-rooms)
                                   (worker/stream! {:type "home-rooms-diff" :rooms next-rooms})))
                         (p/catch (fn [err] (log/error "Global Diff Panic:" err)))))))))


(defn apply-bg-rooms-diffs! [client space-service updates]
  (swap! !bg-room-mutex
         (fn [prev-promise]
           (p/then prev-promise
                   (fn []
                     (-> (apply-matrix-diffs @!bg-rooms-array updates #(parse-room client space-service %))
                         (p/then (fn [next-rooms]
                                   (reset! !bg-rooms-array next-rooms)
                                   (worker/stream! {:type "bg-rooms-diff" :rooms next-rooms})

                                   (doseq [r next-rooms]
                                     (when-not (:first-parent-id r)
                                       (enqueue-parent-check! (:id r))))
                                   (process-parent-queue! client space-service)))
                         (p/catch (fn [err] (log/error "Global Diff Panic:" err)))))))))

(defn start-room-list-sync! [client room-list space-service]
  (p/let [ui-result (.entriesWithDynamicAdapters room-list 200
                                                     #js {:onUpdate #(apply-home-diffs-async! client space-service %)})
          ui-controller (.controller ui-result)
          bg-result (.entriesWithDynamicAdapters room-list 200
                                                     #js {:onUpdate #(apply-bg-rooms-diffs! client space-service %)})
          bg-controller (.controller bg-result)]
    (reset! !ui-controller ui-controller)
    (reset! !bg-controller bg-controller)

    (reset! !ui-list-result ui-result)
    (reset! !bg-list-result bg-result)

    (.setFilter ui-controller (get-rust-filter "people"))
    (.addOnePage ui-controller)

    (.setFilter bg-controller (get-rust-filter "all"))
    (.addOnePage bg-controller)))



(worker/register :paginate-room-list
  (fn [_]
    (if-let [ctrl @!ui-controller]
      (do (.addOnePage ctrl) {:status :success})
      {:status :error :msg "Controller not initialized"})))





(worker/register :fetch-space-hierarchy
                 (fn [{:keys [space-id]}]
                   (go
                     (try
                       (let [client     @state/!client
                             base-url   (.homeserver client)
                             session    (.session client)
                             token      (.-accessToken session)
                             clean-base (str/replace base-url #"/+$" "")
                             url        (str clean-base "/_matrix/client/v1/rooms/" space-id "/hierarchy")
                             resp       (<p! (net/fetch url #js {:headers #js {"Authorization" (str "Bearer " token)}}))
                             json       (<p! (.json resp))]
                         (if (.-ok resp)
                           {:status :success :rooms (:rooms (js->clj json :keywordize-keys true))}
                           {:status :error :msg (str "Hierarchy fetch failed: " (.-status resp))}))
                       (catch :default e
                         {:status :error :msg (str e)})))))


(worker/register :fetch-room-membership
                 (fn [{:keys [room-id]}]
                   (go
                     (if-let [client @state/!client]
                       (try
                         (if-let [room (.getRoom client room-id)]
                           (let [mem-enum (.membership room)
                                 status (case mem-enum
                                          0 "invited"
                                          1 "joined"
                                          2 "left"
                                          3 "knocked"
                                          4 "banned"
                                          "unknown")]
                             {:status "success" :room-id room-id :membership status})
                           {:status "error" :msg "Room not found in local cache"})
                         (catch :default e
                           {:status "error" :msg (str e)}))
                       {:status "error" :msg "No active client"}))))

(worker/register :fetch-room-security
                 (fn [{:keys [room-id]}]
                   (go
                     (if-let [client @state/!client]
                       (try
                         (if-let [room (.getRoom client room-id)]
                           (let [is-public (.isPublic room)
                                 is-enc    (<p! (.isEncrypted room))]
                             {:status "success"
                              :room-id room-id
                              :is-public is-public
                              :is-encrypted is-enc})
                           {:status "error" :msg (str "getRoom returned null for " room-id)})
                         (catch :default e
                           {:status "error" :msg (str e)}))
                       {:status "error" :msg "Client not initialized"}))))


(worker/register :create-room
  (fn [{:keys [name topic visibility is-encrypted history-visibility is-space]}]
    (go
      (if-let [client @state/!client]
        (try
          (let [vis-enum  (if (= visibility "Public")
                            (.new (.-Public (.-RoomVisibility sdk)))
                            (.new (.-Private (.-RoomVisibility sdk))))
                preset    (if (= visibility "Public")
                            (.-PublicChat (.-RoomPreset sdk))
                            (.-PrivateChat (.-RoomPreset sdk)))
                hist-enum (case history-visibility
                            "Invited"       (.new (.-Invited (.-RoomHistoryVisibility sdk)))
                            "Joined"        (.new (.-Joined (.-RoomHistoryVisibility sdk)))
                            "WorldReadable" (.new (.-WorldReadable (.-RoomHistoryVisibility sdk)))
                            (.new (.-Shared (.-RoomHistoryVisibility sdk))))
                topic-val (if (str/blank? topic) js/undefined topic)

                params    (.create (.-CreateRoomParameters sdk)
                                   #js {:name                      name
                                        :topic                     topic-val
                                        :isEncrypted               (boolean is-encrypted)
                                        :isDirect                  false
                                        :visibility                vis-enum
                                        :preset                    preset
                                        :historyVisibilityOverride hist-enum
                                        :isSpace                   (boolean is-space)})
                room-id   (<p! (.createRoom client params))]
            {:status "success" :room-id room-id})
          (catch :default e
            {:status "error" :msg (str e)}))
        {:status "error" :msg "No active client"}))))


(worker/register :join-room
                (fn [{:keys [room-id]}]
                   (go
                     (if-let [client @state/!client]
                       (try
                         (if-let [room (.getRoom client room-id)]
                           (<p! (.join room))
                           (<p! (.joinRoomById client room-id)))

                         {:status "success" :room-id room-id}
                         (catch :default e
                           {:status "error" :msg (str e)}))
                       {:status "error" :msg "No active client"}))))



(worker/register :leave-room
  (fn [{:keys [room-id]}]
    (go
      (if-let [client @state/!client]
        (try
          (if-let [room (.getRoom client room-id)]
            (do
              (<p! (.leave room))
              {:status "success" :room-id room-id})
            {:status "error" :msg "Room not found in local cache"})
          (catch :default e
            {:status "error" :msg (str e)}))
        {:status "error" :msg "No active client"}))))

(worker/register :invite-user
  (fn [{:keys [room-id user-id]}]
    (go
      (if-let [client @state/!client]
        (try
          (if-let [room (.getRoom client room-id)]
            (do
              (<p! (.inviteUserById room user-id))
              {:status "success" :room-id room-id :user-id user-id})
            {:status "error" :msg "Room not found in local cache"})
          (catch :default e
            {:status "error" :msg (str e)}))
        {:status "error" :msg "No active client"}))))

(worker/register :knock-room
                 (fn [{:keys [room-id]}]
                   (go
                     (if-let [client @state/!client]
                       (try
                         (<p! (.knock client room-id js/undefined #js []))
                         {:status "success" :room-id room-id}
                         (catch :default e
                           {:status "error" :msg (str e)}))
                       {:status "error" :msg "No active client"}))))

(worker/register :copy-room-link
                 (fn [{:keys [room-id]}]
                   (go
                     (if-let [client @state/!client]
                       (try
                         (if-let [room (.getRoom client room-id)]
                           (let [link (<p! (.matrixToPermalink room))]
                             {:status "success" :link link})
                           {:status "error" :msg "Room not found in local cache"})
                         (catch :default e
                           {:status "error" :msg (str e)}))
                       {:status "error" :msg "No active client"}))))
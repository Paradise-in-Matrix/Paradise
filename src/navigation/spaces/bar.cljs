(ns navigation.spaces.bar
  (:require [promesa.core :as p]
            [re-frame.core :as re-frame]
            [re-frame.db :as db]
            [reagent.core :as r]
            ["react-virtuoso" :refer [Virtuoso]]
            [client.state :as state :refer [sdk-world]]
            [navigation.rooms.room-list :refer [process-parent-queue!]]
            [client.diff-handler :refer [apply-matrix-diffs apply-generic-diffs!]]
            [overlays.settings :refer [sidebar-profile-mini]]
            [utils.svg :as icons]
            [utils.helpers :refer [mxc->url]]
            [utils.global-ui :refer [avatar]]
            [taoensso.timbre :as log]))

(defn parse-space-child [obj]
  (let [id   (or (.-roomId obj) (.-id obj))
        name (or (.-displayName obj) (.-name obj) "Unknown")
        type-tag (some-> obj .-roomType .-tag)
        ]
    (if-not id
      nil
      {:id        id
       :name      name
       :avatar-url (.-avatarUrl obj)
       :is-space? (= type-tag "Space")
       :raw-room  obj})))


(defonce !global-space-mutex (atom (p/resolved nil)))

(defn apply-global-spaces-diffs! [updates]
  (apply-generic-diffs!
   {:!mutex      !global-space-mutex
    :db-path     [:spaces-list]
    :parse-fn
    #(parse-space-child %)
    :sync-event  [:sdk/set-spaces-list-sync]
    :async-event [:sdk/process-spaces]
    :updates     updates}))

(defonce !space-mutexes (atom {}))

(defn apply-space-rooms-diffs! [space-id updates]
  (swap! !space-mutexes update space-id #(or % (atom (p/resolved nil))))
  (apply-generic-diffs!
   {:!mutex      (get @!space-mutexes space-id)
    :db-path     [:space-rooms space-id]
    :parse-fn    #(parse-space-child %)
    :sync-event  [:sdk/update-space-view space-id]
    :async-event nil
    :updates     updates}))


(defn init-space-service! [client]
  (p/let [space-service (.spaceService client)
          _ (re-frame/dispatch [:sdk/set-space-service space-service])
          listener #js {:onUpdate #(apply-global-spaces-diffs! %)}
          sub (.subscribeToJoinedSpaces space-service listener)
          initial-spaces (.joinedSpaces space-service)
          _ (js/console.log initial-spaces)
          _ (re-frame/dispatch [:sdk/set-global-space-sub sub])
          initial-spaces (.joinedSpaces space-service)]
    (process-parent-queue!)))


(re-frame/reg-event-db
 :sdk/save-space-sub
 (fn [db [_ space-id space-list sub-handle]]
   (assoc-in db [:space-subs space-id] {:list space-list :sub sub-handle})))


(re-frame/reg-event-db
 :sdk/set-global-space-sub
 (fn [db [_ sub-handle]]
   (assoc db :global-space-sub sub-handle)))

(re-frame/reg-event-db
 :sdk/set-spaces-list-sync
 (fn [db [_ spaces-vec]]
   (assoc db :spaces-list spaces-vec)))


(re-frame/reg-event-fx
 :sdk/process-spaces
 (fn [{:keys [db]} [_ parsed-spaces-array]]
   (let [new-spaces-map (into {} (for [s parsed-spaces-array
                                       :when (:id s)]
                                   [(:id s) s]))
         existing-subs (or (:space-subs db) {})
         space-service (:space-service db)
         new-space-ids (remove #(contains? existing-subs %) (keys new-spaces-map))]
     {:db (assoc db :spaces new-spaces-map)
      :dispatch-n (for [id new-space-ids]
                    [:sdk/boot-background-space-list space-service id])
      })))

(re-frame/reg-event-fx
 :sdk/boot-background-space-list
 (fn [_ [_ service space-id]]
   (-> (.spaceRoomList service space-id)
       (.then (fn [space-list]
                (let [listener #js {:onUpdate (fn [diffs] (apply-space-rooms-diffs! space-id diffs))}]
                  (try
                    (p/let [sub-handle (.subscribeToRoomUpdate space-list listener)
                            _ (re-frame/dispatch [:sdk/save-space-sub space-list sub-handle])
                            _ (.paginate space-list)
                            
                            ]
                      ;;(apply-space-rooms-diffs! space-id #js {:tag "Reset" :inner initial-rooms})
                      )
                    (catch :default e
                       (js/console.error "FFI Subscription Panic:" e))))))
       (.catch (fn [err]
                 (js/console.error "Failed to boot space list for" space-id ":" err))))
   {}))

(re-frame/reg-event-fx
 :sdk/paginate-space
 (fn [{:keys [db]} [_ space-id]]
   (let [space-sub (get-in db [:space-subs space-id])
         space-list (:list space-sub)]
     (when space-list
       (.paginate space-list))
     {})))

(re-frame/reg-event-fx
 :room-list/paginate-global
 (fn [{:keys [db]} _]
   (when-let [controller (:room-list-controller db)]
     (.addOnePage controller))
   {}))

(re-frame/reg-event-fx
 :sdk/paginate-space-fully
 (fn [{:keys [db]} [_ space-id]]
   (let [space-sub  (get-in db [:space-subs space-id])
         space-list (:list space-sub)]
     (when space-list
       (-> (.paginate space-list)
           (.then (fn [has-more?]
                    (log/debug "Pagination returned:" has-more?)
                    (if has-more?
                      (re-frame/dispatch [:sdk/paginate-space-fully space-id])
                      (log/debug "Fully loaded all rooms for space:" space-id))))
           (.catch (fn [err]
                     (log/error "Pagination panic for space:" space-id err)))))
     {})))

(defn fetch-space-hierarchy [space-id]
  (let [client @(re-frame/subscribe [:sdk/client])
        base-url (.homeserver client)
        token (.-accessToken (.session client))
        url (str base-url "_matrix/client/v1/rooms/" space-id "/hierarchy")]
    (-> (js/fetch url #js {:headers #js {:Authorization (str "Bearer " token)}})
        (p/then #(.json %))
        (p/then (fn [json]
                  (let [resp (js->clj json :keywordize-keys true)]
                    (re-frame/dispatch [:rooms/process-hierarchy space-id (:rooms resp)])))))))

(re-frame/reg-event-fx
 :sdk/fetch-space-hierarchy
 (fn [_ [_ space-id]]
   (fetch-space-hierarchy space-id)
   {}))

(re-frame/reg-sub
 :spaces/all
 (fn [db _]
   (get db :spaces-list [])))

(re-frame/reg-sub
 :spaces/active-id
 (fn [db _]
   (:active-space-id db)))

(re-frame/reg-sub
 :spaces/active-metadata
 :<- [:spaces/active-id]
 :<- [:spaces/all]
 (fn [[active-id all-spaces] _]
   (when active-id
     (some #(when (= (:id %) active-id) %) all-spaces))))

(re-frame/reg-sub
 :space-children/map
 (fn [db _]
   (:space-children db)))


(re-frame/reg-sub
 :space-rooms-map
 (fn [db _]
   (or (:space-rooms db) {})))

(re-frame/reg-sub
 :sdk/space-service
 (fn [db _]
   (:space-service db)))

(re-frame/reg-sub
 :space/children
 :<- [:space-children]
 :<- [:rooms/all]
 (fn [[rel-map all-rooms] [_ parent-id]]
   (let [child-refs (get rel-map parent-id [])
         rich-lookup (into {} (map (fn [r] [(or (:id r) (.-roomId r)) r]) all-rooms))]
     (mapv (fn [{:keys [id is-space?]}]
             (let [rich-room (get rich-lookup id)]
               (merge {:id id :is-space? is-space? :name "Loading..."}
                      rich-room)))
           child-refs))))

(re-frame/reg-event-db
 :sdk/update-space-view
 (fn [db [_ space-id space-items]]
   (let [children-refs (mapv (fn [item]
                               {:id        (or (:id item) (.-roomId item) (.-id item))
                                :is-space? (:is-space? item)})
                             space-items)
         _ (log/debug children-refs)
         ]
     (assoc-in db [:space-children space-id] children-refs))))

(re-frame/reg-event-db
 :sdk/set-space-service
 (fn [db [_ service]]
   (assoc db :space-service service)))

(re-frame/reg-event-fx
 :space/select
 (fn [{:keys [db]} [_ space-id]]
   (let [current-space (:active-space-id db)]
     (if (= current-space space-id)
       (do (process-parent-queue!)
       {})
       {:db (-> db
                (assoc :active-space-id space-id)
                (assoc :active-room-id nil))
        :dispatch-n (if space-id
                      [[:sdk/paginate-space space-id]
                       [:sdk/fetch-room-emotes :space space-id]
                       [:sdk/fetch-space-hierarchy space-id]]
                      [[:room-list/apply-filter "people"]])}))))

(defn space-icon-item [item active-id]
  (let [{:keys [id name avatar-url is-dm?]} item
        stats (if is-dm?
                item
                @(re-frame/subscribe [:space/unread-stats id]))

        {:keys [unread? highlight? mentions]} stats]

    [:div.space-icon-wrapper
     {:class [(when (= id active-id) "active")
              (when unread? "has-unread")
              (when highlight? "has-highlight")
              (when is-dm? "is-dm-entry")]
      :on-click #(if is-dm?
                   (do
                     (re-frame/dispatch [:space/select nil])
                     (re-frame/dispatch [:rooms/select id]))
                   (re-frame/dispatch [:space/select id]))
      :title name}

     (when unread?
       [:div.space-notification-pill])

     [avatar {:id id :name name :url (mxc->url avatar-url) :size 42 :shape :squircle}]

     (when (and mentions (pos? mentions))
       [:div.space-mention-badge mentions])]))



(defn virtualized-space-bar [spaces active-space-id]
  (let [space-array (to-array spaces)]
    [:> Virtuoso
     {:style {:height "100%" :width "100%"}
      :data space-array
      :itemContent
      (fn [index space-map]
        (r/as-element
         ^{:key (:id space-map)}
         [space-icon-item space-map active-space-id]))}]))

(defn spaces-sidebar []
  (let [spaces        @(re-frame/subscribe [:spaces/all])
        active-id     @(re-frame/subscribe [:spaces/active-id])
        active-dms    @(re-frame/subscribe [:rooms/active-dm-pops])
        tr            @(re-frame/subscribe [:i18n/tr])
        ]
    [:div.sidebar-spaces
     [:div.home-group
      [:div.space-icon-wrapper
       {:class (when (nil? active-id) "active")
        :on-click #(re-frame/dispatch [:space/select nil])
        :title (tr [:navigation.spaces.bar/home])}
       [:div.home-icon-container [icons/sun {:animate :sun-pulse :size "32px"}]]]
      (for [dm active-dms]
        ^{:key (:id dm)}
        [space-icon-item dm active-id])]
     [:div.sidebar-divider]
     [:div.spaces-list-container
      [virtualized-space-bar spaces active-id]]
     [sidebar-profile-mini]]))

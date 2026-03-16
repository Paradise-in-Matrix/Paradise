(ns navigation.rooms.entry
  (:require
   [clojure.string :as str]
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [re-frame.db :as db]
   ["react-virtuoso" :refer [Virtuoso]]
   [promesa.core :as p]
   [reagent.core :as r]
   [utils.svg :as icons]
   [reagent.dom.client :as rdom]
   [navigation.rooms.room-summary :refer [build-room-summary]]
   [client.diff-handler :refer [apply-matrix-diffs]]
   [utils.global-ui :refer [avatar long-press-props]]
   ["generated-compat" :as sdk :refer [RoomListEntriesDynamicFilterKind RoomListFilterCategory RoomListLoadingState]])
  (:require-macros [utils.macros :refer [ocall oget]]))

(defn build-room-actions [room-id room-name is-space?]
  [{:id "mark-read"
    :label "Mark as Read"
    :icon "✓"
    :action #(re-frame/dispatch [:rooms/mark-read room-id])}
   {:id "copy-link"
    :label "Copy Link"
    :icon "🔗"
    :action #(js/console.log "Copy permalink for" room-id)}
   {:id "leave"
    :label (if is-space? "Leave Space" "Leave Room")
    :icon "🚪"
    :class-name "danger"
    :action #(re-frame/dispatch [:rooms/leave room-id])}])

(defn media-button [active? icon-on icon-off title on-click color-active color-inactive]
  [:button.media-btn
   {:style {:background "transparent"
            :border "none"
            :padding "6px"
            :cursor "pointer"
            :border-radius "4px"
            :color (if active? color-active color-inactive)
            :transition "background-color 0.2s ease"}
    :title title
    :on-click on-click}
   (if active? icon-on icon-off)])

(defn active-call-panel []
  (let [active-call-id  @(re-frame/subscribe [:call/active-room])
        audio-on?       @(re-frame/subscribe [:call/audio-enabled?])
        video-on?       @(re-frame/subscribe [:call/video-enabled?])
        deafened?       @(re-frame/subscribe [:call/deafened?])
        screen-sharing? @(re-frame/subscribe [:call/screen-sharing?])
        rooms-map       @(re-frame/subscribe [:rooms/unfiltered-indexed-map])
        room-obj        (when active-call-id (get rooms-map active-call-id))
        room-name       (or (when room-obj (aget room-obj "name")) "Active Call")]

    (when active-call-id
      [:div.active-call-panel
       [:div.call-info-row
        [:div.call-status-container
         {:on-click #(re-frame/dispatch [:rooms/select active-call-id])}
         [:span.call-status-label "VOICE CONNECTED"]
         [:span.call-room-name (str "/ " room-name)]]

        [:button.hangup-icon-btn
         {:title "Disconnect"
          :on-click #(re-frame/dispatch [:call/hangup])}
         [icons/phone-hangup]]]

       [:div.call-controls-grid
        [media-button audio-on?
         [icons/mic] [icons/mic-off]
         (if audio-on? "Mute" "Unmute")
         #(re-frame/dispatch [:call/toggle-audio])
         "var(--text-normal)" "#f04747"]

        [media-button (not deafened?)
         [icons/headphones] [icons/headphones-off]
         (if deafened? "Undeafen" "Deafen")
         #(re-frame/dispatch [:call/toggle-deafen])
         "var(--text-normal)" "#f04747"]

        [media-button video-on?
         [icons/video] [icons/video-off]
         (if video-on? "Stop Video" "Start Video")
         #(re-frame/dispatch [:call/toggle-video])
         "var(--text-normal)" "#f04747"]

        [media-button screen-sharing?
         [icons/screen-share] [icons/screen-share-off]
         (if screen-sharing? "Stop Sharing" "Share Screen")
         #(re-frame/dispatch [:call/toggle-screen-share])
         "var(--accent-color)" "var(--text-normal)"]]])))

(defn room-type-call? [db room-id]
  (let [summary (get-in db [:rooms-unfiltered-cache room-id])
        preview (when summary (aget summary "room-preview"))
        info    (some-> preview deref .info)
        type    (some-> info .-roomType)]
    (and (= "Custom" (some-> type .-tag))
         (= "org.matrix.msc3417.call" (some-> type .-inner .-value)))))

(re-frame/reg-sub
 :room/is-call?
 (fn [db [_ room-id]]
   (room-type-call? db room-id)))

(defn room-item [{:keys [id name indent is-space? is-closed? is-call? has-call?
                        active-room unread? highlight? notif-count
                        call-participants avatar-url space-id active-filter open-menu-fn]}]
  (let [active? (= id active-room)]
    [:div.room-container
     (if is-space?
       [:div.room-drawer-header
        (merge {:style {:padding-left (str indent "px")}
                :class (when is-closed? "collapsed")
                :on-click #(re-frame/dispatch [:rooms/toggle-drawer id])
                :on-context-menu #(open-menu-fn % (.-clientX %) (.-clientY %))}
               (long-press-props #(open-menu-fn nil %1 %2)))
        [:span.drawer-arrow (if is-closed? "▶" "▼")]
        [:span.drawer-name (str/upper-case name)]]

       [:div.room-item
        (merge {:style {:padding-left (str indent "px")}
                :class [(when active? "active")
                        (when unread? "has-unread")
                        (when highlight? "has-highlight")]
                :on-click #(re-frame/dispatch [:rooms/select id])
                :on-context-menu #(open-menu-fn % (.-clientX %) (.-clientY %))}
               (long-press-props #(open-menu-fn nil %1 %2)))

        (cond
          is-call?
          [icons/speaker {:has-call? has-call? :style {:margin-right "8px"}}]
          (and (= active-filter "people")
               (not space-id)
;;               avatar-url
               )
          [avatar {:id id :name name :url avatar-url :size 24 :status :online}]
          :else
          [icons/hash])
        [:span.room-name name]
        [:div.room-item-right
         [:div.room-hover-actions
          (when is-call?
            [:div.action-icon.chat-action
             {:title "View Chat & Lobby"
              :on-click (fn [e]
                          (.stopPropagation e)
                          (re-frame/dispatch [:rooms/select id {:force-lobby? true :focus-override :timeline}]))}
             [icons/chat-bubble]])
          [:div.action-icon.settings-action
           {:title "Room Settings"
            :on-click (fn [e]
                        (.stopPropagation e)
                        (re-frame/dispatch [:rooms/open-settings id]))}
           [icons/settings {:animate :spin}]]]
         (when has-call?
           [:div.call-participants.hide-on-desktop
            (for [participant call-participants]
              (let [p-id (or (:userId participant) participant)]
                ^{:key (str "mobile-call-" p-id)}
                [:div.participant-avatar-ring
                 [avatar {:id p-id :url (:avatarUrl participant) :size 20}]]))])

         (when unread?
           [:div.notification-badge
            {:class (when highlight? "highlight")}
            (if (> notif-count 99) "99+" notif-count)])]])
     (when (and has-call? (not is-closed?))
       [:div.desktop-call-list.hide-on-mobile
        {:style {:padding-left (str (+ indent 24) "px")}}
        (for [participant call-participants]
          (let [p-id   (or (:userId participant) participant)
                p-name (or (:displayName participant) p-id)]
            ^{:key (str "desktop-call-" p-id)}
            [:div.call-participant-item
             {:on-click (fn [e]
                          (.stopPropagation e)
                          (re-frame/dispatch [:ui/open-user-profile p-id]))}
             [avatar {:id p-id :name p-name :url (:avatarUrl participant) :size 20}]
             [:span.participant-name p-name]]))])]))


(defn render-room-item [client rooms active-space active-room closed-drawers active-filter]
  (fn [_ raw-room]
    (let [{:keys [id roomId name is-space? isSpace depth
                  notification-count unreadNotificationsCount
                  highlight-count unreadMentionsCount
                  avatar avatarUrl activeRoomCallParticipants]
           :or {activeRoomCallParticipants []}
           :as room} (if (map? raw-room) raw-room (js->clj raw-room :keywordize-keys true))
          id          (or id roomId)
          room-name   (or name "Loading...")
          space?      (or is-space? isSpace)
          notif-count (or notification-count unreadNotificationsCount 0)
          high-count  (or highlight-count unreadMentionsCount 0)
          url         (or avatar avatarUrl)
          depth       (or depth 0)
          is-call?    (room-type-call? @re-frame.db/app-db id)
          open-menu   (fn [e mx my]
                        (when e (.preventDefault e) (.stopPropagation e))
                        (re-frame/dispatch
                         [:context-menu/open
                          {:x mx :y my
                           :items (build-room-actions id room-name space? #_is-call?)}]))]
      (log/error active-space)
      (r/as-element
       [room-item {:id id
                   :name room-name
                   :indent (* depth 12)
                   :is-space? space?
                   :is-closed? (contains? closed-drawers id)
                   :is-call? is-call?
                   :has-call? (seq activeRoomCallParticipants)
                   :active-room active-room
                   :space-id (:id active-space)
                   :active-filter active-filter
                   :unread? (pos? notif-count)
                   :highlight? (pos? high-count)
                   :notif-count notif-count
                   :call-participants activeRoomCallParticipants
                   :avatar-url url
                   :open-menu-fn open-menu}]))))

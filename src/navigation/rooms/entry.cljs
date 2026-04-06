(ns navigation.rooms.entry
  (:require
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]
   [utils.svg :as icons]
   [utils.global-ui :refer [avatar long-press-props]]))

(defn build-room-actions [tr room-id room-name is-space?]
  [{:id "mark-read"
    :label (tr [:navigation.actions/mark-read])
    :icon [icons/check]
    :action #(re-frame/dispatch [:rooms/mark-read room-id])}
   {:id "copy-link"
    :label (tr [:navigation.actions/copy-link])
    :icon [icons/link]
    :action #(js/console.log "Copy permalink for" room-id)}
   {:id "leave"
    :label (if is-space? (tr [:navigation.actions/leave-space]) (tr [:navigation.actions/leave-room]))
    :icon [icons/leave]
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

(re-frame/reg-sub
 :room/is-call?
 (fn [db [_ room-id]]
   (get-in db [:room-previews room-id :is-call?])))


;; We should probably cache the DM avatars for quicker loading. We can update that
;; saved value and the shown avatar on load. Realistically
;; in the future the rust-sdk will give us the avatar for a DM without any hassle
;; Also likely important to note that we only bother loading the member list
;; for DMs because of this issue. Otherwise that piece can be removed as well.
(defn room-item [{:keys [id name indent is-space? is-closed? is-dm? has-call?
                         active-room unread? highlight? notif-count
                         call-participants avatar-url space-id active-filter active-space open-menu-fn]}]
  (let [active?  (= id active-room)
        tr       @(re-frame/subscribe [:i18n/tr])
        is-call? @(re-frame/subscribe [:room/is-call? id])
        profile  @(re-frame/subscribe [:sdk/profile])
        filter   @(re-frame/subscribe [:room-list/active-filter])
        my-id    (:user-id profile)
        needs-members?  (or (and is-dm? (not avatar-url)) has-call?)
        members-map     @(re-frame/subscribe [:room/members-map id])
        members-loading? @(re-frame/subscribe [:room/members-loading? id])]

    (when (and needs-members? (not members-loading?) (empty? members-map))
      (re-frame/dispatch [:room/fetch-members id]))

    (let [other-user   (when (and is-dm? (seq members-map))
                         (->> (vals members-map)
                              (remove #(= (:user-id %) my-id))
                              first))
          final-avatar (or avatar-url (:avatar-url other-user))
          final-name   (if (and is-dm? other-user (= name "Loading..."))
                         (:display-name other-user)
                         name)

          resolved-participants (map (fn [p]
                                       (let [p-id (or (:userId p) p)
                                             member-info (get members-map p-id)]
                                         {:id p-id
                                          :name (or (:displayName p) (:display-name member-info) p-id)
                                          :avatar-url (or (:avatarUrl p) (:avatar-url member-info))}))
                                     call-participants)]

      [:div.room-container
       (if (and is-space? active-space)
         [:div.room-drawer-header
          (merge {:style {:padding-left (str indent "px")}
                  :class (when is-closed? "collapsed")
                  :on-click #(re-frame/dispatch [:rooms/toggle-drawer id])
                  :on-context-menu #(open-menu-fn % (.-clientX %) (.-clientY %))}
                 (long-press-props #(open-menu-fn nil %1 %2)))
          [:span.drawer-arrow (if is-closed? [icons/chevron-down] [icons/chevron-down])]
          [:span.drawer-name (str/upper-case final-name)]]

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
            (and (= active-filter "people") (or is-dm? (not space-id)))
            [avatar {:id id :name final-name :url final-avatar :size 24 :status :online}]
            :else
            [icons/hash])
          [:span.room-name final-name]
          [:div.room-item-right
           [:div.room-hover-actions
            (when is-call?
              [:div.action-icon.chat-action
               {:title
                (tr [:navigation.room-list/view-chat])
                :on-click (fn [e]
                            (.stopPropagation e)
                            (re-frame/dispatch [:rooms/select id {:force-lobby? true :focus-override :timeline}]))}
               [icons/chat-bubble]])
            [:div.action-icon.settings-action
             {:title (tr [:navigation.room-list/room-settings])
              :on-click (fn [e]
                          (.stopPropagation e)
                          (re-frame/dispatch [:rooms/open-settings id]))}
             [icons/settings {:animate :spin}]]]
           (when has-call?
             [:div.call-participants.hide-on-desktop
              (for [p resolved-participants]
                ^{:key (str "mobile-call-" (:id p))}
                [:div.participant-avatar-ring
                 [avatar {:id (:id p) :url (:avatar-url p) :size 20}]])])

           (when unread?
             [:div.notification-badge
              {:class (when highlight? "highlight")}
              (if (> notif-count 99) "99+" notif-count)])]])
       (when (and has-call? (not is-closed?))
         [:div.desktop-call-list.hide-on-mobile
          {:style {:padding-left (str (+ indent 24) "px")}}
          (for [p resolved-participants]
            ^{:key (str "desktop-call-" (:id p))}
            [:div.call-participant-item
             {:on-click (fn [e]
                          (.stopPropagation e)
                          (re-frame/dispatch [:ui/open-user-profile (:id p)]))}
             [avatar {:id (:id p) :name (:name p) :url (:avatar-url p) :size 20}]
             [:span.participant-name (:name p)]])])])))

(defn render-room-item [tr client rooms active-space active-room closed-drawers active-filter]
  (fn [_ raw-room]
    (let [r           (if (map? raw-room) raw-room (js->clj raw-room :keywordize-keys true))
          id          (or (:id r) (:roomId r))
          room-name   (or (:name r) "Loading...")
          space?      (or (:is-space? r) (:isSpace r))
          dm?         (or (:is-direct? r) (:isDirect r) (:is-direct r))
          notif-count (or (:notification-count r) (:unreadNotificationsCount r) 0)
          high-count  (or (:highlight-count r) (:unreadMentionsCount r) 0)
          url         (or (:avatar r) (:avatarUrl r))
          depth       (or (:depth r) 0)
          call-parts  (or (:active-room-call-participants r)
                          (:activeRoomCallParticipants r)
                          (:active_room_call_participants r)
                          [])
          open-menu   (fn [e mx my]
                        (when e (.preventDefault e) (.stopPropagation e))
                        (re-frame/dispatch
                         [:context-menu/open
                          {:x mx :y my
                           :items (build-room-actions tr id room-name space?)}]))]
      (r/as-element
       [room-item {:tr tr
                   :id id
                   :name room-name
                   :indent (* depth 12)
                   :is-space? space?
                   :is-dm? dm?
                   :is-closed? (contains? closed-drawers id)
                   :has-call? (seq call-parts)
                   :active-room active-room
                   :space-id (:id active-space)
                   :active-filter active-filter
                   :unread? (pos? notif-count)
                   :highlight? (pos? high-count)
                   :notif-count notif-count
                   :call-participants call-parts
                   :avatar-url url
                   :open-menu-fn open-menu}]))))



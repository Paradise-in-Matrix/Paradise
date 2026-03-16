(ns navigation.rooms.room-summary
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [utils.helpers :refer [mxc->url]]
            )
  (:require-macros [utils.macros :refer [ocall oget]]))

(defn build-room-summary [room room-info latest-event]
  (let [
        num-notifications (js/Number (oget room-info :numUnreadNotifications))
        num-mentions      (js/Number (oget room-info :numUnreadMentions))
        num-unread        (js/Number (oget room-info :numUnreadMessages))
        membership        (oget room-info :membership)
        invited           (= membership "Invited")
        is-marked-unread  (oget room-info :isMarkedUnread)
        _ (js/console.log room-info)
        notification-state
        #js {:isMention                    (> num-mentions 0)
             :isNotification               (or (> num-notifications 0) is-marked-unread)
             :isActivityNotification       (and (> num-unread 0) (<= num-notifications 0))
             :hasAnyNotificationOrActivity (or (> num-unread 0) (> num-notifications 0) invited is-marked-unread)
             :invited                      invited}
        display-name (or (some-> (oget room-info :displayName) str/trim)
                         (oget room-info :id))
        avatar-url (mxc->url  (oget room-info :avatarUrl) {:type :thumbnail :width 10 :height 10 :method "crop"})]
    #js {:room                       room
         :id                         (oget room-info :id)
         :name                       display-name
         :avatar                     avatar-url
         ;; TODO Add message preview handling here
         :messagePreview             nil
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

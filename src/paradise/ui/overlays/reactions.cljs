(ns paradise.ui.overlays.reactions
(:require
 [paradise.shared.utils.svg :as icons]
 [paradise.ui.global :refer [avatar]]
 [paradise.shared.utils.macros :refer [defui]]
 [paradise.media.component :refer [media]]
 [re-frame.core :as re-frame]
 [clojure.string :as str]
 [taoensso.timbre :as log]
 [paradise.ui.input.emotes :refer [emoji-sticker-board]]
 [paradise.ui.overlays.base :refer [modal-component popover-component]]))

(defmethod popover-component :inline-emoji [_] nil)

(defui reaction-details-content [{:keys [room-id reactions]}]
  (let [tr          @(re-frame/subscribe [:i18n/tr])
        members-map @(re-frame/subscribe [:room/members-map room-id])
        close-fn    #(re-frame/dispatch [:ui/close-modal])]
    [:<>
     [:div.reaction-details-close-btn
      {:on-click close-fn :title "Close"}
      [icons/exit]]

     [:h3.reaction-details-header
      "Reactions"]

     (for [[emoji count senders] reactions]
       ^{:key emoji}
       [:div.reaction-detail-row
        [:div.reaction-detail-icon
         (if (str/starts-with? emoji "mxc://")
           [media {:mxc   emoji
                       :class "reaction-detail-img"
                       :style {:width "40px"
                               :height "40px"
                               :object-fit "cover"}
                       :alt   "Custom Emote"}]
           [:span emoji])]

        [:div.reaction-detail-users
         (for [user-id senders]
           (let [member     (get members-map user-id)
                 dname      (or (:display-name member) user-id)
                 avatar-url (:avatar-url member)]
             ^{:key user-id}
             [:div.reaction-detail-user
              [avatar {:id user-id :name dname :url avatar-url :size 28 :shape :circle}]
              [:span.reaction-detail-user-name dname]]))]])]))

(defmethod modal-component :reaction-details [_]
  reaction-details-content)

(defn reaction-picker-content [{:keys [room-id msg-id close-fn]}]
  [emoji-sticker-board
   {:on-close close-fn
    :on-insert-emoji (fn [_shortcode url]
                       (re-frame/dispatch [:sdk/toggle-reaction room-id msg-id url])
                       (close-fn))
    :on-insert-native (fn [unicode]
                        (re-frame/dispatch [:sdk/toggle-reaction room-id msg-id unicode])
                        (close-fn))
    :on-send-sticker (fn [& _]
                       (log/warn "No stickers in reactions!"))}])

(defmethod popover-component :reaction-picker [_]
  reaction-picker-content)
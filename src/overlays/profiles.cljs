(ns overlays.profiles
  (:require
   [overlays.base :refer [popover-component]]
   [utils.global-ui :refer [avatar]]
   [utils.images :refer [mxc-image]]
   ))



(defn profile-preview-content [{:keys [member tags]}]
  (let [pl         (:power-level member)
        tag-data   (get tags (keyword (str pl)))
        role-name  (:name tag-data)
        role-color (or (:color tag-data) "var(--text-primary)")
        icon-mxc   (some-> tag-data :icon :key)]
    [:div.profile-preview-card
     [:div.profile-preview-cover]
     [:div.profile-preview-content
      [:div.profile-preview-avatar-wrap
       [avatar {:id (:user-id member)
                :name (:display-name member)
                :url (:avatar-url member)
                :size 64}]]
      [:div.profile-preview-id-block
       [:span.profile-preview-name {:style {:color role-color}}
        (:display-name member)]
       [:span.profile-preview-id
        (:user-id member)]]
      (when role-name
        [:div.profile-preview-role-row
         (when icon-mxc
           [mxc-image {:mxc icon-mxc
                       :class "member-item-role-icon"
                       :alt role-name}])
         [:span.profile-preview-role-text {:style {:color role-color}}
          role-name]])]]))


(defmethod popover-component :profile-preview [_]
  profile-preview-content)
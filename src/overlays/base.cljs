(ns overlays.base
(:require))

(defmulti modal-component identity)

(defmethod modal-component :default [modal-id]
  (fn [_props]
    [:div {:style {:padding "24px" :background "white" :color "black" :border-radius "8px"}}
     [:p modal-id]]))

(defmethod modal-component :plugin-portal [_]
  (fn [{:keys [render-fn] :as props}]
    [render-fn props]))


(defmulti popover-component identity)

(defmethod popover-component :default [popover-id]
  (fn [_props]
    [:div {:style {:background "red" :color "white" :padding "10px"}}
      popover-id]))

(defmethod popover-component :plugin-portal [_]
  (fn [{:keys [render-fn] :as props}]
    [render-fn props]))
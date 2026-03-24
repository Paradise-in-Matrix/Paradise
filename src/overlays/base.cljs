(ns overlays.base
(:require))

(defmulti modal-component identity)

(defmethod modal-component :default [modal-id]
  (fn [_props]
    [:div {:style {:padding "24px" :background "white" :color "black" :border-radius "8px"}}
     [:p modal-id]]))

(defmulti popover-component identity)

(defmethod popover-component :default [popover-id]
  (fn [_props]
    [:div {:style {:background "red" :color "white" :padding "10px"}}
      popover-id]))
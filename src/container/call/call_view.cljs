(ns container.call.call-view
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [goog.functions :as gf]
            [container.reusable :refer [room-header]]
            [container.call.call-container :refer [host-rect]]))

(defn call-view [room-id]
  (let [host-ref    (r/atom nil)
        observer    (r/atom nil)
        room-meta @(rf/subscribe [:rooms/active-metadata])
        display-name (or (some-> room-meta .-name) room-id)
        sync-pos! (gf/debounce
                   (fn []
                     (when-let [el @host-ref]
                       (let [rect (.getBoundingClientRect el)]
                         (reset! host-rect
                                 {:top (.-top rect)
                                  :left (.-left rect)
                                  :width (.-width rect)
                                  :height (.-height rect)}))))
                   50)]

    (r/create-class
     {:component-did-mount
      (fn []
        (when-let [el @host-ref]
          (sync-pos!)
          (let [ro (js/ResizeObserver. sync-pos!)]
            (.observe ro el)
            (reset! observer ro))
          (.addEventListener js/window "scroll" sync-pos! true)))

      :component-will-unmount
      (fn []
        (when-let [ro @observer]
          (.disconnect ro))
        (.removeEventListener js/window "scroll" sync-pos! true)
        (reset! host-rect nil))
:reagent-render
      (fn [room-id]
        [:div.call-view-host
         {:style {:width "100%"
                  :height "100%"
                  :display "flex"
                  :flex-direction "column"
                  :position "relative"}}
         [room-header {:display-name display-name
                       :active-id    room-id
                       :compact?     false}]
         [:div.call-video-area
          {:ref #(reset! host-ref %)
           :style {:flex 1
                   :width "100%"
                   :background "#000"
                   :pointer-events "none"}}]])

      #_:reagent-render
      #_(fn [room-id]
        [room-header {:display-name display-name
                      :active-id    room-id
                      :compact?     true}]
        [:div.call-view-host
         {:ref #(reset! host-ref %)
          :style {:width "100%"
                  :height "100%"
                  :position "relative"
                  :pointer-events "none"}}])


      })
    ))
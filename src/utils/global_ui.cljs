(ns utils.global-ui
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [reagent.core :as r]
   [overlays.base :refer [modal-component popover-component]]
   [utils.svg :as icons]
   ))

(defn click-away-wrapper
  "A reusable invisible backdrop for 'light dismiss' popovers.
   It catches clicks and prevents them from bleeding through to the app."
  [{:keys [on-close z-index]} & children]
  [:<>
   [:div.click-away-catcher
    {:on-mouse-down (fn [e]
                      (.preventDefault e)
                      (.stopPropagation e)
                      (on-close))
     :style {:position "fixed"
             :top 0 :left 0 :right 0 :bottom 0
             :z-index (or z-index 99)
             :cursor "default"}}]
   (into [:<>] children)])

(re-frame/reg-event-db
 :context-menu/open
 (fn [db [_ {:keys [x y items]}]]
   (assoc db :context-menu {:open? true
                            :x x
                            :y y
                            :items items})))

(re-frame/reg-event-db
 :context-menu/close
 (fn [db _]
   (assoc db :context-menu {:open? false :x 0 :y 0 :items []})))

(re-frame/reg-sub
 :context-menu/state
 (fn [db _]
   (:context-menu db {:open? false})))

(defn global-context-menu []
  (r/with-let [!drag-y (r/atom 0)
               !start-y (r/atom 0)
               is-mobile? (<= js/window.innerWidth 768)]
    (let [{:keys [open? x y items]} @(re-frame/subscribe [:context-menu/state])]
      (when-not open?
        (reset! !drag-y 0)
        (reset! !start-y 0))
      (when open?
        (let [
              menu-width  200
      item-height (if is-mobile? 48 32)
      menu-height (* (count items) item-height)
      render-x (if (> (+ x menu-width) js/window.innerWidth)
                 (- x menu-width)
                 x)
      render-y (if (> (+ y menu-height) js/window.innerHeight)
                 (- y menu-height)
                 y)

              handle-ptr-down (fn [e]
                                (reset! !start-y (.-clientY e))
                                (.setPointerCapture (.-target e) (.-pointerId e)))
              handle-ptr-move (fn [e]
                                (when (pos? @!start-y)
                                  (let [delta (- (.-clientY e) @!start-y)]
                                    (reset! !drag-y (max 0 delta)))))
              handle-ptr-up (fn [e]
                              (if (> @!drag-y 100)
                                (re-frame/dispatch [:context-menu/close])
                                (reset! !drag-y 0))
                              (reset! !start-y 0)
                              (.releasePointerCapture (.-target e) (.-pointerId e)))]


          [click-away-wrapper
           {:on-close #(re-frame/dispatch [:context-menu/close])
            :z-index 9998}
           [:div.universal-context-menu
            {:class (when is-mobile? "mobile-sheet")
             :on-pointer-down (when is-mobile? handle-ptr-down)
             :on-pointer-move (when is-mobile? handle-ptr-move)
             :on-pointer-up   (when is-mobile? handle-ptr-up)
             :style (if is-mobile?
           {:transform (str "translateY(" @!drag-y "px)")
            :transition (if (pos? @!start-y) "none" "transform 0.3s cubic-bezier(0.2, 0.8, 0.2, 1)")
            :z-index 99999
            }
           {:left (str render-x "px")
            :top  (str render-y "px")
            :transform "none"
            :z-index 99999})
             }
            [:div.mobile-drag-handle]
            (for [{:keys [id label action class-name icon]} items]
              ^{:key (or id label)}
              [:div.context-menu-item
               {:class class-name
                :on-click (fn [e]
                            (.stopPropagation e)
                            (action)
                            (re-frame/dispatch [:context-menu/close]))}
               (when icon [:span.item-icon icon])
               [:span.item-label label]])]])))))


(re-frame/reg-event-db
 :ui/open-modal
 (fn [db [_ modal-id props]]
   (assoc db :active-modal {:id modal-id :props props})))

(re-frame/reg-event-db
 :ui/close-modal
 (fn [db _]
   (dissoc db :active-modal)))

(re-frame/reg-sub
 :ui/active-modal
 (fn [db _]
   (:active-modal db)))

(defn- modal-inner
  [{:keys [on-close backdrop-props window-props]} children]
  (r/with-let [handle-keyup (fn [e] (when (= (.-key e) "Escape") (on-close)))
               _ (.addEventListener js/window "keyup" handle-keyup)]
    [:div.modal-backdrop
     (merge {:on-click on-close}
            backdrop-props)
     (into [:div.modal-window
            (merge {:on-click #(.stopPropagation %)}
                   window-props)]
           children)]
    (finally
      (.removeEventListener js/window "keyup" handle-keyup))))

(defn generic-modal
  [{:keys [is-open?] :as props} & children]
  (when is-open?
    [modal-inner props children]))

(defn modal-root []
  (let [active-modal @(re-frame/subscribe [:ui/active-modal])]
    (when active-modal
      (let [{:keys [id props]} active-modal
            _ (log/error active-modal)
            close-fn           #(re-frame/dispatch [:ui/close-modal])
            TargetComponent    (modal-component id)]
        [generic-modal
         {:is-open?       true
          :on-close       close-fn
          :backdrop-props (:backdrop-props props)
          :window-props   (:window-props props)}
         [TargetComponent props]]))))

(re-frame/reg-event-db
 :ui/open-popover
 (fn [db [_ id props]]
   (assoc db :ui/active-popover {:id id :props props})))

(re-frame/reg-event-db
 :ui/close-popover
 (fn [db _]
   (dissoc db :ui/active-popover)))

(re-frame/reg-sub
 :ui/active-popover
 (fn [db _]
   (:ui/active-popover db)))

#_(defn popover-root []
  (let [active-popover @(re-frame/subscribe [:ui/active-popover])]
    (when active-popover
      (let [{:keys [id props]} active-popover
            _ (js/console.error props)
            _ (log/error props)
            {:keys [x y width height] :or {width 320 height 380}} props
            close-fn #(re-frame/dispatch [:ui/close-popover])
            Target   (popover-component id)
            render-x (if (> (+ x width) js/window.innerWidth) (- x width) x)
            render-y (if (> (+ y height) js/window.innerHeight) (- y height) y)]
        [:<>
         [:div.popover-backdrop
          {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0 :z-index 11999}
           :on-click close-fn
           :on-context-menu (fn [e] (.preventDefault e) (close-fn))}]
         [:div.popover-container
          {:style {:left (str render-x "px")
                   :top  (str render-y "px")
                   :position "fixed"
                   :z-index 12000}}
          [Target (assoc props :close-fn close-fn)]]]))))

(defn popover-root []
  (let [active-popover @(re-frame/subscribe [:ui/active-popover])]
    (when active-popover
      (let [{:keys [id props]} active-popover
            ;; Ensure props is a map
            props    (if (map? props) props (js->clj props :keywordize-keys true))
            {:keys [x y width height backdrop?]
             :or {width 320 height 380 backdrop? true}} props
            close-fn #(re-frame/dispatch [:ui/close-popover])
            Target   (popover-component id)
            win-w js/window.innerWidth
            win-h js/window.innerHeight
            render-x (max 10 (if (> (+ x width) win-w) (- x width) x))
            render-y (max 10 (if (> (+ y height) win-h) (- y height) y))]
        [:<>
         (when backdrop?
           [:div.popover-backdrop
            {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0 :z-index 11999}
             :on-click close-fn
             :on-context-menu (fn [e] (.preventDefault e) (close-fn))}])
         [:div.popover-container
          {:style {:left (str render-x "px")
                   :top  (str render-y "px")
                   :position "fixed"
                   :z-index 12000}}
          [Target (assoc props :close-fn close-fn)]]]))))





(defn satellite-overlay [child-component]
  (let [picker-state @(re-frame/subscribe [:msg/active-reaction-picker])]
    (when picker-state
      (let [{:keys [room-id event-or-transaction-id x y]} picker-state
            width  320
            height 380
            render-x (if (> (+ x width) js/window.innerWidth) (- x width) x)
            render-y (if (> (+ y height) js/window.innerHeight) (- y height) y)]
        [:div.satellite-overlay
         {:style {:left (str render-x "px")
                  :top (str render-y "px")
                  :position "fixed"
                  :z-index 1000}}
         [:div.satellite-content
          [child-component
           {:on-close #(re-frame/dispatch [:msg/close-reaction-picker])
            :on-insert-native
            (fn [unicode-char]
              (re-frame/dispatch [:sdk/toggle-reaction room-id  event-or-transaction-id unicode-char])
              (re-frame/dispatch [:msg/close-reaction-picker]))
             :on-insert-emoji
            (fn [shortcode url]
              (re-frame/dispatch [:sdk/toggle-reaction room-id event-or-transaction-id  url])
              (re-frame/dispatch [:msg/close-reaction-picker]))
            :on-send-sticker
            (fn [& _]
              (log/warn "Cannot send stickers as a reaction!"))}]]]))))

(defn global-reaction-picker []
  (let [picker-state @(re-frame/subscribe [:msg/active-reaction-picker])]
    (when picker-state
      (let [{:keys [room-id msg-id x y]} picker-state
            width  320
            height 380
            render-x (if (> (+ x width) js/window.innerWidth)
                       (- x width)
                       x)
            render-y (if (> (+ y height) js/window.innerHeight)
                       (- y height)
                       y)]
        [:div.dynamic-reaction-wrapper
         {:style {:position "fixed"
                  :left (str render-x "px")
                  :top (str render-y "px")
                  :z-index 11000}}
         [emoji-sticker-board
          {:on-close #(re-frame/dispatch [:msg/close-reaction-picker])
           :on-insert-emoji (fn [shortcode _url]
                              (re-frame/dispatch [:sdk/send-reaction room-id msg-id shortcode])
                              (re-frame/dispatch [:msg/close-reaction-picker]))
           :on-send-sticker (fn [& _] (log/warn "No stickers in reactions!"))}]]))))

(defn long-press-props [action-fn]
  (let [timer (atom nil)
        start-pos (atom nil)
        fired? (atom false)
        clear-timer! (fn []
                       (when @timer
                         (js/clearTimeout @timer)
                         (reset! timer nil)))]
    {:on-context-menu (fn [e]
                        (.preventDefault e)
                        (when-not @fired?
                          (action-fn (.-clientX e) (.-clientY e)))
                        (reset! fired? false))
     :on-touch-start  (fn [e]
                        (clear-timer!)
                        (reset! fired? false)
                        (let [touch (aget (.-touches e) 0)
                              mx (.-clientX touch)
                              my (.-clientY touch)]
                          (reset! start-pos {:x mx :y my})
                          (reset! timer
                                  (js/setTimeout
                                   (fn []
                                     (reset! fired? true)
                                     (action-fn mx my)
                                     (clear-timer!))
                                   500))))
     :on-touch-move   (fn [e]
                        (when @timer
                          (let [touch (aget (.-touches e) 0)
                                mx (.-clientX touch)
                                my (.-clientY touch)
                                {:keys [x y]} @start-pos
                                dx (- mx x)
                                dy (- my y)
                                dist-sq (+ (* dx dx) (* dy dy))]
                            (when (> dist-sq 100)
                              (clear-timer!)))))
     :on-touch-end    (fn [_] (clear-timer!))
     :on-touch-cancel (fn [_] (clear-timer!))}))

(def avatar-colors
  ["#5865f2" "#3ba55c" "#faa61a" "#ed4245" "#eb459e" "#9b59b6"])

(defn get-avatar-color [id]
  (if-not id
    (first avatar-colors)
    (let [hash (reduce #(+ %1 (.charCodeAt %2 0)) 0 (str id))]
      (nth avatar-colors (mod hash (count avatar-colors))))))

;; Hacky fix, but I need to clean the 'shape' up anyway haha
(defn kname [arg]
  (name arg))

(defn avatar [{:keys [id name url size status shape] :or {size 32 shape :circle}}]
  (r/with-let [!broken? (r/atom false)]
    (let [show-image? (and url (not @!broken?))
          len         (count name)
          target      (if (#{:space :squircle :square} shape) 2 1)
          initials    (if (> len 0)
                        (subs name 0 (min len target))
                        "?")
          bg-color    (if (= shape :none) "transparent" (get-avatar-color id name))]
      [:div.avatar-wrapper
       {:class (kname shape)
        :style {:width size :height size}}
       (when-not show-image?
         [:div.avatar-placeholder-layer
          {:style {:background-color bg-color}}
          (when-not (= shape :none)
            [:span.avatar-text
             {:style {:font-size (str (/ size (if (= shape :circle) 2.2 2.5)) "px")}}
             initials])])

       (when show-image?
         [:img.avatar-img-layer
          {:src      url
           :alt      ""
           :on-error #(reset! !broken? true)}])])))



(defn make-swipe-handlers
  "Abstracts pointer event tracking for horizontal swipes.
   !drag-state should be an atom containing {:start-x nil :dx 0}."
  [!drag-state {:keys [on-start on-move on-end]}]
  (let [handle-ptr-down
        (fn [e]
          (when (= (.-button e) 0)
            (.setPointerCapture (.-target e) (.-pointerId e))
            (let [x (.-clientX e)]
              (reset! !drag-state {:start-x x :dx 0})
              (when on-start (on-start x)))))

        handle-ptr-move
        (fn [e]
          (let [{:keys [start-x]} @!drag-state]
            (when start-x
              (let [dx (- (.-clientX e) start-x)]
                (swap! !drag-state assoc :dx dx)
                (when on-move (on-move dx))))))

        handle-ptr-up
        (fn [e]
          (let [{:keys [start-x dx]} @!drag-state]
            (when start-x
              (.releasePointerCapture (.-target e) (.-pointerId e))
              (when on-end (on-end dx))
              (swap! !drag-state assoc :start-x nil :dx 0))))]

    {:on-pointer-down   handle-ptr-down
     :on-pointer-move   handle-ptr-move
     :on-pointer-up     handle-ptr-up
     :on-pointer-cancel handle-ptr-up}))

(defn swipe-to-action-wrapper [{:keys [can-edit? on-action wrapper-props enabled?]} & children]
  (if-not enabled?
    (into [:div.swipe-foreground wrapper-props] children)
    (r/with-let [!drag-state     (r/atom {:start-x nil :dx 0 :action nil :current-x 0})
                 reply-threshold 50
                 edit-threshold  140
                 swipe-props (make-swipe-handlers
                              !drag-state
                              {:on-move (fn [dx]
                                          (let [pull-dist  (- dx)
                                                bounded-dx (max 0 (min pull-dist 180))
                                                new-action (cond
                                                             (and can-edit? (> bounded-dx edit-threshold)) :edit
                                                             (> bounded-dx reply-threshold)                :reply
                                                             :else                                         nil)]
                                            (swap! !drag-state assoc :current-x bounded-dx :action new-action)))
                               :on-end (fn [_]
                                         (when-let [action (:action @!drag-state)]
                                           (on-action action))
                                         (swap! !drag-state assoc :action nil :current-x 0))})]

      (let [{:keys [start-x current-x action]} @!drag-state
            is-dragging? (some? start-x)]

        [:div.timeline-swipe-wrapper
         {:style {:position "relative" :overflow "hidden"}}
         [:div.swipe-action-bg
          {:style {:position "absolute" :right 0 :top 0 :bottom 0 :width "100%"
                   :display "flex" :align-items "center" :justify-content "flex-end" :padding-right "16px"
                   :opacity (if (> current-x 20) 1 0)
                   :transition "opacity 0.2s"
                   :color (if (= action :edit) "var(--brand-experiment, #5865f2)" "var(--text-muted, #888)")
                   :font-weight "bold" :font-size "0.85rem" :z-index 0}}
          (cond
            (= action :edit)  [:span [icons/edit] " Edit"]
            (= action :reply) [:span "Reply " [icons/reply]]
            :else             [:span [icons/reply]])]
         (into [:div.swipe-foreground
                (merge wrapper-props
                       swipe-props
                       {:style {:transform    (str "translateX(-" current-x "px)")
                                :transition   (if is-dragging? "none" "transform 0.25s cubic-bezier(0.2, 0.8, 0.2, 1)")
                                :touch-action "pan-y"
                                :position     "relative"
                                :z-index      1
                                :background   "var(--background-primary, #313338)"
                                :user-select  (if is-dragging? "none" "auto")}})]
               children)]))))
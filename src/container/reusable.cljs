(ns container.reusable
            (:require [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [re-frame.db :as db]
            ["react-virtuoso" :refer [Virtuoso]]
            [promesa.core :as p]
            [reagent.core :as r]
            [reagent.dom.client :as rdom])
  )

(re-frame/reg-event-db
 :container/set-main-focus
 (fn [db [_ focus]]
   (assoc db :main-focus focus)))

(re-frame/reg-event-db
 :container/set-side-panel
 (fn [db [_ panel]]
   (assoc db :side-panel panel)))

(re-frame/reg-sub
 :container/main-focus
 (fn [db _]
   (:main-focus db :timeline)))

(re-frame/reg-sub
 :container/side-panel
 (fn [db _]
   (:side-panel db nil)))

(defn room-header [{:keys [display-name compact? active-id]}]
  (let [main-focus    @(re-frame/subscribe [:container/main-focus])
        side-panel    @(re-frame/subscribe [:container/side-panel])
        call-active?  @(re-frame/subscribe [:call/is-active?])
        in-lobby?     (and (= main-focus :timeline) call-active?)
        show-actions? (or (not compact?) (= main-focus :call))]
    [:div.timeline-header
     [:div.header-left
      [:button.mobile-menu-btn {:on-click #(re-frame/dispatch [:container/toggle-sidebar])} "☰"]
      (when display-name
        [:h2.timeline-header-title display-name])]
     (when (and show-actions? active-id)
       [:div.header-actions
        [:button.header-icon-btn
         {:title (if in-lobby? "Join Call" "Start Call")
          :class (when (or (= main-focus :call) call-active?) "active-green")
          :on-click (fn [_]
                      (re-frame/dispatch [:call/init-widget active-id])
                      (re-frame/dispatch [:container/set-main-focus :call]))}
         [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "none" :stroke "currentColor" :stroke-width "2"}
          [:path {:d "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"}]]]

       (when (= main-focus :call)
          [:button.header-icon-btn
           {:title "Room Chat"
            :class (when (= side-panel :timeline) "active")
            :on-click #(re-frame/dispatch [:container/set-side-panel (if (= side-panel :timeline) nil :timeline)])}
           [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "none" :stroke "currentColor" :stroke-width "2"}
            [:path {:d "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"}]]])

        [:button.header-icon-btn
         {:title "Search"
          :class (when (= side-panel :search) "active")
          :on-click #(re-frame/dispatch [:container/set-side-panel :search])}
         [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "none" :stroke "currentColor" :stroke-width "2"}
          [:circle {:cx "11" :cy "11" :r "8"}]
          [:line {:x1 "21" :y1 "21" :x2 "16.65" :y2 "16.65"}]]]

        [:button.header-icon-btn
         {:title "Pinned Messages"
          :class (when (= side-panel :pins) "active")
          :on-click #(re-frame/dispatch [:container/set-side-panel :pins])}
         [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "none" :stroke "currentColor" :stroke-width "2"}
          [:path {:d "M21 10V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v2a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 10z"}]
          [:path {:d "M12 22v-6"}]
          [:path {:d "m12 16 7-4"}]
          [:path {:d "M12 16 5 12"}]]]

        [:button.header-icon-btn
         {:title "Member List"
          :class (when (= side-panel :members) "active")
          :on-click #(re-frame/dispatch [:container/set-side-panel :members])}
         [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "none" :stroke "currentColor" :stroke-width "2"}
          [:path {:d "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"}]
          [:circle {:cx "9" :cy "7" :r "4"}]
          [:path {:d "M23 21v-2a4 4 0 0 0-3-3.87"}]
          [:path {:d "M16 3.13a4 4 0 0 1 0 7.75"}]]]

        [:button.header-icon-btn
         {:title "More Options"
          :on-click (fn [e]
                      (let [mx (.-clientX e)
                            my (.-clientY e)]
                        (re-frame/dispatch [:context-menu/open {:x mx :y my :items (build-room-actions active-id display-name)}])))}
         [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "none" :stroke "currentColor" :stroke-width "2"}
          [:circle {:cx "12" :cy "12" :r "1"}]
          [:circle {:cx "12" :cy "5" :r "1"}]
          [:circle {:cx "12" :cy "19" :r "1"}]]]])]))
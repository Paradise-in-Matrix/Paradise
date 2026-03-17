(ns container.base
  (:require
   [re-frame.core :as re-frame]
   [container.call.events]
   [container.call.core]
   [container.call.call-view :refer [call-view]]
   [container.timeline.base :refer [timeline]]
   [taoensso.timbre :as log]
   [promesa.core :as p]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]))


#_(defn call-view []
  [:div.call-view {:style {:padding "20px" :background "#222" :color "#fff" :height "100%"}}
   [:h2 "Active Call"]
   [:button {:on-click #(re-frame/dispatch [:ui/set-main-focus :timeline])} "End Call"]])

(defn thread-view []
  [:div.thread-view {:style {:padding "20px" :height "100%"}}
   [:h2 "Thread Context"]
   [:button {:on-click #(re-frame/dispatch [:ui/set-main-focus :timeline])} "Back to Timeline"]])

(defn member-list []
  [:div.member-list-wrapper
   [:ul.member-items
    [:li "@alice:matrix.org"]
    [:li "@bob:matrix.org"]]])

(defn thread-list []
  [:div.thread-list {:style {:padding "10px"}}
   [:h3 "Active Threads"]
   [:div "Thread 1..."]
  [:div "Thread 2..."]])


(defn container []
  (let [main-focus    @(re-frame/subscribe [:container/main-focus])
        side-panel    @(re-frame/subscribe [:container/side-panel])
        active-room   @(re-frame/subscribe [:rooms/active-id])]
    [:div.room-layout
     {:style {:display "grid"
              :flex "1"
              :min-width "0"
              :grid-template-columns (if side-panel "1fr 350px" "1fr")
              :height "100%"}}
   [:div.main-content
    (case main-focus
      :call     [call-view active-room]
      :thread   [thread-view]
      :timeline [timeline])]
     (when-let [panel side-panel]
       [:div.sidebar
            {:style {:display "flex"
                     :flex-direction "column"
                     :height "100%"
                     :background "var(--surface-1)"
                     :border-left "1px solid var(--border-color)"}}
            (when (contains? #{:members :threads :search :pins :timeline} panel)
              [:div.sidebar-header
               {:style {:display "flex"
                        :align-items "center"
                        :justify-content "space-between"
                        :padding "0 16px"
                        :height "48px"
                        :border-bottom "1px solid var(--border-color)"}}
               [:h3.sidebar-title
                {:style {:font-size "0.9rem" :text-transform "uppercase" :letter-spacing "0.05rem"}}
                (case panel
                  :members "Members"
                  :threads "Threads"
                  :search  "Search"
                  :pins    "Pinned Messages"
                  :timeline "")]
               [:button.sidebar-close
                {:style {:background "transparent" :border "none" :color "var(--text-secondary)" :cursor "pointer"}
                 :on-click #(re-frame/dispatch [:container/set-side-panel nil])}
                "✕"]])

            [:div.sidebar-content
             {:style {:flex 1 :overflow "hidden"}}
             (case panel
               :timeline [timeline :compact? true :hide-header? true]
               :members  [member-list]
               :threads  [thread-list]
      ;;:search   [search-view]
      ;;:pins     [pins-view]
               )]])

           ])

)
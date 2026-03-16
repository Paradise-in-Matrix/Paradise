(ns utils.svg
  (:require)
  )

(defn- icon-base [{:keys [animate] :as props} & children]
  (into
   [:svg (merge {:viewBox "0 0 24 24"
                 :width "16px"
                 :height "16px"
                 :fill "none"
                 :stroke "currentColor"
                 :stroke-width "2"
                 :stroke-linecap "round"
                 :stroke-linejoin "round"
                 :class (when animate (str "animate-" (name animate)))}
                (dissoc props :animate))]
   children))




(defn phone-hangup [props]
  [icon-base (merge {:fill "currentColor" :stroke "none" :animate :hangup} props)
   [:path {:d "M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.58.9-.98.45-1.89.86-2.73 1.34-.14.07-.31.11-.49.11-.28 0-.53-.11-.7-.3l-2.6-2.6c-.18-.17-.3-.43-.3-.71 0-.28.11-.53.3-.7C3.12 7.32 7.32 4 12 4s8.88 3.32 11.7 6.96c.19.17.3.43.3.71 0 .28-.11.53-.3.71l-2.6 2.6c-.17.19-.42.3-.7.3-.18 0-.35-.04-.49-.11-.84-.48-1.75-.89-2.73-1.34-.35-.16-.58-.51-.58-.9v-3.1C15.15 9.25 13.6 9 12 9z"}]])

(defn mic [props]
  [icon-base (merge {:animate :wiggle} props)
   [:path {:d "M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3zM19 10v2a7 7 0 0 1-14 0v-2M12 19v4M8 23h8"}]])

(defn screen-share [props]
  [icon-base props
   [:rect {:x "2" :y "3" :width "20" :height "14" :rx "2" :ry "2"}]
   [:path {:d "M8 21h8M12 17v4"}]
   [:path {:d "M17 8l-5-5-5 5M12 3v9" :stroke "var(--accent-color)"}
    [:animate {:attributeName "stroke-opacity"
               :values "1;0.3;1"
               :dur "2s"
               :repeatCount "indefinite"}]]])

(defn video [props]
  [icon-base props
   [:path {:d "M23 7l-7 5 7 5V7zM1 5h15v14H1z"}]])

(defn mic-off [props]
  [icon-base props
   [:g
    [:path {:d "M9 9a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6"}]
    [:path {:d "M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23M12 19v4M8 23h8"}]
    [:line {:x1 "1" :y1 "1" :x2 "23" :y2 "23"
            :stroke-dasharray "32"
            :stroke-dashoffset "32"}
     [:animate {:attributeName "stroke-dashoffset"
                :from "32" :to "0"
                :dur "0.2s"
                :fill "freeze"}]]]])

(defn headphones [props]
  [icon-base (merge {:animate :ears} props)
   [:path {:d "M3 18v-6a9 9 0 0 1 18 0v6M4 15h2a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM18 15h2a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z"}]])

(defn headphones-off [props]
  [icon-base props
   [:g [:path {:d "M3 11a9 9 0 0 1 13.52-7.78M20.42 15h.58a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-3a2 2 0 0 1 .58-1.42M2 17v3a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2z"}]
       [:line {:x1 "1" :y1 "1" :x2 "23" :y2 "23"
               :stroke-dasharray "32" :stroke-dashoffset "32"}
        [:animate {:attributeName "stroke-dashoffset" :from "32" :to "0" :dur "0.2s" :fill "freeze"}]]]])

(defn video-off [props]
  [icon-base props
   [:g [:path {:d "M16 16v3H1V5h2m4 0h9v9l7 5V7l-7 5V7.94"}]
       [:line {:x1 "1" :y1 "1" :x2 "23" :y2 "23"
               :stroke-dasharray "32" :stroke-dashoffset "32"}
        [:animate {:attributeName "stroke-dashoffset" :from "32" :to "0" :dur "0.2s" :fill "freeze"}]]]])

(defn screen-share-off [props]
  [icon-base props
   [:rect {:x "2" :y "3" :width "20" :height "14" :rx "2" :ry "2"}
    [:animate {:attributeName "opacity" :from "1" :to "0.3" :dur "0.4s" :fill "freeze"}]]
   [:path {:d "M8 21h8M12 17v4"}]])

(defn speaker [{:keys [has-call?] :as props}]
  [icon-base (merge {:stroke-width "2.5"
                     :animate (when has-call? :waves)}
                    (dissoc props :has-call?))
   [:path {:d "M11 5L6 9H2v6h4l5 4V5z"}]
   (when has-call?
     [:path {:d "M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"}])])

(defn settings [props]
  [icon-base props
   [:circle {:cx "12" :cy "12" :r "3"}]
   [:path {:d "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"}]])

(defn chat-bubble [props]
  [icon-base props
   [:path {:d "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"}]])

(defn search [props]
  [icon-base (merge {:animate :scan} props)
   [:circle {:cx "11" :cy "11" :r "8"}]
   [:line {:x1 "21" :y1 "21" :x2 "16.65" :y2 "16.65"}]])

(defn hash [props]
  [icon-base props
   [:line {:x1 "4" :y1 "9" :x2 "20" :y2 "9"}]
   [:line {:x1 "4" :y1 "15" :x2 "20" :y2 "15"}]
   [:line {:x1 "10" :y1 "3" :x2 "8" :y2 "21"}]
   [:line {:x1 "16" :y1 "3" :x2 "14" :y2 "21"}]])
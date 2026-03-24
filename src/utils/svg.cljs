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

(defn phone [props]
  [icon-base (merge {:width "20px" :height "20px"} props)
   [:path {:d "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"}]])

(defn pins [props]
  [icon-base (merge {:width "20px" :height "20px"} props)
   [:path {:d "M12 17v5"}]
   [:path {:d "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"}]] )

(defn members [props]
  [icon-base (merge {:width "20px" :height "20px"} props)
   [:path {:d "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"}]
   [:circle {:cx "9" :cy "7" :r "4"}]
   [:path {:d "M23 21v-2a4 4 0 0 0-3-3.87"}]
   [:path {:d "M16 3.13a4 4 0 0 1 0 7.75"}]])

(defn more-vertical [props]
  [icon-base (merge {:width "20px" :height "20px"} props)
   [:circle {:cx "12" :cy "12" :r "1"}]
   [:circle {:cx "12" :cy "5" :r "1"}]
   [:circle {:cx "12" :cy "19" :r "1"}]])

(defn menu [props]
  [icon-base (merge {:width "20px" :height "20px"} props)
   [:line {:x1 "4" :y1 "12" :x2 "20" :y2 "12"}]
   [:line {:x1 "4" :y1 "6" :x2 "20" :y2 "6"}]
   [:line {:x1 "4" :y1 "18" :x2 "20" :y2 "18"}]])

(defn arrow-left [props]
  [icon-base props
   [:line {:x1 "19" :y1 "12" :x2 "5" :y2 "12"}]
   [:polyline {:points "12 19 5 12 12 5"}]])

(defn check-circle-green [props]
  [icon-base (merge {:stroke "#22c55e"} props)
   [:polyline {:points "20 6 9 17 4 12"}]])

(defn chevron-down [props]
  [icon-base props
   [:polyline {:points "6 9 12 15 18 9"}]])

(defn home [props]
  [icon-base props
   [:path {:d "M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"}]
   [:polyline {:points "9 22 9 12 15 12 15 22"}]])

(defn user [props]
  [icon-base props
   [:path {:d "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"}]
   [:circle {:cx "12" :cy "7" :r "4"}]])

(defn check [props]
  [icon-base props
   [:polyline {:points "20 6 9 17 4 12"}]])

(defn leave [props]
  [icon-base props
   [:path {:d "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"}]
   [:polyline {:points "16 17 21 12 16 7"}]
   [:line {:x1 "21" :y1 "12" :x2 "9" :y2 "12"}]])

(defn download [props]
  [icon-base props
   [:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
   [:polyline {:points "7 10 12 15 17 10"}]
   [:line {:x1 "12" :y1 "15" :x2 "12" :y2 "3"}]])

(defn external-link [props]
  [icon-base props
   [:path {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]
   [:polyline {:points "15 3 21 3 21 9"}]
   [:line {:x1 "10" :y1 "14" :x2 "21" :y2 "3"}]])


(defn plus [props]
  [icon-base (merge {:width "20px" :height "20px"} props)
   [:line {:x1 "12" :y1 "5" :x2 "12" :y2 "19"}]
   [:line {:x1 "5" :y1 "12" :x2 "19" :y2 "12"}]])

(defn smiley [props]
  [icon-base (merge {:width "20px" :height "20px"} props)
   [:circle {:cx "12" :cy "12" :r "9"}]
   [:circle {:cx "9" :cy "9" :r "1" :fill "currentColor"}]
   [:circle {:cx "15" :cy "9" :r "1" :fill "currentColor"}]
   [:path {:d "M8 14s1.5 2 4 2 4-2 4-2"
           :fill "none"
           :stroke-linecap "round"}]])

(defn send [props]
  [icon-base (merge {:width "20px" :height "20px"} props)
   [:path {:d "M22 2L11 13"}]
   [:path {:d "M22 2L15 22L11 13L2 9L22 2Z"}]])

(defn file [props]
  [icon-base props
   [:path {:d "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"}]
   [:polyline {:points "13 2 13 9 20 9"}]])

(defn reply [props]
  [icon-base props
   [:polyline {:points "9 17 4 12 9 7"}]
   [:path {:d "M20 18v-2a4 4 0 0 0-4-4H4"}]])

(defn edit [props]
  [icon-base props
   [:path {:d "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"}]
   [:path {:d "M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"}]])

(defn exit [props]
  [icon-base props
   [:line {:x1 "18" :y1 "6" :x2 "6" :y2 "18"}]
   [:line {:x1 "6" :y1 "6" :x2 "18" :y2 "18"}]])

(defn thread [props]
  [icon-base props
   [:circle {:cx "7" :cy "7" :r "3"}]
   [:circle {:cx "17" :cy "17" :r "3"}]
   [:path {:d "M7 10v4a2 2 0 0 0 2 2h4"}]
   [:path {:d "M17 14v-4a2 2 0 0 0-2-2h-4"}]])

(defn copy [props]
  [icon-base props
   [:rect {:x "9" :y "9" :width "13" :height "13" :rx "2" :ry "2"}]
   [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]])

(defn link [props]
  [icon-base props
   [:path {:d "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"}]
   [:path {:d "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"}]])

(defn trash [props]
  [icon-base props
   [:polyline {:points "3 6 5 6 21 6"}]
   [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
   [:line {:x1 "10" :y1 "11" :x2 "10" :y2 "17"}]
   [:line {:x1 "14" :y1 "11" :x2 "14" :y2 "17"}]])

(defn more [props]
  [icon-base props
   [:circle {:cx "12" :cy "12" :r "1"}]
   [:circle {:cx "19" :cy "12" :r "1"}]
   [:circle {:cx "5" :cy "12" :r "1"}]])

(defn double-check [props]
  [icon-base props
   [:path {:d "M7 12l5 5L22 7"}]
   [:path {:d "M2 12l5 5L9 15"}]])

(defn typing-dots [props]
  [icon-base props
   [:circle {:cx "5" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :repeatCount "indefinite"}]]
   [:circle {:cx "12" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :begin "0.2s" :repeatCount "indefinite"}]]
   [:circle {:cx "19" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :begin "0.4s" :repeatCount "indefinite"}]]])
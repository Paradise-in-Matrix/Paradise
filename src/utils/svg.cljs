(def chat-bubble-shapes
  [:path {:d "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"}])

(defn chat-bubble [props]
  (build-icon-hiccup props chat-bubble-shapes))

(def search-shapes
  [:g
   [:circle {:cx "11" :cy "11" :r "8"}]
   [:line {:x1 "21" :y1 "21" :x2 "16.65" :y2 "16.65"}]])

(defn search [props]
  (build-icon-hiccup (merge {:animate :scan} props) search-shapes))

(def hash-shapes
  [:g
   [:line {:x1 "4" :y1 "9" :x2 "20" :y2 "9"}]
   [:line {:x1 "4" :y1 "15" :x2 "20" :y2 "15"}]
   [:line {:x1 "10" :y1 "3" :x2 "8" :y2 "21"}]
   [:line {:x1 "16" :y1 "3" :x2 "14" :y2 "21"}]])

(defn hash [props]
  (build-icon-hiccup props hash-shapes))

(def shield-shapes
  [:g
   [:path {:d "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"}]])

(def globe-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "10"}]
   [:line {:x1 "2" :y1 "12" :x2 "22" :y2 "12"}]
   [:path {:d "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"}]])

(def lock-shapes
  [:g
   [:rect {:x "3" :y "11" :width "18" :height "11" :rx "2" :ry "2"}]
   [:path {:d "M7 11V7a5 5 0 0 1 10 0v4"}]])

(def phone-shapes
  [:path {:d "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"}])

(defn phone [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) phone-shapes))

(def pins-shapes
  [:g
   [:path {:d "M12 17v5"}]
   [:path {:d "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"}]])

(defn pins [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) pins-shapes))

(def members-shapes
  [:g
   [:path {:d "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"}]
   [:circle {:cx "9" :cy "7" :r "4"}]
   [:path {:d "M23 21v-2a4 4 0 0 0-3-3.87"}]
   [:path {:d "M16 3.13a4 4 0 0 1 0 7.75"}]])

(defn members [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) members-shapes))

(def more-vertical-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "1"}]
   [:circle {:cx "12" :cy "5" :r "1"}]
   [:circle {:cx "12" :cy "19" :r "1"}]])

(defn more-vertical [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) more-vertical-shapes))

(def menu-shapes
  [:g
   [:line {:x1 "4" :y1 "12" :x2 "20" :y2 "12"}]
   [:line {:x1 "4" :y1 "6" :x2 "20" :y2 "6"}]
   [:line {:x1 "4" :y1 "18" :x2 "20" :y2 "18"}]])

(defn menu [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) menu-shapes))

(def arrow-left-shapes
  [:g
   [:line {:x1 "19" :y1 "12" :x2 "5" :y2 "12"}]
   [:polyline {:points "12 19 5 12 12 5"}]])

(defn arrow-left [props]
  (build-icon-hiccup props arrow-left-shapes))

(def check-circle-green-shapes
  [:polyline {:points "20 6 9 17 4 12"}])

(defn check-circle-green [props]
  (build-icon-hiccup (merge {:stroke "#22c55e"} props) check-circle-green-shapes))

(def chevron-down-shapes
  [:polyline {:points "6 9 12 15 18 9"}])

(defn chevron-down [props]
  (build-icon-hiccup props chevron-down-shapes))


(def home-shapes
  [:g
   [:path {:d "M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"}]
   [:polyline {:points "9 22 9 12 15 12 15 22"}]])

(defn home [props]
  (build-icon-hiccup props home-shapes))

(def user-shapes
  [:g
   [:path {:d "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"}]
   [:circle {:cx "12" :cy "7" :r "4"}]])

(defn user [props]
  (build-icon-hiccup props user-shapes))

(def check-shapes
  [:polyline {:points "20 6 9 17 4 12"}])

(defn check [props]
  (build-icon-hiccup props check-shapes))

(def leave-shapes
  [:g
   [:path {:d "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"}]
   [:polyline {:points "16 17 21 12 16 7"}]
   [:line {:x1 "21" :y1 "12" :x2 "9" :y2 "12"}]])

(defn leave [props]
  (build-icon-hiccup props leave-shapes))

(def download-shapes
  [:g
   [:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
   [:polyline {:points "7 10 12 15 17 10"}]
   [:line {:x1 "12" :y1 "15" :x2 "12" :y2 "3"}]])

(defn download [props]
  (build-icon-hiccup props download-shapes))

(def external-link-shapes
  [:g
   [:path {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]
   [:polyline {:points "15 3 21 3 21 9"}]
   [:line {:x1 "10" :y1 "14" :x2 "21" :y2 "3"}]])

(defn external-link [props]
  (build-icon-hiccup props external-link-shapes))

(def plus-shapes
  [:g
   [:line {:x1 "12" :y1 "5" :x2 "12" :y2 "19"}]
   [:line {:x1 "5" :y1 "12" :x2 "19" :y2 "12"}]])

(defn plus [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) plus-shapes))

(def smiley-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "9"}]
   [:circle {:cx "9" :cy "9" :r "1" :fill "currentColor"}]
   [:circle {:cx "15" :cy "9" :r "1" :fill "currentColor"}]
   [:path {:d "M8 14s1.5 2 4 2 4-2 4-2"
           :fill "none"
           :stroke-linecap "round"}]])

(defn smiley [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) smiley-shapes))

(def send-shapes
  [:g
   [:path {:d "M22 2L11 13"}]
   [:path {:d "M22 2L15 22L11 13L2 9L22 2Z"}]])

(defn send [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) send-shapes))

(def file-shapes
  [:g
   [:path {:d "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"}]
   [:polyline {:points "13 2 13 9 20 9"}]])

(defn file [props]
  (build-icon-hiccup props file-shapes))

(def reply-shapes
  [:g
   [:polyline {:points "9 17 4 12 9 7"}]
   [:path {:d "M20 18v-2a4 4 0 0 0-4-4H4"}]])

(defn reply [props]
  (build-icon-hiccup props reply-shapes))

(def edit-shapes
  [:g
   [:path {:d "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"}]
   [:path {:d "M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"}]])

(defn edit [props]
  (build-icon-hiccup props edit-shapes))

(def exit-shapes
  [:g
   [:line {:x1 "18" :y1 "6" :x2 "6" :y2 "18"}]
   [:line {:x1 "6" :y1 "6" :x2 "18" :y2 "18"}]])

(defn exit [props]
  (build-icon-hiccup props exit-shapes))

(def thread-shapes
  [:g
   [:circle {:cx "7" :cy "7" :r "3"}]
   [:circle {:cx "17" :cy "17" :r "3"}]
   [:path {:d "M7 10v4a2 2 0 0 0 2 2h4"}]
   [:path {:d "M17 14v-4a2 2 0 0 0-2-2h-4"}]])

(defn thread [props]
  (build-icon-hiccup props thread-shapes))

(def copy-shapes
  [:g
   [:rect {:x "9" :y "9" :width "13" :height "13" :rx "2" :ry "2"}]
   [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]])

(defn copy [props]
  (build-icon-hiccup props copy-shapes))

(def link-shapes
  [:g
   [:path {:d "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"}]
   [:path {:d "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"}]])

(defn link [props]
  (build-icon-hiccup props link-shapes))

(def trash-shapes
  [:g
   [:polyline {:points "3 6 5 6 21 6"}]
   [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
   [:line {:x1 "10" :y1 "11" :x2 "10" :y2 "17"}]
   [:line {:x1 "14" :y1 "11" :x2 "14" :y2 "17"}]])

(defn trash [props]
  (build-icon-hiccup props trash-shapes))

(def more-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "1"}]
   [:circle {:cx "19" :cy "12" :r "1"}]
   [:circle {:cx "5" :cy "12" :r "1"}]])

(defn more [props]
  (build-icon-hiccup props more-shapes))

(def double-check-shapes
  [:g
   [:path {:d "M7 12l5 5L22 7"}]
   [:path {:d "M2 12l5 5L9 15"}]])

(defn double-check [props]
  (build-icon-hiccup props double-check-shapes))

(def typing-dots-shapes
  [:g
   [:circle {:cx "5" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :repeatCount "indefinite"}]]
   [:circle {:cx "12" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :begin "0.2s" :repeatCount "indefinite"}]]
   [:circle {:cx "19" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :begin "0.4s" :repeatCount "indefinite"}]]])

(defn typing-dots [props]
  (build-icon-hiccup props typing-dots-shapes))

(def door-open-shapes
  [:g
   [:path {:d "M13 4h3a2 2 0 0 1 2 2v14"}]
   [:path {:d "M2 20h3"}]
   [:path {:d "M13 20h9"}]
   [:path {:d "M10 12v.01"}]
   [:path {:d "M13 4.562v16.157a1 1 0 0 1-1.242.97L5 20V5.562a2 2 0 0 1 1.515-1.94l4-1A2 2 0 0 1 13 4.561Z"}]])

(defn door-open [props]
  (build-icon-hiccup props door-open-shapes))

(def doorbell-shapes
  [:g
   [:rect {:x "7" :y "3" :width "10" :height "18" :rx "2"}]
   [:circle {:cx "12" :cy "15" :r "2"}]
   [:line {:x1 "10" :y1 "7" :x2 "14" :y2 "7"}]
   [:line {:x1 "10" :y1 "9" :x2 "14" :y2 "9"}]])

(defn doorbell [props]
  (build-icon-hiccup props doorbell-shapes))

(def compass-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "10"}]
   [:line {:x1 "12" :y1 "2" :x2 "12" :y2 "22"}]
   [:line {:x1 "2" :y1 "12" :x2 "22" :y2 "12"}]
   [:path {:d "M16.24 7.76l-2.12 6.36-6.36 2.12 2.12-6.36z"}]])

(defn compass [props]
  (build-icon-hiccup props compass-shapes))

(defn members-plus [{:keys [size] :as props}]
  (let [final-size (or size "20px")]
    (build-icon-hiccup
     (assoc props :size final-size)
     [:g
      [:svg {:x "2" :y "2" :width "18" :height "18" :viewBox "0 0 24 24"}
       members-shapes]

      [:svg {:x "14" :y "14" :width "10" :height "10" :viewBox "0 0 24 24"}
       plus-shapes]])))

(def plus-circle-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "10"}]
   [:line {:x1 "12" :y1 "8" :x2 "12" :y2 "16"}]
   [:line {:x1 "8" :y1 "12" :x2 "16" :y2 "12"}]])

(defn plus-circle [props]
  (build-icon-hiccup props plus-circle-shapes))

(def bell-shapes
  [:g
   [:path {:d "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"}]
   [:path {:d "M10.3 21a1.94 1.94 0 0 0 3.4 0"}]])

(defn bell [props]
  (build-icon-hiccup props bell-shapes))

(defn dynamic-room-hash [{:keys [is-public? is-encrypted? is-joinable?] :as props}]
  (let [clean-props (dissoc props :is-public? :is-encrypted? :is-joinable?)]
    (composite-icon
     (merge clean-props
            {:main hash-shapes
             :top-right    (when is-public? globe-shapes)
             :bottom-right (when is-encrypted? shield-shapes)
             :top-left     (when is-joinable? {:shape plus-shapes
                                               :class "active-green"
                                               :size "5px"
                                               :stroke
                                               "var(--active-green, #10b981)"})}))))
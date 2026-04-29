(ns container.timeline.virtualizer
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [container.timeline.item :as item]
            ["@chenglou/pretext" :refer [prepare layout]]
            [container.timeline.item :refer [connected-event-tile]]))

(defn timeline-empty-state [room-id]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.timeline-empty {:style {:padding "40px" :text-align "center"}}
     (if room-id
         (tr [:container.timeline/loading])
         (tr [:container.timeline/no-room])
         )]))


(defn timeline-jump-button [do-jump! focus-mode?]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:button.jump-to-bottom
     {:on-click do-jump!}
     [:span (if focus-mode?
              (tr [:container.timeline/return-to-live])
              (tr [:container.timeline/jump-to-bottom]))]]))

(defn timeline-loading-overlay []
   [:div.spinner-wrapper
    [:div.spinner]])

(defn get-computed-metrics [el]
  (let [style (js/window.getComputedStyle el)
        font-size (.getPropertyValue style "font-size")
        font-family (.getPropertyValue style "font-family")
        line-height (.getPropertyValue style "line-height")
        get-var (fn [var-name fallback]
                  (let [val (str/trim (.getPropertyValue style var-name))]
                    (if (empty? val) fallback (js/parseFloat val))))
        get-str-var (fn [var-name fallback]
                      (let [val (str/trim (.getPropertyValue style var-name))]
                        (if (empty? val) fallback val)))
        avatar-size (get-var "--chat-avatar-size" 32)
        avatar-gap  (get-var "--chat-avatar-gap" 14)]
    {:font              (get-str-var "--chat-body-font" "15.2px sans-serif")
     :line-height       (get-var "--chat-body-line-height" 22.8)
     :avatar-h          avatar-size
     :avatar-col-w      (+ avatar-size avatar-gap)
     :header-h          (get-var "--chat-header-h" 26.8)
     :message-padding-x (get-var "--chat-padding-x" 40)
     :seq-padding       (get-var "--chat-seq-padding" 10)
     :merged-padding    (get-var "--chat-merged-padding" 0)
     :media-margin      (get-var "--chat-media-margin" 0)
     :reaction-row-h    (get-var "--chat-reaction-row-h" 24)
     :system-event-h    (get-var "--chat-system-event-h" 42)
     :edited-label-h    (get-var "--chat-edited-label-h" 16)
     :reply-banner-h    (get-var "--chat-reply-banner-h" 32.4)
     :text-wrap-buffer  (get-var "--chat-text-wrap-buffer" 4)
     :quote-wrap-buffer (get-var "--chat-quote-wrap-buffer" 19)
     :url-wrap-buffer   (get-var "--chat-url-wrap-buffer" 40)
     :code-padding      (get-var "--chat-code-padding" 28)
     :code-font         (get-str-var "--chat-code-font" "13.68px 'fira code', monospace")
     :code-line-height  (get-var "--chat-code-line-height" 20.52)}))


(defn estimate-height [msg width pretext-cache measured-heights theme-metrics]
  (let [id (:id msg)
        cached-measured (get measured-heights id)]
    (if cached-measured
      cached-measured
      (if (= id "read-marker")
        (:virtual-divider-h theme-metrics 49)
        (item/calc-item-height msg width pretext-cache theme-metrics)))))



(defn calculate-layout [events width pretext-cache measured-heights theme-metrics]
  (reduce (fn [acc msg]
            (let [h (estimate-height msg width pretext-cache measured-heights theme-metrics)
                  current-bottom (:total acc)]
              (-> acc
                  (update :items conj (assoc msg :bottom current-bottom :height h))
                  (update :total + h))))
          {:total 0 :items []}
          events))

(defn timeline-measuring-sticks [ruler-ref-fn]
  [:div#timeline-rulers
   {:style {:position "absolute" :visibility "hidden" :pointer-events "none" :z-index -1 :width "100%"}}
   [:div.swipe-foreground.timeline-message.is-message {:id "ruler-row" :ref ruler-ref-fn}
    [:div.timeline-avatar-wrapper {:id "ruler-avatar" :ref ruler-ref-fn}]
    [:div.timeline-content-wrapper {:id "ruler-content-wrapper" :ref ruler-ref-fn}
     [:div.timeline-header {:id "ruler-header" :ref ruler-ref-fn}
      [:span.timeline-sender-name "A"]
      [:span.timeline-timestamp "12:00 PM"]]
     [:div.timeline-reply-banner {:id "ruler-reply" :ref ruler-ref-fn} "Reply Text"]
     [:div.timeline-body {:id "ruler-text-plain" :ref ruler-ref-fn}
      [:div.message-render-container
       [:span.body "A"]]]
     [:div.timeline-body {:id "ruler-text-html" :ref ruler-ref-fn}
      [:div.message-render-container
       [:span.body.formatted [:p {:style {:margin 0}} "A"]]]]
     [:div.timeline-body
      [:pre {:id "ruler-code" :ref ruler-ref-fn} [:code "A"]]]
     [:div {:style {:display "flex"}}
      [:span.timeline-edited-label {:id "ruler-edited" :ref ruler-ref-fn} "(edited)"]]
     [:div.reactions-row {:id "ruler-reaction" :ref ruler-ref-fn}
      [:span.reaction-pill "😀 1"]]]]
   [:div.timeline-system-event {:id "ruler-system" :ref ruler-ref-fn} "System text"]
   [:div.timeline-item-virtual-wrapper {:id "ruler-divider" :ref ruler-ref-fn}
    [:div.timeline-date-separator
     [:div.separator-line]
     [:span.separator-text "Today"]
     [:div.separator-line]]]])

(defn pretext-timeline [room-id]
  (let [!scroll-ref         (atom nil)
        !dist-bottom        (r/atom 0)
        !container-width    (r/atom 400)
        !prepared-cache     (atom {})
        !measured           (r/atom {})
        !latest-events      (atom {})
        !current-height     (atom 0)
        !current-positioned (atom [])
        !current-focus      (atom false)
        !current-was-loading-fwd (atom false)
        !prev-loading-fwd   (atom false)
        !anchor             (atom {:id nil :offset 0 :total-height 0})

        !theme-metrics (r/atom {:font "16px sans-serif" :line-height 22.8})
        metrics-obs (js/ResizeObserver.
                     (fn [entries]
                       (let [parse-px (fn [val fallback]
                                        (let [v (js/parseFloat val)]
                                          (if (js/isNaN v) fallback v)))

                             new-metrics
                             (reduce
                              (fn [acc entry]
                                (let [el    (.-target entry)
                                      id    (.-id el)
                                      style (js/window.getComputedStyle el)]

                                  (case id

                                    "ruler-text-html"
                                    (let [total-h (.-offsetHeight el)
                                          base-lh (:line-height acc 22.8)]
                                      (assoc acc :html-vertical-tax (max 0 (- total-h base-lh))))

                                    "ruler-row"
                                    (assoc acc
                                           :row-w          (.-offsetWidth el)
                                           :seq-padding    (parse-px (.getPropertyValue style "--chat-seq-padding") 10)
                                           :merged-padding (parse-px (.getPropertyValue style "--chat-merged-padding") 5)
                                           :media-margin   (parse-px (.getPropertyValue style "--chat-media-margin") 0))

                                    "ruler-content-wrapper"
                                    (assoc acc :content-w (.-offsetWidth el))

                                    "ruler-reply"
                                    (let [mb (parse-px (.getPropertyValue style "margin-bottom") 0)
                                          mt (parse-px (.getPropertyValue style "margin-top") 0)]
                                      (assoc acc :reply-banner-h (+ (.-offsetHeight el) mb mt)))

                                    "ruler-text-plain"
                                    (let [lh          (parse-px (.getPropertyValue style "line-height") 22.8)
                                          total-h     (.-offsetHeight el)
                                          container-w (.-offsetWidth el)
                                          fw          (.getPropertyValue style "font-weight")
                                          fs          (.getPropertyValue style "font-size")
                                          ff          (.getPropertyValue style "font-family")
                                          raw-font    (str/trim (.getPropertyValue style "font"))
                                          font-str    (if (empty? raw-font)
                                                        (str fw " " fs " " ff)
                                                        raw-font)]
                                      (assoc acc
                                             :line-height       lh
                                             :text-vertical-tax (max 0 (- total-h lh))
                                             :text-node-w       container-w
                                             :font              font-str
                                             :text-wrap-buffer  (parse-px (.getPropertyValue style "--chat-text-wrap-buffer") 8)
                                             :quote-wrap-buffer (parse-px (.getPropertyValue style "--chat-quote-wrap-buffer") 19)))

                                    "ruler-code"
                                    (let [lh          (parse-px (.getPropertyValue style "line-height") 20.52)
                                          pt          (parse-px (.getPropertyValue style "padding-top") 0)
                                          pb          (parse-px (.getPropertyValue style "padding-bottom") 0)
                                          fw          (.getPropertyValue style "font-weight")
                                          fs          (.getPropertyValue style "font-size")
                                          ff          (.getPropertyValue style "font-family")
                                          raw-font    (str/trim (.getPropertyValue style "font"))
                                          font-str    (if (empty? raw-font)
                                                        (str fw " " fs " " ff)
                                                        raw-font)]
                                      (assoc acc
                                             :code-line-height lh
                                             :code-padding     (+ pt pb)
                                             :code-font        font-str))


                                    "ruler-avatar"   (assoc acc :avatar-h (.-offsetHeight el))
                                    "ruler-header"   (assoc acc :header-h (.-offsetHeight el))

                                    "ruler-edited"
                                    (assoc acc
                                           :edited-label-h (.-offsetHeight el)
                                           :edited-label-w (.-offsetWidth el))

                                    "ruler-reaction" (assoc acc :reaction-row-h (.-offsetHeight el) :reaction-pill-h (.-offsetHeight el))
                                    "ruler-system"   (assoc acc :system-event-h (.-offsetHeight el))
                                    "ruler-divider"  (assoc acc :virtual-divider-h (.-offsetHeight el))


                                    acc)))
                              @!theme-metrics
                              entries)]

                         (when (not= new-metrics @!theme-metrics)
                           (reset! !prepared-cache {})
                           (reset! !theme-metrics new-metrics)))))
        !at-bottom?      (r/atom true)
        !show-jump?      (r/atom false)
        !initialized?    (r/atom false)
        !scroll-timer    (atom nil)
        item-resize-obs  (js/ResizeObserver.
                          (fn [entries]
                            (doseq [entry entries]
                              (let [el (.-target entry)
                                    id (.getAttribute el "data-event-id")
                                    rect (.-contentRect entry)
                                    dom-h (.-height rect)]
                                (when (and id (pos? dom-h))
                                  (when-let [msg (or (get @!latest-events id)
                                                     (when (= id "read-marker") {:id id}))]
                                    (let [current-measured (get @!measured id)
                                          estimated (estimate-height msg @!container-width !prepared-cache !measured @!theme-metrics)
                                          diff (js/Math.abs (- dom-h estimated))]
                                      (when (and (> diff 4) (not= current-measured (js/Math.round dom-h)))
                                        (swap! !measured assoc id (js/Math.round dom-h))))))))))

        container-obs    (js/ResizeObserver.
                          (fn [entries]
                            (let [rect (.-contentRect (aget entries 0))]
                              (reset! !container-width (.-width rect)))))]
    (r/create-class
     {:component-did-update
      (fn [this]
        (let [el @!scroll-ref]
          (when el
            (let [state        @!anchor
                  get-dist     (fn [target-el]
                                 (let [st (js/Math.round (.-scrollTop target-el))
                                       max-s (- (.-scrollHeight target-el) (.-clientHeight target-el))]
                                   (if (<= st 0)
                                     (js/Math.abs st)
                                     (if (> st (/ max-s 2))
                                       (max 0 (- max-s st))
                                       0))))
                  current-dist (get-dist el)
                  total-height @!current-height
                  positioned   @!current-positioned
                  focus-mode?  @!current-focus
                  was-loading? @!current-was-loading-fwd]
              (when (not= total-height (:total-height state))
                (log/info "\n==================================")
                (log/info "[TIMELINE-UPDATE] Height shift detected!")
                (log/info "--> Prev Height:" (:total-height state) "| New Height:" total-height)
                (let [old-anchor-item (first (filter #(= (:id %) (:id state)) positioned))]
                  (if old-anchor-item
                    (let [expected-dist (+ (:bottom old-anchor-item) (:offset state))]
                      (if (or focus-mode? was-loading? (> current-dist 5))
                        (do
                          (log/info "--> ACTION: Applying expected ScrollDist natively (" expected-dist ")")
                          (set! (.-scrollTop el) (- expected-dist)))
                        (log/info "--> ACTION: SKIPPED math application. Assuming Live message hug.")))
                    (log/info "--> WARNING: Target Anchor ID NOT FOUND in positioned array! Node unmounted?")))
                (log/info "==================================\n"))

              (let [dist-after-shift (get-dist el)
                    new-anchor       (first (filter #(> (+ (:bottom %) (:height %)) dist-after-shift) positioned))]
                (when new-anchor
                  (reset! !anchor
                          {:id           (:id new-anchor)
                           :offset       (- dist-after-shift (:bottom new-anchor))
                           :total-height total-height})))))))

      :component-will-unmount
      (fn [this]
        (.disconnect container-obs)
        (.disconnect item-resize-obs))

      :reagent-render
      (fn [room-id]
        (let [events           @(re-frame/subscribe [:timeline/current-events room-id])
              events-map       @(re-frame/subscribe [:timeline/events-map room-id])
              _                (reset! !latest-events events-map)
              ordered-events   (keep #(or (get events-map (:id %)) %) (reverse events))
              cnt              (count ordered-events)
              loading?         @(re-frame/subscribe [:timeline/loading-more? room-id])
              loading-forward? @(re-frame/subscribe [:timeline/loading-forward? room-id])
              back-dead?       @(re-frame/subscribe [:timeline/back-dead? room-id])
              jump-target      @(re-frame/subscribe [:timeline/jump-target-id room-id])
              focus-mode?      @(re-frame/subscribe [:room/is-focused? room-id])
              width            @!container-width
              metrics          @!theme-metrics

              layout-data      @(r/track calculate-layout ordered-events width !prepared-cache @!measured metrics)

              total-height     (:total layout-data)
              positioned       (:items layout-data)
              _ (when (not= total-height (:total-height @!anchor))
                  (log/info "\n[DEBUG: RENDER] Height Shift Detected!")
                  (log/info "--> Old Height:" (:total-height @!anchor) "| New Height:" total-height)
                  (log/info "--> Loading Forward?:" loading-forward?))
              visible-window   (let [dist-from-bottom @!dist-bottom
                                     vh (if-let [el @!scroll-ref] (.-clientHeight el) 800)
                                     overscan 2000
                                     w-start (- dist-from-bottom overscan)
                                     w-end (+ dist-from-bottom vh overscan)]
                                 (->> positioned
                                      (drop-while #(let [item-top (+ (:bottom %) (:height %))]
                                                     (< item-top w-start)))
                                      (take-while #(<= (:bottom %) w-end))))
              do-jump!         (fn []
                                 (reset! !at-bottom? true)
                                 (if focus-mode?
                                   (do
                                     (reset! !initialized? false)
                                     (re-frame/dispatch [:room/jump-to-live-bottom room-id]))
                                   (when-let [el @!scroll-ref]
                                     (set! (.-scrollTop el) 0))))]

          (reset! !current-height total-height)
          (reset! !current-positioned positioned)
          (reset! !current-focus focus-mode?)
          (reset! !current-was-loading-fwd @!prev-loading-fwd)
          (reset! !prev-loading-fwd loading-forward?)

          (when (and @!scroll-ref (pos? cnt) (not @!initialized?))
            (js/setTimeout
             (fn []
               (let [el @!scroll-ref]
                 (when el
                   (if jump-target
                     (let [target-item (first (filter #(= (:id %) jump-target) positioned))]
                       (if target-item
                         (set! (.-scrollTop el) (- (:bottom target-item)))
                         (set! (.-scrollTop el) 0)))
                     (set! (.-scrollTop el) 0))
                   (reset! !initialized? true)
                   (reset! !at-bottom? (not jump-target)))))
             0))

          (when (and @!scroll-ref
                     @!initialized?
                     (pos? cnt)
                     (not loading?)
                     (not back-dead?)
                     (< total-height (.-clientHeight @!scroll-ref)))
            (js/setTimeout
             (fn []
               (re-frame/dispatch [:sdk/back-paginate room-id]))
             50))

          [:div.timeline-wrapper

           [timeline-measuring-sticks
            (fn [el]
              (when el (.observe metrics-obs el)))]

           [:div.timeline-item {:style {:position "absolute" :visibility "hidden" :pointer-events "none" :z-index -1}}
            [:div.timeline-body {:ref #(when % (.observe metrics-obs %))}
             "Measuring Stick"]]

           [:div.timeline-messages
            {:ref
             (fn [el]
               (reset! !scroll-ref el)
               (if el
                 (.observe container-obs el)
                 (.disconnect container-obs)))

             :style {:overflow-anchor "none"
                     :overflow-y "auto"}

             :class (when jump-target "jumping-animation")
             :on-scroll (fn [e]
                          (let [target           (.-currentTarget e)
                                scroll-top       (js/Math.round (.-scrollTop target))
                                max-scroll       (- (.-scrollHeight target) (.-clientHeight target))
                                dist-from-bottom (if (<= scroll-top 0)
                                                   (js/Math.abs scroll-top)
                                                   (if (> scroll-top (/ max-scroll 2))
                                                     (max 0 (- max-scroll scroll-top))
                                                     0))
                                dist-from-top    (max 0 (- max-scroll dist-from-bottom))
                                at-bottom        (<= dist-from-bottom 30)]

                            (when (not= @!dist-bottom dist-from-bottom)
                              (log/info "\n[DEBUG: ON-SCROLL] Dist from bottom changed!")
                              (log/info "--> Raw ScrollTop:" scroll-top)
                              (log/info "--> Calculated Dist From Bottom:" dist-from-bottom)
                              (reset! !dist-bottom dist-from-bottom))

                            (when (and (pos? cnt) (not loading?) @!initialized?)
                              (reset! !at-bottom? at-bottom)
                              (when-not @!scroll-timer
                                (reset! !scroll-timer
                                        (js/setTimeout
                                         (fn []
                                           (reset! !scroll-timer nil)
                                           (reset! !show-jump? (> dist-from-bottom 600))

                                           (when (and (<= dist-from-top 600) (not loading?))
                                             (re-frame/dispatch [:sdk/back-paginate room-id]))

                                           (when (and focus-mode?
                                                      (<= dist-from-bottom 600)
                                                      (not loading-forward?))
                                             (log/info "[DEBUG: DISPATCH] Forward Paginate Triggered.")
                                             (re-frame/dispatch [:sdk/forward-paginate room-id])))
                                         10))))))}

            (when loading?
              [timeline-loading-overlay])

            (if (zero? cnt)
              [timeline-empty-state room-id]

              (let [first-vis  (first visible-window)
                    last-vis   (last visible-window)
                    bottom-gap (if first-vis (:bottom first-vis) total-height)
                    top-gap    (if last-vis (max 0 (- total-height (+ (:bottom last-vis) (:height last-vis)))) 0)]
                (into [:<>]
                      (concat
                       [^{:key "bottom-gap"}
                        [:div.timeline-bottom-gap
                         {:style {:height (str bottom-gap "px")
                                  :flex-shrink 0
                                  :width "100%"}}]]
                       (for [{:keys [id height] :as item} visible-window]
                         ^{:key id}
                         [:div.item-wrapper
                          {:style {:min-height (str height "px")
                                   :width "100%"
                                   :margin "0"
                                   :box-sizing "border-box"}}

                          [:div.timeline-item
                           {:data-event-id id
                            :ref (fn [el]
                                   (when el
                                     (.observe item-resize-obs el)))
                            :class (when (= id jump-target) "is-jump-target")}
                           [connected-event-tile room-id item]]])
                       [^{:key "top-gap"}
                        [:div.timeline-top-gap
                         {:style {:height (str top-gap "px")
                                  :overflow-anchor "none"
                                  :flex-shrink 0
                                  :width "100%"}}]]))))

            (when (and focus-mode? loading-forward?)
              [:div.spinner-wrapper
               [:div.spinner]])]
           (when (and @!show-jump? (not @!at-bottom?))
             [timeline-jump-button do-jump! focus-mode?])]))})))
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
  [:div.timeline-loading-overlay
   [:div.timeline-loading-spinner-container
    [:div.spinner]]])

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
    {:font             (get-str-var "--chat-body-font" "15.2px sans-serif")
     :line-height      (get-var "--chat-body-line-height" 22.8)

     :avatar-h          avatar-size
     :avatar-col-w      (+ avatar-size avatar-gap)
     :header-h          (get-var "--chat-header-h" 26.8)
     :message-padding-x (get-var "--chat-padding-x" 40)
     :seq-padding       (get-var "--chat-seq-padding" 10)
     :merged-padding    (get-var "--chat-merged-padding" 0)
     :media-margin      (get-var "--chat-media-margin" 0)
     :reaction-row-h    (get-var "--chat-reaction-row-h" 24)
     :system-event-h    (get-var "--chat-system-event-h" 52)
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
      (item/calc-item-height msg width pretext-cache theme-metrics))))

(defn calculate-layout [events width pretext-cache measured-heights theme-metrics]
  (reduce (fn [acc msg]
            (let [h (estimate-height msg width pretext-cache measured-heights theme-metrics)
                  current-bottom (:total acc)]
              (-> acc
                  (update :items conj (assoc msg :bottom current-bottom :height h))
                  (update :total + h))))
          {:total 0 :items []}
          events))

(defn pretext-timeline [room-id]
  (r/with-let [!scroll-ref      (atom nil)
               !scroll-top      (r/atom 0)
               !container-width (r/atom 400)
               !prepared-cache  (atom {})
               !measured        (r/atom {})
               !theme-metrics   (r/atom {:font "16px sans-serif" :line-height 24})
               !latest-events   (atom {})
               metrics-obs      (js/ResizeObserver.
                                 (fn [entries]
                                   (let [el (.-target (aget entries 0))
                                         metrics (get-computed-metrics el)]
                                     (when (not= metrics @!theme-metrics)
                                       (reset! !prepared-cache {})
                                       (reset! !theme-metrics metrics)))))
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
                                         (when-let [msg (get @!latest-events id)]
                                           (let [current-measured (get @!measured id)
                                                 estimated (estimate-height msg @!container-width !prepared-cache !measured @!theme-metrics)
                                                 diff (js/Math.abs (- dom-h estimated))]
                                             (when (and (> diff 2) (not= current-measured (js/Math.round dom-h)))
                                               (log/warn "Layout Correction on" id "| Math:" estimated "| DOM:" dom-h)
                                               (log/warn "Message event: " msg)
                                               (swap! !measured assoc id (js/Math.round dom-h))))))))))
               container-obs    (js/ResizeObserver.
                                 (fn [entries]
                                   (let [rect (.-contentRect (aget entries 0))]
                                     (reset! !container-width (.-width rect)))))]
    (fn [room-id]
      (let [events           @(re-frame/subscribe [:timeline/current-events room-id])
             events-map       @(re-frame/subscribe [:timeline/events-map room-id])
            _                (reset! !latest-events events-map)
            ordered-events   (keep #(or (get events-map (:id %)) %) (reverse events))
            cnt              (count ordered-events)
            loading?         @(re-frame/subscribe [:timeline/loading-more? room-id])
            loading-forward? @(re-frame/subscribe [:timeline/loading-forward? room-id])
            jump-target      @(re-frame/subscribe [:timeline/jump-target-id room-id])
            focus-mode?      @(re-frame/subscribe [:room/is-focused? room-id])
            width            @!container-width
            metrics          @!theme-metrics

            layout-data      @(r/track calculate-layout ordered-events width !prepared-cache @!measured metrics)

            total-height     (:total layout-data)
            positioned       (:items layout-data)
            visible-window   (let [st @!scroll-top
                                   vh (if-let [el @!scroll-ref] (.-clientHeight el) 800)
                                   overscan 800
                                   max-s (max 0 (- total-height vh))
                                   dist-from-bottom (if (<= st 0)
                                                      (js/Math.abs st)
                                                      (if (> st (/ max-s 2))
                                                        (max 0 (- max-s st))
                                                        0))
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

        [:div.timeline-wrapper
         {:ref (fn [el]
                 (if el
                   (.observe container-obs el)
                   (.disconnect container-obs)))}

         [:div.timeline-item {:style {:position "absolute" :visibility "hidden" :pointer-events "none" :z-index -1}}
          [:div.timeline-body {:ref #(when % (.observe metrics-obs %))}
           "Measuring Stick"]]

         [:div.timeline-messages
          {:ref #(reset! !scroll-ref %)
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

                          (when (not= @!scroll-top scroll-top)
                            (reset! !scroll-top scroll-top))

                          (when (and (pos? cnt) (not loading?) @!initialized?)
                            (reset! !at-bottom? at-bottom)
                            (when-not @!scroll-timer
                              (reset! !scroll-timer
                                      (js/setTimeout
                                       (fn []
                                         (reset! !scroll-timer nil)
                                         (reset! !show-jump? (> dist-from-bottom 600))

                                         (when (and (<= dist-from-top 500) (not loading?))
                                           (re-frame/dispatch [:sdk/back-paginate room-id]))

                                         (when (and focus-mode?
                                                    (<= dist-from-bottom 500)
                                                    (not loading-forward?))
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
                      [:div.virtua-top-gap
                       {:style {:height (str top-gap "px")
                                :flex-shrink 0
                                :width "100%"}}]]))))

          (when (and focus-mode? loading-forward?)
            [:div.spinner-wrapper
             [:div.spinner]])]

         (when (and @!show-jump? (not @!at-bottom?))
           [timeline-jump-button do-jump! focus-mode?])]))
    (finally
      (.disconnect container-obs)
      (.disconnect item-resize-obs))))
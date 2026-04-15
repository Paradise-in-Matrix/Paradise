(ns container.timeline.virtualizer
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            ["@chenglou/pretext" :refer [prepare layout]]
            [container.timeline.item :refer [connected-event-tile]]))

(defn timeline-empty-state []
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.timeline-empty {:style {:padding "40px" :text-align "center"}}
     (tr [:container.timeline/loading])]))

(defn timeline-jump-button [do-jump! focus-mode?]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:button.jump-to-bottom
     {:style {:position "absolute"
              :bottom "20px"
              :right "24px"
              :z-index 10
              :display "flex"
              :align-items "center"
              :gap "8px"}
      :on-click do-jump!}
     [:span (if focus-mode?
              (tr [:container.timeline/return-to-live])
              (tr [:container.timeline/jump-to-bottom]))]]))

(defn get-computed-metrics [el]
  (let [style (js/window.getComputedStyle el)
        font-size (.getPropertyValue style "font-size")
        font-family (.getPropertyValue style "font-family")
        line-height (.getPropertyValue style "line-height")]
    {:font (str font-size " " font-family)
     :line-height (if (= line-height "normal")
                    (* (js/parseFloat font-size) 1.2)
                    (js/parseFloat line-height))}))

(defn estimate-height [msg width pretext-cache measured-heights theme-metrics]
  (let [id (:id msg)
        cached-measured (get measured-heights id)]
    (if cached-measured
      cached-measured
      (let [type (:type msg)
            content-tag (:content-tag msg)]
        (cond
          (or (= type :virtual) (= type "virtual") (str/starts-with? (str id) "virtual-divider")) 49
          (and content-tag (not (#{"MsgLike" "Image" "Video" "Sticker" "File" "Text"} content-tag))) 53
          :else
          (let [is-sequence-start? (not (:merge-with-prev? msg))
                avatar-h 32
                avatar-col-w 46
                header-h 26.8
                seq-padding 10
                merged-padding 0
                message-padding-x 40
                media-margin 4
                reaction-h (if (seq (:reactions msg)) 24 0)

                content (:content msg)
                info (or (:info content) (get-in content [:inner :content :info]))
                w (js/parseFloat (:w info 0))
                h (js/parseFloat (:h info 0))
                available-w (max 0 (- width avatar-col-w message-padding-x))
                actual-tag (or (get-in content [:inner :tag]) content-tag)
                is-media? (#{"Image" "Video" "Sticker"} actual-tag)

                final-height
                (if is-media?
                  (let [valid-dims? (and (pos? w) (pos? h))
                        _ (log/warn valid-dims?)
                        default-ratio (case actual-tag "Video" 1.77 "Sticker" 1.0 1.33)
                        calc-w (if valid-dims? (min w available-w 400) 300)
                        calc-h (if valid-dims? (min (* calc-w (/ h w)) 350) (min (/ calc-w default-ratio) 350))
                        media-h (+ calc-h media-margin)]
                    (if is-sequence-start?
                      (+ (max avatar-h (+ header-h media-h)) seq-padding reaction-h)
                      (max avatar-h (+ media-h merged-padding reaction-h))))
                  (let [raw-txt (or (:body msg) (:body content) (get-in content [:inner :content :body]) "")
                        txt-str (if (string? raw-txt) raw-txt (str raw-txt))
                        html-txt (or (get-in content [:inner :content :html]) "")
                        has-code-block? (or (clojure.string/includes? txt-str "```")
                                            (clojure.string/includes? html-txt "<pre>"))
                        font-str (if has-code-block?
                                   "13.68px 'fira code', monospace"
                                   (:font theme-metrics "16px sans-serif"))
                        lh (if has-code-block?
                             20.52
                             (:line-height theme-metrics 22.8))
                        code-padding (if has-code-block? 28 0)

                        cache-key (str id "-" available-w "-" has-code-block?)
                        cached-text-h (get @pretext-cache cache-key)
                        final-text-h
                        (or cached-text-h
                            (let [lines (clojure.string/split txt-str #"\n")
                                  total-h (reduce (fn [acc line]
                                                    (if (empty? (clojure.string/trim line))
                                                      (+ acc lh)
                                                      (let [prep (prepare line font-str)
                                                            raw (try (layout prep available-w lh) (catch js/Error _ nil))]
                                                        (+ acc (if raw (.-height raw) lh)))))
                                                  0
                                                  lines)]
                              (swap! pretext-cache assoc cache-key total-h)
                              total-h))]

                    (if is-sequence-start?
                      (+ (max avatar-h (+ header-h final-text-h code-padding)) seq-padding reaction-h)
                      (max avatar-h (+ final-text-h code-padding merged-padding reaction-h)))))]
            (log/info "Calculated height for ID:" id "->" final-height "| Media?:" is-media?)
            final-height))))))

(defn calculate-layout [events width pretext-cache measured-heights theme-metrics]
  (reduce (fn [acc msg]
            (let [h (estimate-height msg width pretext-cache measured-heights theme-metrics)
                  current-bottom (:total acc)]
              (-> acc
                  (update :items conj (assoc msg :bottom current-bottom :height h))
                  (update :total + h))))
          {:total 0 :items []}
          events))

(defn test-timeline [room-id]
  (log/info "Outer re-render")
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
      (log/info "Rendered")
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

        [:div.virtua-timeline-wrapper
         {:ref (fn [el]
                 (if el
                   (.observe container-obs el)
                   (.disconnect container-obs)))
          :style {:flex 1
                  :position "relative"
                  :display "flex"
                  :flex-direction "column"
                  :min-height 0
                  :opacity (if (or @!initialized? (zero? cnt)) 1 0)
                  :transition "opacity 0.15s ease-in-out"}}

         [:div.timeline-item {:style {:position "absolute" :visibility "hidden" :pointer-events "none" :z-index -1}}
          [:div.timeline-body {:ref #(when % (.observe metrics-obs %))}
           "Measuring Stick"]]

         [:div.timeline-messages
          {:ref #(reset! !scroll-ref %)
           :class (when jump-target "jumping-animation")
           :style {:display "flex"
                   :flex-direction "column-reverse"
                   :flex 1
                   :height "100%"
                   :overflow-y "auto"
                   :position "relative"}
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
            [:div {:style {:position "absolute"
                           :top "20px"
                           :width "100%"
                           :display "flex"
                           :justify-content "center"
                           :z-index 100
                           :pointer-events "none"}}
             [:div {:style {:background "var(--bg-color, white)"
                            :padding "8px"
                            :border-radius "50%"
                            :box-shadow "0 2px 10px rgba(0,0,0,0.1)"}}
              [:div.spinner]]])

          (if (zero? cnt)
            [timeline-empty-state]

            (let [first-vis  (first visible-window)
                  last-vis   (last visible-window)
                  bottom-gap (if first-vis (:bottom first-vis) total-height)
                  top-gap    (if last-vis (max 0 (- total-height (+ (:bottom last-vis) (:height last-vis)))) 0)]
              (into [:<>]
                    (concat
                     [^{:key "bottom-gap"}
                      [:div.virtua-bottom-gap
                       {:style {:height (str bottom-gap "px")
                                :flex-shrink 0
                                :width "100%"}}]]
                     (for [{:keys [id height] :as item} visible-window]
                       ^{:key id}
                       [:div.virtua-item-wrapper
                        {:style {:min-height (str height "px")
                                 :width "100%"
                                 :margin "0"
                                 :box-sizing "border-box"}}
                        [:div.timeline-item
                         {:data-event-id id
                          :ref (fn [el]
                                 (when el
                                   (.observe item-resize-obs el)))
                          :class (when (= id jump-target) "is-jump-target")
                          :style {:margin "0"}}
                         [connected-event-tile room-id item]]])
                     [^{:key "top-gap"}
                      [:div.virtua-top-gap
                       {:style {:height (str top-gap "px")
                                :flex-shrink 0
                                :width "100%"}}]]))))

          (when (and focus-mode? loading-forward?)
            [:div {:style {:min-height "60px"
                           :display "flex"
                           :align-items "center"
                           :justify-content "center"
                           :padding-bottom "20px"}}
             [:div.spinner]])]

         (when (and @!show-jump? (not @!at-bottom?))
           [timeline-jump-button do-jump! focus-mode?])]))
    (finally
      (.disconnect container-obs)
      (.disconnect item-resize-obs))))
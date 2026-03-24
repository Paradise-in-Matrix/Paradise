(ns container.call.call-container
  (:require [reagent.core :as r]
            [taoensso.timbre :as log]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]))

(defonce primary-iframe-ref (r/atom nil))
(defonce backup-iframe-ref  (r/atom nil))
(defonce host-rect (r/atom nil))


(defn apply-iframe-sound-state! [iframe-ref sound-on?]
  (when-let [iframe @iframe-ref]
    (try
      (when-let [doc (or (.-contentDocument iframe) (.. iframe -contentWindow -document))]
        (doseq [el (js/Array.from (.querySelectorAll doc "audio, video"))]
          (set! (.-muted el) (not sound-on?))))
      (catch :default e
        (log/error "Failed to apply sound state:" e)))))

(defn attach-iframe-observers! [iframe-ref]
  (letfn [(check-dom []
            (when-let [iframe @iframe-ref]
              (try
                (let [doc (or (.-contentDocument iframe) (.. iframe -contentWindow -document))]
                  (if doc
                    (do
                      (let [media-observer (js/MutationObserver.
                                            (fn [_]
                                              (let [deafened? (get-in @rf-db/app-db [:call :deafened?] false)]
                                                (when deafened?
                                                  (apply-iframe-sound-state! iframe-ref false)))))]
                        (.observe media-observer (.-body doc) #js {:childList true :subtree true}))
                      (if-let [share-btn (.querySelector doc "[data-testid='incall_screenshare']")]
                        (let [share-observer (js/MutationObserver.
                                              (fn [_]
                                                (let [is-active? (= (.getAttribute share-btn "aria-pressed") "true")]
                                                  (rf/dispatch [:call/set-screen-sharing is-active?]))))]
                          (.observe share-observer share-btn #js {:attributes true :attributeFilter #js ["class" "aria-pressed"]}))
                        (log/warn "Screen share button not found; observer not attached.")))
                    (js/setTimeout check-dom 400)))
                (catch :default _
                  (js/setTimeout check-dom 400)))))]
    (check-dom)))


(defn persistent-call-container []
  (let [call-state   @(rf/subscribe [:call/state])
        tr           @(rf/subscribe [:i18n/tr])

        primary?     (get call-state :primary-iframe? true)
        rect         @host-rect
        active-style (if rect
                       {:position "fixed" :top (str (:top rect) "px") :left (str (:left rect) "px")
                        :width (str (:width rect) "px") :height (str (:height rect) "px")
                        :border "none" :pointer-events "auto" :display "block"}
                       {:border "none" :display "none"})
        hidden-style {:border "none" :display "none"}]
    [:div.persistent-call-layer
     {:style {:position "fixed" :top 0 :left 0 :width "100%" :height "100%"
              :pointer-events "none" :z-index 99
              :display (if (and (:mobile? call-state) (:chat-open? call-state)) "none" "block")}}
     [:iframe
      {:ref     #(reset! primary-iframe-ref %)
       :title   (tr [:container.calls/main-iframe])
       :style   (if primary? active-style hidden-style)
       :sandbox "allow-forms allow-scripts allow-same-origin allow-popups allow-modals allow-downloads"
       :allow   "camera; microphone; display-capture; autoplay; encrypted-media; fullscreen;"
       :on-load #(attach-iframe-observers! primary-iframe-ref)
       :src     "about:blank"}]
     [:iframe
      {:ref     #(reset! backup-iframe-ref %)
       :title   (tr [:container.calls/backup-iframe])
       :style   (if-not primary? active-style hidden-style)
       :sandbox "allow-forms allow-scripts allow-same-origin allow-popups allow-modals allow-downloads"
       :allow   "camera; microphone; display-capture; autoplay; encrypted-media; fullscreen;"
       :on-load #(attach-iframe-observers!   backup-iframe-ref)
       :src     "about:blank"}]]))

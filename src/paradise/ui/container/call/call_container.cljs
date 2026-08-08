(ns paradise.ui.container.call.call-container
  (:require [reagent.core :as r]
            [taoensso.timbre :as log]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [paradise.shared.client.state :as state]
            ))

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
                                              (let [db @rf-db/app-db
                                                    deafened? (get-in db [:call :deafened?] false)
                                                    active-iframe (get-in db [:call :active-iframe])
                                                    is-primary-ref? (= iframe-ref primary-iframe-ref)
                                                    is-active? (if active-iframe
                                                                 (= active-iframe (if is-primary-ref? :primary :backup))
                                                                 (= (get-in db [:call :visible-iframe] :primary) (if is-primary-ref? :primary :backup)))]
                                                (if is-active?
                                                  (apply-iframe-sound-state! iframe-ref (not deafened?))
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

(defn ^:ui persistent-call-container []
  (let [call-state   @(rf/subscribe [:call/state])
        tr           @(rf/subscribe [:i18n/tr])
        visible      (get call-state :visible-iframe :primary)
        primary-vis? (= visible :primary)
        rect         @host-rect
        offscreen-style {:position "absolute" :top "-9999px" :left "-9999px"
                         :width "1px" :height "1px" :border "none"
                         :opacity 0 :pointer-events "none" :z-index -1}
        active-style    (if rect
                          {:position "fixed" :top (str (:top rect) "px") :left (str (:left rect) "px")
                           :width (str (:width rect) "px") :height (str (:height rect) "px")
                           :border "none" :pointer-events "auto" :display "block"}
                          offscreen-style)
        hidden-style    offscreen-style]
    [:div.persistent-call-layer
     {:style {:position "fixed" :top 0 :left 0 :width "100%" :height "100%"
              :pointer-events "none" :z-index 99
              :display (if (and (:mobile? call-state) (:chat-open? call-state)) "none" "block")}}
     [:iframe {:ref #(reset! primary-iframe-ref %) :title (tr [:container.calls/main-iframe])
               :style (if primary-vis? active-style hidden-style)
               :sandbox "allow-forms allow-scripts allow-same-origin allow-popups allow-modals allow-downloads"
               :allow "camera; microphone; display-capture; autoplay; encrypted-media; fullscreen;"
               :on-load #(attach-iframe-observers! primary-iframe-ref)
               :src "about:blank"}]
     [:iframe {:ref #(reset! backup-iframe-ref %) :title (tr [:container.calls/backup-iframe])
               :style (if-not primary-vis? active-style hidden-style)
               :sandbox "allow-forms allow-scripts allow-same-origin allow-popups allow-modals allow-downloads"
               :allow "camera; microphone; display-capture; autoplay; encrypted-media; fullscreen;"
               :on-load #(attach-iframe-observers! backup-iframe-ref)
               :src "about:blank"}]

     (state/get-slot :call-container/background)]))

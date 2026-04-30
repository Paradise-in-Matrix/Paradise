(ns input.base
  (:require [promesa.core :as p]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [input.drafts :refer [attachment-preview]]
            [input.composer :refer [tiptap-component get-matrix-formatted-body prepare-html-for-editor]]
            [input.emotes :refer [emoji-sticker-board]]
            [input.autocomplete :refer [suggestion-menu]]
            [reagent.core :as r]
            [utils.images :refer [mxc->url]]
            [utils.helpers :refer [truncate-name]]
            [utils.svg :as icons]
            [utils.macros :refer [defui]]
            [cljs.core.async :refer [go <!]]
            [cljs-workers.core :as main]
            [client.state :as state]
            [plugins :as plugins]))

(re-frame/reg-event-fx
 :sdk/upload-media
 (fn [_ [_ file on-success-event]]
   (let [reader (js/FileReader.)]
     (if file
       (do
         (set! (.-onload reader)
               (fn [e]
                 (let [buffer (.. e -target -result)
                       mime   (.-type file)]
                   (if-let [pool @state/!engine-pool]
                     (go
                       (let [res (<! (main/do-with-pool! pool {:handler :upload-media
                                                               :arguments {:mime mime :buffer buffer}}))]
                         (if (= (:status res) "success")
                           (re-frame/dispatch (conj on-success-event (:url res)))
                           (log/error "Upload failed:" (:msg res)))))
                     (log/error "No engine pool for upload")))))
         (.readAsArrayBuffer reader file)
         {:db (assoc (re-frame/db) :uploading-files? true)})
       (do (log/warn "Missing file for upload") {})))))






(defn extract-metadata [raw-file]
  (p/create
   (fn [resolve _]
     (let [mime      (.-type raw-file)
           is-image? (str/starts-with? mime "image/")
           is-video? (str/starts-with? mime "video/")
           reader    (js/FileReader.)]
       (set! (.-onload reader)
             (fn [e]
               (let [buffer      (.. e -target -result)
                     base-att    {:buffer      buffer
                                  :mime        mime
                                  :filename    (.-name raw-file)
                                  :size        (.-size raw-file)
                                  :preview-url (js/URL.createObjectURL raw-file)}]
                 (if (or is-image? is-video?)
                   (let [el  (if is-image? (js/Image.) (js/document.createElement "video"))
                         url (js/URL.createObjectURL raw-file)]
                     (if is-image?
                       (set! (.-onload el)
                             (fn []
                               (js/URL.revokeObjectURL url)
                               (resolve (assoc base-att :width (.-width el) :height (.-height el)))))
                       (set! (.-onloadedmetadata el)
                             (fn []
                               (js/URL.revokeObjectURL url)
                               (resolve (assoc base-att :width (.-videoWidth el) :height (.-videoHeight el))))))
                     (set! (.-src el) url))
                   (resolve base-att)))))
       (.readAsArrayBuffer reader raw-file)))))

(re-frame/reg-event-fx
 :sdk/handle-file-drop
 (fn [_ [_ room-id files]]
   (doseq [raw-file files]
     (-> (extract-metadata raw-file)
         (p/then (fn [attachment]
                   (re-frame/dispatch [:composer/add-attachment room-id attachment])))))
   {}))



(re-frame/reg-event-fx
 :composer/submit
 (fn [{:keys [db]} [_ room-id text html]]
   (let [attachments (get-in db [:drafts room-id :attachments])
         raw-context (get-in db [:input-context room-id])
         worker-context (when raw-context
                          {:mode        (:mode raw-context)
                           :target      (get-in raw-context [:target :id])
                           :msg-tag     (get-in raw-context [:target :content :inner :tag])
                           :ffi-content (get-in raw-context [:target :content :inner :content :ffi-content])})
         pool        @state/!engine-pool]
     (if (seq attachments)
       (go
         (let [res (<! (main/do-with-pool! pool {:handler :send-attachments
                                                 :arguments {:room-id room-id
                                                             :attachments attachments
                                                             :text text
                                                             :html html}}))]
           (when-not (#{"success" :success} (:status res))
             (log/error "Failed to send attachments:" (:msg res)))))
       (go
         (let [res (<! (main/do-with-pool! pool {:handler :send-message
                                                 :arguments {:room-id room-id
                                                                 :text text
                                                                 :html html
                                                                 :context worker-context}}))]
           (when-not (#{"success" :success} (:status res))
             (log/error "Failed to send message:" (:msg res))))))

     (doseq [att attachments]
       (when (:preview-url att)
         (js/URL.revokeObjectURL (:preview-url att))))

     {:db (-> db
              (assoc-in [:composer room-id] {:text "" :html "" :loaded-text "" :uploading? false})
              (assoc-in [:drafts room-id :attachments] [])
              (update :input-context dissoc room-id))
      :dispatch [:composer/persist-draft room-id]})))

(re-frame/reg-event-fx
 :sdk/send-sticker
 (fn [_ [_ room-id mxc alt-text info]]
   (if-let [pool @state/!engine-pool]
     (go
       (let [res (<! (main/do-with-pool! pool {:handler :send-raw-sticker
                                               :arguments {:room-id room-id
                                                           :mxc mxc
                                                           :alt-text alt-text
                                                           :info info}}))]
         (if (= (:status res) "success")
           (log/info "Sticker sent!")
           (log/error "Sticker failed:" (:msg res)))))
     (log/error "No engine pool to send sticker"))
   {}))

(re-frame/reg-event-db
 :sdk/upload-complete
 (fn [db _]
   (assoc db :uploading-files? false)))

(re-frame/reg-sub
 :input/uploading?
 (fn [db _]
   (:uploading-files? db false)))

(re-frame/reg-event-db
 :input/set-context
 (fn [db [_ room-id mode target-item]]
   (assoc-in db [:input-context room-id] {:mode mode :target target-item})))

(re-frame/reg-event-db
 :input/clear-context
 (fn [db [_ room-id]]
   (update db :input-context dissoc room-id)))

(re-frame/reg-sub
 :input/context
 (fn [db [_ room-id]]
   (get-in db [:input-context room-id])))

(re-frame/reg-fx
 :input/focus-composer
 (fn [should-focus?]
   (when should-focus?
     (js/setTimeout
      (fn []
        (when-let [composer (.querySelector js/document ".ProseMirror")]
          (.focus composer)))
      50))))

(re-frame/reg-sub
 :ui/inline-emoji-open?
 :<- [:ui/active-popover]
 (fn [active-popover _]
   (= (:id active-popover) :inline-emoji)))


(defn inline-editor [item active-id]
  (r/with-let [!editor (r/atom nil)]
    (fn [item active-id]
      (let [m-content    (get-in item [:content :inner :content])
            raw-html     (:html m-content)
            initial-text (if (seq raw-html)
                           (prepare-html-for-editor raw-html)
                           (or (:body m-content) ""))]
        [:div.inline-editor-wrapper
         [:div.inline-editor-box
          [:> tiptap-component
           #js {:activeId active-id
                :loadedText initial-text
                :onEditorReady #(reset! !editor %)
                :onSend (fn [text html]
                          (let [matrix-html (get-matrix-formatted-body @!editor)]
                            (re-frame/dispatch [:msg/edit active-id item text matrix-html])
                            (re-frame/dispatch [:input/clear-context active-id])))}]]
         [:div.inline-editor-hint
          "escape to "
          [:span.link-btn {:on-click #(re-frame/dispatch [:input/clear-context active-id])} "cancel"]
          " • enter to "
          [:span.link-btn {:on-click (fn []
                                       (when-let [ed @!editor]
                                         (let [text (.getText ed)
                                               matrix-html (get-matrix-formatted-body ed)]
                                           (re-frame/dispatch [:msg/edit active-id item text matrix-html])
                                           (re-frame/dispatch [:input/clear-context active-id]))))} "save"]]]))))


(defui timeline-send-button [{:keys [submit-message! editor attachments]}]
  [:button.timeline-send-btn
   {:on-click (fn [e]
                (.preventDefault e)
                (.stopPropagation e)
                (submit-message!))}
   [plugins/plugin-slot :composer-send {:room-id active-id}]
   [icons/send]])

(defui timeline-emoji-button [{:keys [on-click active? room-id]}]
  [:button.timeline-emoji-btn
   {:on-click (fn [e]
                (.stopPropagation e)
                (when on-click (on-click)))}
   [icons/smiley]
   [plugins/plugin-slot :composer-emojis {:room-id room-id}]])

(defui message-input []
  (r/with-let [!editor       (r/atom nil)
               !sug-command  (r/atom nil)]
    (fn []
      (let [active-id      @(re-frame/subscribe [:rooms/active-id])
            uploading?     @(re-frame/subscribe [:input/uploading?])
            attachments    @(re-frame/subscribe [:composer/attachments active-id])
            loaded-text    @(re-frame/subscribe [:composer/loaded-text active-id])
            cached-html    @(re-frame/subscribe [:composer/cached-html active-id])
            context        @(re-frame/subscribe [:input/context active-id])
            picker-open?   @(re-frame/subscribe [:ui/inline-emoji-open?])
            tr             @(re-frame/subscribe [:i18n/tr])
            submit-message! (fn []
                              (when-let [ed @!editor]
                                (let [text        (.getText ed)
                                      matrix-html (get-matrix-formatted-body ed)
                                      can-send?   (or (not (str/blank? text))
                                                      (str/includes? matrix-html "<img")
                                                      (seq attachments))]
                                  (if can-send?
                                    (do
                                      (re-frame/dispatch [:composer/submit active-id text matrix-html])
                                      (-> ed .chain .clearContent .run)
                                      true)
                                    false))))]
        [:div.timeline-input-outer
         [suggestion-menu
          (fn [item]
            (when-let [cmd @!sug-command]
              (cmd #js {:props item})))]
         (when picker-open?
           [emoji-sticker-board
            {:inline?  true
             :on-close #(re-frame/dispatch [:ui/close-popover])
             :on-send-sticker
             (fn [mxc alt-text info]
               (re-frame/dispatch [:sdk/send-sticker active-id mxc alt-text info])
               (re-frame/dispatch [:ui/close-popover]))
             :on-insert-native
             (fn [unicode-char]
               (when-let [ed @!editor]
                 (-> ed .chain .focus (.insertContent unicode-char) .run)))
             :on-insert-emoji
             (fn [shortcode url]
               (when-let [ed @!editor]
                 (-> ed .chain .focus
                     (.insertContent #js {:type "customEmote"
                                          :attrs #js {:shortcode shortcode
                                                      :src (mxc->url url)
                                                      :mxc url}})
                     .run))
               )}])
         (when (and context (= (:mode context) :reply))
           (let [sender-name (truncate-name (-> context :target :sender-name) 32)]
             [:div.input-context-banner
              [:div.context-info
               [:span (tr [:composer/replying-to] [sender-name])]]
              [:button.context-cancel-btn
               {:on-click #(re-frame/dispatch [:input/clear-context active-id])} [icons/exit]]]))

         [:div.timeline-input-wrapper
          (when (seq attachments)
            [:div.composer-attachments
             (doall
              (map-indexed (fn [idx att]
                             ^{:key (str "att-" idx)}
                             [attachment-preview active-id att idx])
                           attachments))])

          (when uploading?
            ^{:key "upload-indicator"}
            [:div.upload-indicator
             [:span.upload-text (tr [:composer/uploading])]
             [:div.upload-progress-bar [:div.upload-progress-fill]]])

          ^{:key "composer-input-row"}
          [:div.timeline-input-row
           [:label.timeline-upload-btn
            {:title (tr [:composer/upload-tooltip])}
            [:input {:type "file"
                     :multiple true
                     :style {:display "none"}
                     :on-change (fn [e]
                                  (let [files      (.. e -target -files)
                                        file-array (js/Array.from files)]
                                    (when (seq file-array)
                                      (re-frame/dispatch [:sdk/handle-file-drop active-id file-array]))
                                    (set! (.-value (.-target e)) "")))}]
            [icons/plus]]
           [:div.timeline-editor-container
            [:> tiptap-component
             #js {:activeId active-id
                  :key (str "editor-" active-id)
                  :loadedText loaded-text
                  :cachedHtml cached-html
                  :onChange (fn [text html]
                              (re-frame/dispatch [:composer/on-change active-id text html]))
                  :onSend submit-message!
                  :onFiles (fn [files]
                             (let [file-array (js/Array.from files)]
                               (re-frame/dispatch [:sdk/handle-file-drop active-id file-array])))
                  :placeholder (tr [:composer/placeholder])
                  :onEditorReady #(reset! !editor %)
                  :onSuggestionStart (fn [cmd] (reset! !sug-command cmd))
                  :onSuggestionExit  (fn [] (reset! !sug-command nil))}]]
           [plugins/plugin-slot :composer-actions {:room-id active-id}]
           [timeline-emoji-button {:on-click #(if picker-open?
                                                (re-frame/dispatch [:ui/close-popover])
                                                (re-frame/dispatch [:ui/open-popover :inline-emoji {}]))
                                   :active?  picker-open?
                                   :room-id  active-id}]
           [timeline-send-button {:submit-message! submit-message!
                                  :editor @!editor
                                  :attachments attachments}]]]]))))


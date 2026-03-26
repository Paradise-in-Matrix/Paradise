(ns input.drafts
  (:require [promesa.core :as p]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [reagent.core :as r]
            ["react" :as react]
            [utils.svg :as icons]
            ["ffi-bindings" :as sdk :refer [MessageType MessageFormat MediaSource UploadSource UploadParameters]]))

(defn reify-attachment [att]
  (let [mime (or (:mime att) "application/octet-stream")
        file-info #js {:mimetype mime
                       :size (js/BigInt (.-byteLength (:buffer att)))
                       :thumbnailInfo js/undefined
                       :thumbnailSource js/undefined}
        source (.new (.-Data (.-UploadSource sdk))
                     #js {:bytes (:buffer att)
                          :filename (:filename att)})]
    (cond
      (str/starts-with? mime "image/")
      (.new (.-Image (.-DraftAttachment sdk))
            #js {:imageInfo file-info :source source :thumbnailSource js/undefined})
      (str/starts-with? mime "video/")
      (.new (.-Video (.-DraftAttachment sdk))
            #js {:videoInfo file-info :source source :thumbnailSource js/undefined})
      (str/starts-with? mime "audio/")
      (.new (.-Audio (.-DraftAttachment sdk))
            #js {:audioInfo file-info :source source})
      :else
      (.new (.-File (.-DraftAttachment sdk))
            #js {:fileInfo file-info :source source}))))

(defn prepare-attachment [sdk att]
  (let [mime      (or (:mime att) "application/octet-stream")
        file-info #js {:mimetype mime
                       :size (js/BigInt (.-byteLength (:buffer att)))
                       :thumbnailInfo js/undefined
                       :thumbnailSource js/undefined}
        source    (.. sdk -UploadSource -Data
                      (new #js {:bytes (:buffer att)
                                :filename (:filename att)}))]
    {:source source
     :info   file-info
     :type   (cond
               (str/starts-with? mime "image/") "Image"
               (str/starts-with? mime "video/") "Video"
               (str/starts-with? mime "audio/") "Audio"
               :else                            "File")}))

(defn attachment-preview [room-id attachment index]
  [:div.attachment-preview
   (let [{:keys [mime preview-url filename]} attachment]
     (cond
       (str/starts-with? mime "image/")
       [:img.preview-image {:src preview-url}]
       (or (str/starts-with? mime "audio/")
           (str/starts-with? mime "video/"))
       [:div.preview-media-placeholder
        [:span [icons/chevron-right]]
        [:span.mime-label (last (str/split mime #"/"))]]
       :else
       [:div.preview-file
        [icons/file]
        [:span.file-name filename]]))
   [:button.remove-attachment
    {:on-click #(re-frame/dispatch [:composer/remove-attachment room-id index])}
    [icons/exit]]])

  (re-frame/reg-event-fx
 :composer/save-draft
 (fn [{:keys [db]} [_ room-id text html buffer filename mime]]
   (let [timeline (get-in db [:timeline-subs room-id :timeline])]
     (try
       (let [file-info #js {:mimetype        (or mime "application/octet-stream")
                            :size            (js/BigInt (.-byteLength buffer))
                            :thumbnailInfo   js/undefined
                            :thumbnailSource js/undefined}
             upload-source (.new (.-Data (.-UploadSource sdk))
                                 #js {:bytes buffer :filename filename})
             attachment (.new (.-File (.-DraftAttachment sdk))
                              #js {:fileInfo file-info
                                   :source upload-source})
             draft-type (.new (.-NewMessage (.-ComposerDraftType sdk)))
             draft (.. (.-ComposerDraft sdk)
                       (create #js {:plainText   text
                                    :htmlText    (or html js/undefined)
                                    :draftType   draft-type
                                    :attachments #js [attachment]}))]
         (log/info "Saving reified draft for room:" room-id)
         (.setComposerDraft timeline draft file-info))
       (catch :default e
         (log/error "Draft Reification Failure:" e)))
     {})))

(re-frame/reg-event-db
 :composer/clear-after-submit
 (fn [db [_ room-id]]
   (-> db
       (assoc-in [:drafts room-id :attachments] [])
       (assoc-in [:composer room-id] {:text ""
                                      :html ""
                                      :loaded-text ""
                                      :uploading? false}))))

(re-frame/reg-event-fx
 :composer/persist-draft
 (fn [{:keys [db]} [_ room-id]]
   (let [client (:client db)
         room   (when client (.getRoom client room-id))
         attachments (get-in db [:drafts room-id :attachments])
         {:keys [text html]} (get-in db [:composer room-id])]
     (when (and room (not (str/blank? text)))
       (try
         (let [draft-type (.new (.-NewMessage (.-ComposerDraftType sdk)))
               _ (log/debug attachments)
               draft-atts (clj->js (map reify-attachment attachments))
               draft (.. (.-ComposerDraft sdk)
                         (create #js {:plainText text
                                      :htmlText (or html js/undefined)
                                      :draftType draft-type
                                      :attachments draft-atts}))]
           (log/debug "Auto-saving draft for" room-id "with" (count attachments) "attachments")
           (-> (.saveComposerDraft room draft js/undefined)
               (.catch #(log/error "Auto-save failed:" %))))
         (catch :default e (log/error "Draft Persist Panic:" e))))
     {})))

(re-frame/reg-event-fx
 :composer/on-change
 (fn [{:keys [db]} [_ room-id text html]]
   (let [old-timer (get-in db [:composer room-id :save-timer])]
     (when old-timer (js/clearTimeout old-timer))
     {:db (update-in db [:composer room-id]
                     merge
                     {:text text
                      :html html
                      :save-timer (js/setTimeout
                                   #(re-frame/dispatch [:composer/persist-draft room-id])
                                   1000)})})))

(re-frame/reg-event-db
 :composer/add-attachment
 (fn [db [_ room-id attachment]]
   (update-in db [:drafts room-id :attachments] (fnil conj []) attachment)))


(re-frame/reg-sub
 :composer/attachments
 (fn [db [_ room-id]]
   (get-in db [:drafts room-id :attachments] [])))

(re-frame/reg-event-db
 :composer/remove-attachment
 (fn [db [_ room-id index]]
   (let [attachment (get-in db [:drafts room-id :attachments index])]
     (when (:preview-url attachment)
       (js/URL.revokeObjectURL (:preview-url attachment))))
   (update-in db [:drafts room-id :attachments]
              (fn [atts] (vec (concat (take index atts) (drop (inc index) atts)))))))

(re-frame/reg-event-fx
 :composer/load-draft
 (fn [{:keys [db]} [_ room-id]]
   (let [client (:client db)
         room (when client (.getRoom client room-id))]
     (when room
       (-> (.loadComposerDraft room js/undefined)
           (.then (fn [draft]
                    (when draft
                      (re-frame/dispatch [:composer/draft-loaded room-id draft]))))
           (.catch #(log/error "Failed to load draft:" %))))
     {})))

(re-frame/reg-event-fx
 :composer/draft-loaded
 (fn [{:keys [db]} [_ room-id draft]]
   (let [plain-text (.-plainText draft)
         html-text  (.-htmlText draft)
         ffi-atts   (or (.-attachments draft) #js [])
         _ (js/console.log ffi-atts)
         restored-atts
         (mapv (fn [att]
                 (try
                   (let [tag    (.-tag att)
                         inner  (.-inner att)
                         source (.-source inner)
                         data-inner (.-inner source)
                         buffer   (.-bytes data-inner)
                         filename (.-filename data-inner)
                         mime (case tag
                                "Image" (.-mimetype (.-imageInfo inner))
                                "Video" (.-mimetype (.-videoInfo inner))
                                "Audio" (.-mimetype (.-audioInfo inner))
                                "File"  (.-mimetype (.-fileInfo inner))
                                "application/octet-stream")
                         blob (js/Blob. #js [buffer] #js {:type mime})
                         url  (js/URL.createObjectURL blob)]
                     {:buffer buffer
                      :mime mime
                      :filename filename
                      :preview-url url})
                   (catch :default e
                     (log/error "Failed to restore individual attachment:" e)
                     nil)))
               ffi-atts)]
     {:db (-> db
              (assoc-in [:composer room-id :loaded-text] (or html-text plain-text))
              (assoc-in [:drafts room-id :attachments] (vec (remove nil? restored-atts))))})))

(re-frame/reg-sub
 :composer/loaded-text
 (fn [db [_ room-id]]
   (get-in db [:composer room-id :loaded-text] "")))
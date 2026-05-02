(ns input.drafts
  (:require [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [utils.svg :as icons]
            [cljs.core.async :refer [go <!]]
            [cljs-workers.core :as main]
            [client.state :as state]
            ))


(defn reify-attachment [att]
  (let [mime       (or (:mime att) (:mimetype att) "application/octet-stream")
        safe-bytes (if (= (type (:buffer att)) js/Uint8Array)
                     (:buffer att)
                     (js/Uint8Array. (:buffer att)))
        file-info  #js {:mime mime
                        :size (js/BigInt (.-byteLength safe-bytes))
                        :height (js/BigInt (or (:height att) 0))
                        :width (js/BigInt (or (:width att) 0))
                        :thumbnailInfo js/undefined
                        :thumbnailSource js/undefined}
        source     (.new (.-Data (.-UploadSource sdk))
                         #js {:bytes safe-bytes
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
  (let [mime       (or (:mime att) (:mimetype att) "application/octet-stream")
        safe-bytes (if (= (type (:buffer att)) js/Uint8Array)
                     (:buffer att)
                     (js/Uint8Array. (:buffer att)))
        file-info  #js {:mimetype mime
                        :size (js/BigInt (.-byteLength safe-bytes))
                        :thumbnailInfo js/undefined
                        :thumbnailSource js/undefined}

        source     (.. sdk -UploadSource -Data
                       (new #js {:bytes safe-bytes
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
   (let [{:keys [mimetype mime preview-url filename]} attachment
         resolved-mime (or mime mimetype "application/octet-stream")
         _ (log/error attachment)
         ]
     (cond
       (str/starts-with? resolved-mime "image/")
       [:img.preview-image {:src preview-url}]
       (or (str/starts-with? resolved-mime "audio/")
           (str/starts-with? resolved-mime "video/"))
       [:div.preview-media-placeholder
        [:span [icons/chevron-down]]
        [:span.mime-label (last (str/split resolved-mime #"/"))]]
       :else
       [:div.preview-file
        [icons/file]
        [:span.file-name filename]]))
   [:button.remove-attachment
    {:on-click #(re-frame/dispatch [:composer/remove-attachment room-id index])}
    [icons/exit]]])


(re-frame/reg-event-fx
 :composer/clear-after-submit
 (fn [{:keys [db]} [_ room-id]]
   (doseq [att (get-in db [:drafts room-id :attachments] [])]
     (when (:preview-url att)
       (js/URL.revokeObjectURL (:preview-url att))))
   {:db (-> db
            (assoc-in [:drafts room-id :attachments] [])
            (assoc-in [:composer room-id] {:text ""
                                           :html ""
                                           :loaded-text ""
                                           :uploading? false}))
    :dispatch [:composer/persist-draft room-id]}))



(re-frame/reg-event-fx
 :composer/persist-draft
 (fn [{:keys [db]} [_ room-id]]
   (let [attachments (get-in db [:drafts room-id :attachments] [])
         {:keys [text html]} (get-in db [:composer room-id])]

     (if-let [pool @state/!engine-pool]
       (go
         (let [res (<! (main/do-with-pool! pool {:handler :persist-draft
                                                 :arguments {:room-id room-id
                                                             :text (or text "")
                                                             :html (or html "")
                                                             :attachments attachments}}))]
           (when (= (:status res) "error")
             (log/error "Draft Persist failed:" (:msg res)))))
       (log/error "No engine pool for draft auto-save"))
     {})))


(re-frame/reg-sub
 :composer/cached-html
 (fn [db [_ room-id]]
   (get-in db [:composer room-id :html])))

(re-frame/reg-event-fx
 :composer/on-change
 (fn [{:keys [db]} [_ room-id text html]]
   (let [old-timer (get-in db [:composer room-id :save-timer])]
     (when old-timer (js/clearTimeout old-timer))
     {:db (update-in db [:composer room-id]
                     merge
                     {:text text
                      :html html
                      :loaded-text nil
                      :save-timer (js/setTimeout
                                   #(re-frame/dispatch [:composer/persist-draft room-id])
                                   500)})})))


(re-frame/reg-event-fx
 :composer/add-attachment
 (fn [{:keys [db]} [_ room-id attachment]]
   {:db (update-in db [:drafts room-id :attachments] (fnil conj []) attachment)
    :dispatch [:composer/persist-draft room-id]}))

(re-frame/reg-sub
 :composer/attachments
 (fn [db [_ room-id]]
   (get-in db [:drafts room-id :attachments] [])))


(re-frame/reg-event-fx
 :composer/remove-attachment
 (fn [{:keys [db]} [_ room-id index]]
   (let [attachment (get-in db [:drafts room-id :attachments index])]
     (when (:preview-url attachment)
       (js/URL.revokeObjectURL (:preview-url attachment))))
   {:db (update-in db [:drafts room-id :attachments]
                   (fn [atts] (vec (concat (take index atts) (drop (inc index) atts)))))
    :dispatch [:composer/persist-draft room-id]}))

(re-frame/reg-event-fx
 :composer/load-draft
 (fn [_ [_ room-id]]
   (if-let [pool @state/!engine-pool]
     (go
       (let [res (<! (main/do-with-pool! pool {:handler :load-draft
                                               :arguments {:room-id room-id}}))]
         (when (and (= (:status res) "success") (:draft res))
           (re-frame/dispatch [:composer/draft-loaded room-id (:draft res)]))))
     (log/error "No engine pool to load draft"))
   {}))


(re-frame/reg-event-fx
 :composer/draft-loaded
 (fn [{:keys [db]} [_ room-id draft]]
   (let [plain-text (:text draft)
         html-text  (:html draft)
         raw-atts   (:attachments draft)
         restored-atts
         (mapv (fn [att]
                 (let [raw-bytes (js/Uint8Array. (:buffer att))
                       mime      (or (:mimetype att) (:mime att) "application/octet-stream")
                       blob      (js/Blob. #js [raw-bytes] #js {:type mime})
                       url       (js/URL.createObjectURL blob)]
                   (assoc att :preview-url url :buffer raw-bytes :mimetype mime :mime mime)))
               raw-atts)]
     {:db (-> db
              (assoc-in [:composer room-id :loaded-text] (or html-text plain-text))
              (assoc-in [:drafts room-id :attachments] restored-atts))})))


(re-frame/reg-sub
 :composer/loaded-text
 (fn [db [_ room-id]]
   (get-in db [:composer room-id :loaded-text] "")))

(defn send-attachments! [sdk timeline room attachments text html]
  (let [total (count attachments)]
    (p/let [_ (p/loop [idx 0]
                (when (< idx total)
                  (let [att       (nth attachments idx)
                        is-last?  (= idx (dec total))
                        {:keys [source #_info type]} (prepare-attachment sdk att)


                        info (case type
                               "Image" (create-image-info att source)
                               "Video" (create-video-info att source)
                               "Audio" (create-audio-info att source)
                               js/undefined)
                        caption   (if is-last? (or text js/undefined) (:filename att))
                        formatted (if (and is-last? (not (str/blank? html)))
                                    #js {:format (.new (.-Html (.-MessageFormat sdk)))
                                         :body html}
                                    js/undefined)
                        params    (.. sdk -UploadParameters
                                      (create #js {:source source
                                                   :caption (or caption js/undefined)
                                                   :formattedCaption (or formatted js/undefined)}))]
                    (.sendFile timeline params info)
                    (p/do! (p/delay 100)
                           (p/recur (inc idx))))))]
      (.clearComposerDraft room js/undefined)
      (doseq [att attachments]
        (js/URL.revokeObjectURL (:preview-url att)))
      true)))
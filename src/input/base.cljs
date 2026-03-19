(ns input.base
  (:require [promesa.core :as p]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [input.drafts :refer [attachment-preview reify-attachment prepare-attachment]]
            [input.composer :refer [tiptap-component get-matrix-formatted-body prepare-html-for-editor]]
            [input.emotes :refer [emoji-sticker-board]]
            [input.autocomplete :refer [suggestion-menu]]
            [reagent.core :as r]
            [utils.helpers :refer [mxc->url]]
            ["generated-compat" :as sdk :refer [MessageType MessageFormat MediaSource UploadSource UploadParameters]]
            ))

(re-frame/reg-event-fx
 :sdk/upload-media
 (fn [{:keys [db]} [_ file on-success-event]]
   (let [client (:client db)
         reader (js/FileReader.)]
     (if (and client file)
       (do
         (set! (.-onload reader)
               (fn [e]
                 (let [buffer (.. e -target -result)
                       mime (.-type file)]
                   (-> (.uploadMedia client mime buffer js/undefined)
                       (p/then (fn [mxc-url]
                                 (re-frame/dispatch (conj on-success-event mxc-url))))
                       (p/catch #(log/error "Generic Upload Failed:" %))))))
         (.readAsArrayBuffer reader file)
         {:db (assoc db :uploading-files? true)})
       (do (js/console.warn "Missing client or file for generic upload") {})))))

(re-frame/reg-event-fx
 :sdk/upload-and-send-file
 (fn [{:keys [db]} [_ room-id buffer mime filename]]
   (let [timeline (get-in db [:timeline-subs room-id :timeline])]
     (if-not timeline
       (log/error "No timeline found for" room-id)
       (try
         (let [file-info #js {:mimetype        (or mime "application/octet-stream")
                              :size            (js/BigInt (.-byteLength buffer))
                              :thumbnailInfo   js/undefined
                              :thumbnailSource js/undefined}

               upload-source (.new (.. UploadSource -Data)
                                   #js {:bytes    buffer
                                        :filename filename})
               params (.. UploadParameters
                          (create #js {:source  upload-source
                                       :caption filename}))]
           (.sendFile timeline params file-info)
           (re-frame/dispatch [:sdk/upload-complete]))
         (catch :default e
           (js/console.error "FFI Sync Call Panic:" e)
           (re-frame/dispatch [:sdk/upload-complete]))))
     {})))

(re-frame/reg-event-fx
 :sdk/handle-file-drop
 (fn [{:keys [db]} [_ room-id files]]
   (doseq [raw-file files]
     (let [reader      (js/FileReader.)
           preview-url (js/URL.createObjectURL raw-file)
           mime        (.-type raw-file)
           filename    (.-name raw-file)]
       (set! (.-onload reader)
             (fn [e]
               (let [buffer (.. e -target -result)
                     attachment {:buffer      buffer
                                 :mime        mime
                                 :filename    filename
                                 :preview-url preview-url}]
                 (re-frame/dispatch [:composer/add-attachment room-id attachment]))))
       (.readAsArrayBuffer reader raw-file)))
   {}))

(defn send-attachments! [sdk timeline room attachments text html]
  (let [total (count attachments)]
    (p/let [_ (p/loop [idx 0]
                (when (< idx total)
                  (let [att       (nth attachments idx)
                        is-last?  (= idx (dec total))
                        {:keys [source info type]} (prepare-attachment sdk att)
                        caption   (if is-last? (or text js/undefined) (:filename att))
                        formatted (if (and is-last? (not (str/blank? html)))
                                    #js {:format (.new (.-Html (.-MessageFormat sdk)))
                                         :body html}
                                    js/undefined)
                        params    (.. sdk -UploadParameters
                                      (create #js {:source source
                                                   :caption (or caption js/undefined)
                                                   :formattedCaption (or formatted js/undefined)}))]
                    (log/info "Sending attachment" (inc idx) "of" total "as" type)
                    (.sendFile timeline params info)
                    (p/do! (p/delay 100)
                           (p/recur (inc idx))))))]
      (.clearComposerDraft room js/undefined)
      (doseq [att attachments]
        (js/URL.revokeObjectURL (:preview-url att)))
      true)))

  (defn send-all! [timeline attachments text html]
  (let [total (count attachments)
        send-one (fn send-one [remaining-atts idx]
                   (if (empty? remaining-atts)
                     (js/Promise.resolve true)
                     (let [att (first remaining-atts)
                           is-last? (= idx (dec total))
                           caption   (if is-last? text (:filename att))
                           formatted (if (and is-last? (not (str/blank? html)))
                                       #js {:format (.new (.-Html (.-MessageFormat sdk)))
                                            :body html}
                                       js/undefined)

                           ffi-att (reify-attachment att)
                           inner   (.-inner ffi-att)
                           info    (condp = (.-tag ffi-att)
                                     "Image" (.-imageInfo inner)
                                     "Video" (.-videoInfo inner)
                                     (.-fileInfo inner))
                           params (.. (.-UploadParameters sdk)
                                      (create #js {:source (.-source inner)
                                                   :caption (or caption js/undefined)
                                                   :formattedCaption (or formatted js/undefined)}))]
                       (log/info "Sending file" (inc idx) "of" total)
                       (-> (.sendFile timeline params info)
                           (.then (fn [] (send-one (rest remaining-atts) (inc idx))))))))]
    (send-one (vec attachments) 0)))

(re-frame/reg-event-fx
 :composer/submit
 (fn [{:keys [db]} [_ room-id text html]]
   (let [client      (:client db)
         timeline    (get-in db [:timeline-subs room-id :timeline])
         room        (when client (.getRoom client room-id))
         attachments (get-in db [:drafts room-id :attachments])
         context     (get-in db [:input-context room-id])]
     (cond
       (or (not timeline) (not room))
       (do (log/error "Room or Timeline missing for" room-id) {})
       (seq attachments)
       (do
         (-> (send-attachments! sdk timeline room attachments text html)
             (.then #(re-frame/dispatch [:composer/clear-after-submit room-id]))
             (.catch #(log/error "Failed to send attachments:" %)))
         {})
:else
       (try
         (let [_       (log/debug "LIVE CONTEXT FROM DB:" context)
               payload (.messageEventContentFromHtml sdk text (or html text))]
           (if context
             (let [action     (:mode context)
                   raw-id-obj (-> context :target :event-or-transaction-id)
                   target-id  (if (= (.-tag raw-id-obj) "EventId")
                                (.. raw-id-obj -inner -eventId)
                                (.. raw-id-obj -inner -transactionId))
                   id-enum    (if (str/starts-with? target-id "$")
                                #js {:tag "EventId" :inner #js {:eventId target-id}}
                                #js {:tag "TransactionId" :inner #js {:transactionId target-id}})]
               (log/debug "Executing:" action "on" target-id)
               (cond
                 (= action :reply)
                 (.sendReply timeline payload target-id js/undefined)
                 (= action :edit)
                 (.edit timeline id-enum payload js/undefined)))
             (.send timeline payload js/undefined))
           (.clearComposerDraft room js/undefined)
           {:db (-> db
                    (assoc-in [:composer room-id] {:text "" :html "" :loaded-text ""})
                    (update :input-context dissoc room-id))})
         (catch :default e (log/error "Send error:" e) {}))))))

(re-frame/reg-event-fx
 :sdk/send-sticker
 (fn [{:keys [db]} [_ room-id mxc alt-text info]]
   (let [client  (:client db)
         session (when client (.session client))]
     (if-not (and client session)
       (log/error "Cannot send raw sticker: No active session")
       (let [token   (.-accessToken session)
             hs      (str/replace (.-homeserverUrl session) #"/+$" "")
             txn-id  (str "stk-" (.getTime (js/Date.)) "-" (rand-int 10000))
             url     (str hs "/_matrix/client/v3/rooms/"
                          (js/encodeURIComponent room-id)
                          "/send/m.sticker/" txn-id)
             clean-body (cond
                          (keyword? alt-text) (name alt-text)
                          (string? alt-text) alt-text
                          :else "Sticker")
             info-obj (js-obj "mimetype" (or (:mimetype info) "image/png"))
             _        (when (:w info) (aset info-obj "w" (js/Number (:w info))))
             _        (when (:h info) (aset info-obj "h" (js/Number (:h info))))

             payload  #js {:body clean-body
                           :url  mxc
                           :info info-obj}]

         (log/info "Sending RAW m.sticker event to:" url)
         (-> (js/fetch url #js {:method  "PUT"
                                :headers #js {:Authorization (str "Bearer " token)
                                              :Content-Type  "application/json"}
                                :body    (js/JSON.stringify payload)})
             (.then (fn [resp]
                      (if (.-ok resp)
                        (log/info "Raw Sticker sent successfully!")
                        (log/error "Server rejected raw sticker:" (.-status resp)))))
             (.catch #(log/error "Sticker network error:" %)))))
     {})))
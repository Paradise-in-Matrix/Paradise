(ns paradise.engine.media-previews
  (:require [cljs-workers.worker :as worker]
            [clojure.string :as str]
            [net :as net]
            [taoensso.timbre :as log]
            ["ffi-bindings" :as sdk]
            [paradise.engine.state :as state])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs.core.async.interop :refer [<p!]]))

(defonce !media-preview-listener (atom nil))

(defn attach-media-preview-listener! [client]
  (try
    (when-let [old-listener @!media-preview-listener]
      (try (.unsubscribe old-listener) (catch :default _ nil)))
    (let [sub (.subscribeToMediaPreviewConfig client
               #js {:onChange
                    (fn [config]
                      (if config
                        (let [policy (.-mediaPreviews config)
                              policy-kw (cond
                                          (or (= policy 0) (= policy (.. sdk -MediaPreviews -On)))      :on
                                          (or (= policy 1) (= policy (.. sdk -MediaPreviews -Private))) :private
                                          (or (= policy 2) (= policy (.. sdk -MediaPreviews -Off)))     :off
                                          :else                                                         :off)]
                          (worker/stream! {:type "media-preview-config" :policy policy-kw}))
                        (worker/stream! {:type "media-preview-config" :policy :private})))})]
      (reset! !media-preview-listener sub))
    (catch :default e
      (log/error "Failed to bind media preview config listener:" e))))

(worker/register :get-url-preview
  (fn [{:keys [url]}]
    (go
      (try
        (let [client    @state/!client
              session   (.session client)
              token     (.-accessToken session)
              clean-hs  (clojure.string/replace (.-homeserverUrl session) #"/+$" "")
              fetch-url (str clean-hs "/_matrix/media/v3/preview_url?url=" (js/encodeURIComponent url))
              resp      (<p! (net/fetch fetch-url #js {:headers #js {"Authorization" (str "Bearer " token)}}))]
          (if (.-ok resp)
            (let [json (<p! (.json resp))]
              {:status "success" :data (js->clj json :keywordize-keys true)})
            {:status "error" :msg (str "HTTP " (.-status resp))}))
        (catch :default e
          {:status "error" :msg (str e)})))))


(worker/register :set-media-preview-policy
  (fn [{:keys [policy]}]
    (go
      (js/console.error policy)
      (if-let [client @state/!client]
        (try
          (let [sdk-policy (case policy
                             "on"      (.. sdk -MediaPreviews -On)
                             "private" (.. sdk -MediaPreviews -Private)
                             "off"     (.. sdk -MediaPreviews -Off)
                             (.. sdk -MediaPreviews -Off))]
            (<p! (.setMediaPreviewDisplayPolicy client sdk-policy))
            {:status "success"})
          (catch :default e
            (log/error "Failed to set media preview policy:" e)
            {:status "error" :msg (str e)}))
        {:status "error" :msg "No active client"}))))

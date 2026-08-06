(ns paradise.ui.container.call.native
  (:require [re-frame.core :as rf]
            ["@capacitor/core" :refer [Capacitor registerPlugin]]
            [taoensso.timbre :as log]))

(def call-plugin
  (when (.isNativePlatform js/Capacitor)
  (registerPlugin "ParadiseCall"))
)

(defn request-media-permissions! []
  (when call-plugin
    (.requestMediaPermissions call-plugin)))

(defn start-call! [room-id display-name]
  (when call-plugin
    (.startCall call-plugin #js {:roomId room-id :name display-name})))

(defn report-connected! [room-id]
  (when call-plugin
    (.reportConnected call-plugin #js {:roomId room-id})))

(defn end-call! [room-id]
  (when call-plugin
    (.endCall call-plugin #js {:roomId room-id})))

(defn init-native-listeners! []
  (when call-plugin
    (.addListener call-plugin "callAnswered"
      (fn [^js data]
        (let [room-id (.-roomId data)]
          (log/info "Native OS answered call:" room-id)
          (rf/dispatch [:call/init-widget room-id {:join-directly? true :is-native-answered? true}]))))
    (.addListener call-plugin "callDeclined"
      (fn [^js data]
        (let [room-id (.-roomId data)]
          (log/info "Native OS ended call:" room-id)
          (rf/dispatch [:call/hangup {:room-id room-id :wipe-state? true :skip-native? true}]))))))

(ns app
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [promesa.core :as p]
   [clojure.string :as str]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]
   [navigation.spaces.bar :refer [spaces-sidebar]]
   [overlays.notifications :as notifications]
   [overlays.settings]
   [overlays.lightbox]
   [overlays.profiles]
   [overlays.reactions]
   [auth.events :refer [login-screen]]
   [container.call.call-container :refer [persistent-call-container]]
   [client.config :refer [load-config check-remote-version load-i18n]]
   [client.state :refer [!config]]
   [client.session-store :as store]
   [taoensso.tempura :as tempura :refer [tr]]
   [utils.global-ui :refer [global-reaction-picker modal-root popover-root global-context-menu satellite-overlay make-swipe-handlers]]
   ["@capacitor/status-bar" :refer [StatusBar]]
   [utils.macros :refer [i18n-data]]
   [utils.svg :as icons]
   [navigation.rooms.room-list :refer [room-list]]
   [container.base :refer [container]]
   ))

#_(def default-db
  {:spaces {"!space1:example.com" {:id "!space1:example.com" :name "Main Space" :parent-id nil}
            "!space2:example.com" {:id "!space2:example.com" :name "Sub Space" :parent-id "!space1:example.com"}
            "!space3:example.com" {:id "!space3:example.com" :name "Other Space" :parent-id nil}}
   :rooms {"!room1:example.com" {:id "!room1:example.com" :name "general" :parent-id "!space1:example.com"}
           "!room2:example.com" {:id "!room2:example.com" :name "random" :parent-id "!space1:example.com"}
           "!room3:example.com" {:id "!room3:example.com" :name "cljs-dev" :parent-id "!space2:example.com"}}
   :active-space-id "!space1:example.com"
   :active-room-id "!room1:example.com"})


(def default-db
  {:spaces {}
   :rooms  {}
   :active-space-id nil
   :active-room-id  nil
   :auth-status :checking
   :login-error nil
   :client nil
   :config {:version "0.0.0"}
   :locale :en
   :dictionary {}
   :update-available? false}
   )

(re-frame/reg-event-db
 :initialize-db
 (fn [_ _]
   default-db
   ))

(re-frame/reg-event-db
 :ui/set-sidebar
 (fn [db [_ open?]]
   (assoc-in db [:ui :sidebar-open?] open?)))

(re-frame/reg-event-db
 :ui/toggle-sidebar
 (fn [db _]
   (update-in db [:ui :sidebar-open?] not)))

(re-frame/reg-sub
 :ui/sidebar-open?
 (fn [db _]
   (get-in db [:ui :sidebar-open?] false)))

(re-frame/reg-event-db
 :ui/window-resized
 (fn [db [_ width]]
   (assoc-in db [:ui :mobile?] (< width 768))))

(re-frame/reg-sub
 :ui/mobile?
 (fn [db _]
   (get-in db [:ui :mobile?] false)))


(re-frame/reg-event-db
 :app/config-loaded
 (fn [db [_ config]]
   (assoc db :config config
             :update-available? false)))

(re-frame/reg-event-fx
 :app/poll-version
 (fn [{:keys [db]} [_ manual?]]
   (let [current-v (get-in db [:config :version])]
     (when current-v
       (-> (check-remote-version)
           (p/then (fn [remote-v]
                     (cond
                       (and remote-v (not= remote-v current-v))
                       (re-frame/dispatch [:app/update-detected])
                       manual?
                       (js/alert (str "You are up to date! (v" current-v ")"))))))
     {}))))

(re-frame/reg-event-db
 :app/update-detected
 (fn [db _]
   (assoc db :update-available? true)))

(re-frame/reg-sub
 :app/update-available?
 (fn [db _]
   (:update-available? db false)))

(re-frame/reg-sub
 :app/version
 (fn [db _]
   (get-in db [:config :version] "Unknown")))


(re-frame/reg-sub
 :i18n/tr
 (fn [db _]
   (let [locale (:locale db)
         dictionary (:dictionary db)]
     (partial tempura/tr {:dict dictionary} [locale :en]))))



(re-frame/reg-sub
 :i18n/locale
 (fn [db _] (:locale db)))

(re-frame/reg-sub
 :i18n/dictionary
 (fn [db _] (:dictionary db)))

(re-frame/reg-sub
 :i18n/tr
 (fn [db _]
   (let [locale (get db :locale :en)
         dictionary (get db :dictionary {})]
     (if (empty? dictionary)
       (fn [k & _] (str "[" (name (last k)) "]"))
       (partial tempura/tr {:dict dictionary} [locale :en])))))

(re-frame/reg-event-db
 :i18n/set-dictionary
 (fn [db [_ dict]]
   (assoc db :dictionary dict)))

(re-frame/reg-event-db
 :i18n/set-locale
 (fn [db [_ locale]]
   (assoc db :locale locale)))



(re-frame/reg-event-fx
 :ui/hydrate-theme
 (fn [{:keys [db]} [_ theme-val]]
   {:dispatch [:ui/switch-theme theme-val]}))

(re-frame/reg-sub
 :ui/current-theme
 (fn [db _]
   (:ui/current-theme db "default")))

(re-frame/reg-event-fx
 :ui/reset-theme
 (fn [{:keys [db]} _]
   {:db (assoc db :ui/current-theme "default")
    :settings/save ["theme" "default"]
    :ui/hotswap-css "css/app.css"}))


(re-frame/reg-fx
 :ui/hotswap-css
 (fn [new-href]
   (if-let [link (.getElementById js/document "app-theme")]
     (do
       (set! (.-href link) new-href)
       (log/info "Swapped CSS to:" new-href))
     (log/error "Theme stylesheet link #app-theme not found!"))))

(re-frame/reg-event-fx
 :ui/switch-theme
 (fn [{:keys [db]} [_ theme-val]]
   (let [safe-theme (or theme-val "default")
         theme-str  (if (keyword? safe-theme) (name safe-theme) safe-theme)
         is-url?    (str/starts-with? theme-str "http")
         href       (if is-url?
                      theme-str
                      (str "css/" (case (keyword theme-str)
                                    :discord "cordlike.css"
                                    :matrix  "matrix.css"
                                    :retro   "retro.css"
                                    "app.css")))]
     {:db (assoc db :ui/current-theme theme-str)
      :settings/save ["theme" theme-str]
      :ui/hotswap-css href})))


(defn booting-screen []
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.boot-container
     [:div.boot-content
      [:div.boot-logo-wrapper
       [icons/transition {:size "100px"}]
       ]
      [:div.boot-loading-text
       [:span (tr [:boot/loading-text])]]]]))

(defn set-status-bar! []
  (.setBackgroundColor StatusBar #js {:color
                                      "#1e1f22"})
  (.setStyle StatusBar #js {:style "DARK"}))


(defn main-layout []
  (r/with-let [!drag-state (r/atom {:start-x nil :dx 0})]
    (let [auth-status   @(re-frame/subscribe [:auth/status])
          sidebar-open? @(re-frame/subscribe [:ui/sidebar-open?])
          update-ready? @(re-frame/subscribe [:app/update-available?])
          swipe-props (make-swipe-handlers
                       !drag-state
                       {:on-end (fn [dx]
                                  (let [start-x (:start-x @!drag-state)]
                                    (cond
                                      (and (not sidebar-open?) #_ (< start-x 40) (> dx 60))
                                      (re-frame/dispatch [:ui/set-sidebar true])
                                      (and sidebar-open? (< dx -60))
                                      (re-frame/dispatch [:ui/set-sidebar false]))))})]

      (case auth-status
        :checking [booting-screen]
        (:logged-out :authenticating) [login-screen]
        :logged-in
        [:<>
         (when update-ready?
           [:div.global-update-banner
            [:span "A new version of the app is available!"]
            [:button {:on-click #(re-frame/dispatch [:app/apply-update])}
             "Refresh"]])

         [persistent-call-container]
         (into [:div.app-root
                (merge {:class (when sidebar-open? "sidebars-open")
                        :style {:touch-action "pan-y"}}
                       swipe-props)]
               [[spaces-sidebar]
                [room-list]
                (when sidebar-open?
                  [:div.mobile-overlay {:on-click #(re-frame/dispatch [:ui/set-sidebar false])}])
                [container]])
         [modal-root]
         [popover-root]
         [global-context-menu]]
        [:div "Unknown State"]))))


(defn init-window-size-listener! []
  (re-frame/dispatch [:ui/window-resized (.-innerWidth js/window)])

  (let [resize-handler (goog.functions/debounce
                        #(re-frame/dispatch [:ui/window-resized (.-innerWidth js/window)])
                        150)]
    (.addEventListener js/window "resize" resize-handler)))


(defonce root (atom nil))

(defn mount-root []
 (re-frame/dispatch [:ui/switch-theme])
  (re-frame/clear-subscription-cache!)
  (let [container (.getElementById js/document "root")]
    (when-not @root
      (reset! root (rdom/create-root container)))
    (init-window-size-listener!)
    (.render @root (r/as-element [main-layout]))))

(defn ^:export init []
  (re-frame/dispatch-sync [:initialize-db])
  (re-frame/dispatch [:app/load-settings-by-stage :boot])
  (re-frame/dispatch [:app/start-boot-sequence])
  (mount-root))


(re-frame/reg-event-fx
 :app/start-boot-sequence
 (fn [_ _]
   (-> (load-config)
       (p/then (fn [config]
                 (reset! !config config)
                 (re-frame/dispatch-sync [:app/config-loaded config])
                 (log/info "Config loaded:" config)
                 (load-i18n)))
       (p/then (fn [_]
                 (let [params  (js/URLSearchParams. js/window.location.search)
                       room-id (.get params "room")]
                   (when room-id
                     (log/info "Deep link detected on boot:" room-id)
                     (re-frame/dispatch [:nav/lock-deep-link room-id])
                     (.replaceState js/window.history #js {} "" (.. js/window -location -pathname)))

                   (p/let [saved-user (store/get-setting "last_active_user")]
                     (re-frame/dispatch [:app/bootstrap saved-user]))

                   (notifications/init!)
                   (js/setInterval #(re-frame/dispatch [:app/poll-version false]) (* 1000 60 15)))))
       (p/catch #(log/error "App initialization failed:" %)))
   {}))

(re-frame/reg-event-db
 :nav/lock-deep-link
 (fn [db [_ room-id]]
   (assoc db :deep-link-room room-id)))

(re-frame/reg-event-fx
 :nav/route-from-push
 (fn [{:keys [db]} [_ room-id]]
   (if (= (:auth-status db) :logged-in)
     {:dispatch [:rooms/select room-id]}
     {:dispatch [:nav/lock-deep-link room-id]})))

(re-frame/reg-event-fx
 :app/restore-user-session
 (fn [{:keys [db]} [_ user-id]]
   (let [deep-room (:deep-link-room db)]
     (if deep-room
       {:dispatch-n [[:auth/set-status :logged-in]
                     [:rooms/select deep-room]
                     [:sdk/ignite-session]]}
       (do
         (p/let [[room-id space-id] (p/all [(store/get-setting (str "last_room_" user-id))
                                            (store/get-setting (str "last_space_" user-id))])]
           (if room-id
             (do
               (log/info "Restoring session for" user-id "-> Space:" space-id "| Room:" room-id)
               (when space-id
                 (re-frame/dispatch [:space/select space-id]))
               (re-frame/dispatch [:auth/set-status :logged-in])
               (re-frame/dispatch [:rooms/select room-id])
               (re-frame/dispatch [:sdk/ignite-session]))
             (do
               (re-frame/dispatch [:auth/set-status :logged-in])
               (re-frame/dispatch [:sdk/ignite-session]))))
         {})))))



(defn ^:after-load re-render []
  (mount-root))
(ns app
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [promesa.core :as p]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]
   [navigation.spaces.bar :refer [spaces-sidebar]]
   [overlays.settings]
   [overlays.lightbox]
   [overlays.profiles]
   [overlays.reactions]
   [auth.events :refer [login-screen]]
   [container.call.call-container :refer [persistent-call-container]]
   [client.config :refer [load-config check-remote-version load-i18n]]
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



(re-frame/reg-fx
 :ui/hotswap-css
 (fn [new-filename]
   (let [links (.querySelectorAll js/document "link[rel='stylesheet']")
         link (aget links 0)]
     (if link
       (do
         (set! (.-href link) new-filename)
         (log/info "Swapped CSS to:" new-filename))
       (log/error "No stylesheet links found in document")))))

(re-frame/reg-event-fx
 :ui/switch-theme
 (fn [{:keys [db]} [_ theme-name]]
   (let [filename (case theme-name
                    :discord "cordlike.css"
                    :matrix  "matrix.css"
                    :retro   "retro.css"
                    "app.css")]
     {:db (assoc db :ui/current-theme theme-name)
      :ui/hotswap-css (str "css/" filename)})))

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
  (-> (load-config)
      (p/then (fn [config]
                (re-frame/dispatch-sync [:app/config-loaded config])
                (log/info "Config loaded:" config)
                (load-i18n)))
      (p/then (fn [_]
                (let [params  (js/URLSearchParams. js/window.location.search)
                      room-id (.get params "room")]
                  (re-frame/dispatch [:app/bootstrap])
                  (when room-id
                    (log/info "App started with room-id:" room-id)
                    (re-frame/dispatch [:rooms/select room-id])
                    (let [new-url (.. js/window -location -pathname)]
                      (.replaceState js/window.history #js {} "" new-url))))
                (mount-root)
                (js/setInterval #(re-frame/dispatch [:app/poll-version false]) (* 1000 60 15))))
      (p/catch #(log/error "App initialization failed:" %))))




(defn ^:after-load re-render []
  (mount-root))
(ns overlays.settings
  (:require
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [utils.svg :as icons]
   [overlays.base :refer [modal-component]]
   [utils.helpers :refer [mxc->url]]
   [taoensso.timbre :as log]
   [utils.macros :refer [config]]
   [cljs.core.async :refer [go <!]]
   [cljs-workers.core :as main]
   [client.state :as state]
   ))



(re-frame/reg-sub
 :sdk/profile
 (fn [db _]
   (get db :current-user {:user-id "Loading..."
                          :display-name "Loading..."
                          :avatar-url nil})))

(re-frame/reg-event-fx
 :sdk/fetch-own-profile
 (fn [_ _]
   (if-let [pool @state/!engine-pool]
     (go
       (let [res (<! (main/do-with-pool! pool {:handler :fetch-profile}))]
         (when (= (:status res) "success")
           (re-frame/dispatch [:sdk/set-own-profile (:profile res)]))))
     (log/error "Cannot fetch profile: engine pool missing"))
   {}))

(re-frame/reg-event-db
 :sdk/set-own-profile
 (fn [db [_ profile]]
   (assoc db :current-user profile)))


(re-frame/reg-event-fx
 :settings/open
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [:ui/open-modal :settings
                     {:backdrop-props {:class "settings-backdrop"}
                      :window-props   {:class "settings-window"}}]]
         [:dispatch [:settings/load-accounts]]]}))

(re-frame/reg-event-db
 :settings/set-tab
 (fn [db [_ tab-id]]
   (assoc db :settings/active-tab tab-id)))

(re-frame/reg-sub
 :settings/active-tab
 (fn [db _]
   (:settings/active-tab db :my-account)))

(re-frame/reg-event-fx
 :sdk/submit-verification
 (fn [{:keys [db]} [_ recovery-key]]
   (if-let [pool @state/!engine-pool]
     (do
       (go
         (let [res (<! (main/do-with-pool! pool {:handler :recover-session
                                                 :arguments {:recovery-key recovery-key}}))]
           (if (= (:status res) "success")
             (do
               (log/info "Session successfully verified and recovered!")
               (re-frame/dispatch [:sdk/verification-success]))
             (do
               (log/error "Verification failed:" (:msg res))
               (re-frame/dispatch [:sdk/verification-error (:msg res)])))))
       {:db (assoc db :verification/status :verifying
                      :verification/error nil)})
     (do
       (log/error "Cannot verify: No engine pool active")
       {}))))

(re-frame/reg-event-db
 :sdk/verification-success
 (fn [db _]
   (assoc db :verification/status :verified
             :verification/error nil)))

(re-frame/reg-event-db
 :sdk/verification-error
 (fn [db [_ err-msg]]
   (assoc db :verification/status :error
             :verification/error err-msg)))

(re-frame/reg-sub
 :verification/status
 (fn [db _] (:verification/status db :unverified)))

(re-frame/reg-sub
 :verification/error
 (fn [db _] (:verification/error db)))


(defn sidebar-profile-mini []
  (let [tr      @(re-frame/subscribe [:i18n/tr])
        profile @(re-frame/subscribe [:sdk/profile])]
    [:div.sidebar-profile-mini
     [:div.profile-trigger
      {:style {:user-select "none"}
       :on-click (fn [e]
                   (.preventDefault e)
                   (re-frame/dispatch [:settings/open]))
       :on-context-menu (fn [e]
                          (.preventDefault e)
                          (re-frame/dispatch
                           [:context-menu/open
                            {:x (.-clientX e)
                             :y (.-clientY e)
                             :items [{:id "status"
                                      :label (tr [:settings.context-menu/set-status])
                                      :action #(log/info "Status")}
                                     {:id "copy"
                                      :label (tr [:settings.context-menu/copy-id])
                                      :action #(log/info "Copied")}
                                     {:id "logout"
                                      :label (tr [:settings.context-menu/logout])
                                      :action #(re-frame/dispatch [:sdk/logout])
                                      :class-name "danger"}]}]))}
      (if (:avatar-url profile)
        [:img.profile-avatar {:src (mxc->url (:avatar-url profile))}]
        [:div.avatar-placeholder (subs (or (:display-name profile) "?") 0 1)])
      [:div.status-dot]]]))

(defn verification-tab []
  (r/with-let [!passphrase (r/atom "")]
    (let [tr             @(re-frame/subscribe [:i18n/tr])
          status         @(re-frame/subscribe [:verification/status])
          error          @(re-frame/subscribe [:verification/error])
          is-verifying?  (= status :verifying)
          is-empty?      (empty? @!passphrase)]
      [:div.settings-section
       [:h2.verification-title (tr [:settings.verification/title])]
       (if (= status :verified)
         [:div.success-banner
          [:div.success-icon [icons/check-circle-green]]
          [:div
           [:div.success-title (tr [:settings.verification.status/success-title])]
           [:div.success-subtitle (tr [:settings.verification.status/success-subtitle])]]]
         [:<>
          [:p.verification-description
           (tr [:settings.verification/description])]
          [:div.verification-form
           [:label.form-label (tr [:settings.verification.form/label])]
           [:input.form-input
            {:class     (when error "is-invalid")
             :type      "password"
             :value     @!passphrase
             :on-change #(reset! !passphrase (.. % -target -value))
             :disabled  is-verifying?
             :placeholder (tr [:settings.verification.form/placeholder])}]
           (when error
             [:div.form-error (str (tr [:settings.verification.form/error-prefix]) error)])
           [:button.form-button
            {:class    (when is-verifying? "is-verifying")
             :on-click #(re-frame/dispatch [:sdk/submit-verification @!passphrase])
             :disabled (or is-empty? is-verifying?)}
            (if is-verifying?
              (tr [:settings.verification.status/is-verifying])
              (tr [:settings.verification.status/verify-action]))]]])])))


(defn my-account-tab [profile]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:<>
     [:h2.settings-heading (tr [:settings.profile/title])]
     [:div.profile-card
      (if (:avatar-url profile)
        [:img.profile-avatar-large {:src (mxc->url (:avatar-url profile))}]
        [:div.profile-avatar-placeholder-large])
      [:div.profile-info
       [:div.profile-name
        (or (:display-name profile)
            (tr [:settings.profile/unknown-user]))]
       [:div.profile-id (:user-id profile)]]]]))



(defn url-base64->uint8-array [base64-string]
  (let [padding (.repeat "=" (mod (- 4 (mod (.-length base64-string) 4)) 4))
        base64 (-> (.replace base64-string (js/RegExp. "-" "g") "+")
                   (.replace (js/RegExp. "_" "g") "/")
                   (str padding))
        raw-data (js/atob base64)
        output-array (js/Uint8Array. (.-length raw-data))]
    (dotimes [i (.-length raw-data)]
      (aset output-array i (.charCodeAt raw-data i)))
    output-array))



  (defn url-base64->uint8-array [base64-string]
  (let [padding (.repeat "=" (mod (- 4 (mod (.-length base64-string) 4)) 4))
        base64 (-> (.replace base64-string (js/RegExp. "-" "g") "+")
                   (.replace (js/RegExp. "_" "g") "/")
                   (str padding))
        raw-data (js/atob base64)
        output-array (js/Uint8Array. (.-length raw-data))]
    (dotimes [i (.-length raw-data)]
      (aset output-array i (.charCodeAt raw-data i)))
    output-array))


(defn- clean-url [base path]
  (str (clojure.string/replace base #"/+$" "")
       "/"
       (clojure.string/replace path #"^/+" "")))

(re-frame/reg-event-fx
 :push/enable
 (fn [_ _]
   (if-not (and (exists? js/window.PushManager) (exists? js/navigator.serviceWorker))
     (do (log/error "Push messaging is not supported.") {})
     (-> (p/let [permission (js/Notification.requestPermission)]
           (if (= permission "granted")
             (p/let [reg (.-ready js/navigator.serviceWorker)
                     existing-sub (.getSubscription (.-pushManager reg))
                     sub (if existing-sub
                           existing-sub
                           (.subscribe (.-pushManager reg)
                                       #js {:userVisibleOnly true
                                            :applicationServerKey (url-base64->uint8-array (:vapid-key config))}))]
               (re-frame/dispatch [:push/register-pusher sub]))
             (log/warn "Notification permission denied.")))
         (p/catch #(log/error "Push setup failed:" %))))
   {}))

(re-frame/reg-event-fx
 :push/register-pusher
 (fn [_ [_ subscription]]
   (if-let [pool @state/!engine-pool]
     (let [sub-json (.toJSON subscription)
           p256dh   (.. sub-json -keys -p256dh)
           auth     (.. sub-json -keys -auth)
           endpoint (.-endpoint sub-json)
           app-id   (:app-id config)
           push-url (:push-url config)]
       (cond
         (not app-id)   (log/error "Missing app-id!")
         (not push-url) (log/error "Missing push-url!")
         (not p256dh)   (log/error "Missing p256dh key from browser subscription!")
         (not auth)     (log/error "Missing auth key from browser subscription!")
         :else
         (go
           (let [res (<! (main/do-with-pool! pool
                                             {:handler :register-pusher
                                              :arguments {:p256dh   p256dh
                                                          :auth     auth
                                                          :endpoint endpoint
                                                          :app-id   app-id
                                                          :push-url push-url
                                                          :app-name (:app-name config)
                                                          :lang     (or js/navigator.language "en")}}))]
             (if (= (:status res) "success")
               (do
                 (log/info "Pusher registered successfully!")
                 (re-frame/dispatch [:push/set-status :enabled]))
               (log/error "HS Rejected Pusher:" (:msg res)))))))
     (log/error "Cannot register pusher: no engine pool"))
   {}))

(re-frame/reg-event-db
 :push/set-status
 (fn [db [_ status]]
   (assoc db :push-status status)))

(re-frame/reg-sub
 :push/status
 (fn [db _]
   (:push-status db :disabled)))

(defn notifications-tab []
  (let [tr     @(re-frame/subscribe [:i18n/tr])
        status @(re-frame/subscribe [:push/status])]
    [:div.settings-tab-content
     [:h2.settings-heading (tr [:settings.notifications/title])]
     [:div.settings-section
      [:p.verification-description
       (tr [:settings.notifications/description])]
      (if (= status :enabled)
        [:div.success-banner
         [:div.success-icon [icons/check-circle-green]]
         [:div.success-text-wrapper
          [:div.success-title (tr [:settings.notifications.status/active-title])]
          [:div.success-subtitle (tr [:settings.notifications.status/active-subtitle])]]]
        [:button.form-button
         {:on-click #(re-frame/dispatch [:push/enable])}
         (tr [:settings.notifications/enable-btn])])]]))





(re-frame/reg-fx
 :idb/fetch-sessions
 (fn [on-success-event]
   (let [req (.open js/indexedDB "sw-vault" 1)]
     (set! (.-onerror req) #(log/error "Failed to open sw-vault"))
     (set! (.-onupgradeneeded req)
           (fn [e]
             (let [db (.. e -target -result)]
               (.createObjectStore db "tokens" #js {:keyPath "userId"}))))
     (set! (.-onsuccess req)
           (fn [e]
             (let [db (.. e -target -result)]
               (if (.contains (.-objectStoreNames db) "tokens")
                 (let [tx          (.transaction db #js ["tokens"] "readonly")
                       store       (.objectStore tx "tokens")
                       get-all-req (.getAll store)]
                   (set! (.-onsuccess get-all-req)
                         (fn [ae]
                           (let [results (js->clj (.. ae -target -result) :keywordize-keys true)]
                             (.close db)
                             (re-frame/dispatch (conj on-success-event results))))))
                 (do
                   (.close db)
                   (re-frame/dispatch (conj on-success-event []))))))))))


(re-frame/reg-event-fx
 :settings/load-accounts
 (fn [_ _]
   (if-let [pool @state/!engine-pool]
     (go
       (let [res (<! (main/do-with-pool! pool {:handler :get-available-accounts}))]
         (if (= (:status res) "success")
           (re-frame/dispatch [:settings/set-accounts (:accounts res)])
           (log/error "Failed to fetch accounts from worker:" (:msg res)))))
     (log/warn "Cannot load accounts: No engine pool"))
   {}))

(re-frame/reg-event-db
 :settings/set-accounts
 (fn [db [_ accounts]]
   (assoc db :available-accounts accounts)))

(re-frame/reg-sub
 :settings/available-accounts
 (fn [db _] (:available-accounts db [])))

(defn account-item [{:keys [acc is-active?]}]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.account-item {:class (when is-active? "active")}
     [:div.account-info
      [:div.account-id (:userId acc)]
      [:div.account-hs (:hs_url acc)]]

     [:div.account-actions
      (if is-active?
        [:div.active-badge (tr [:settings.account-item/active-status])]
        [:button.form-button.switch-btn
         {:on-click #(re-frame/dispatch [:auth/switch-account (:userId acc)])}
         (tr [:settings.account-item/switch-button])])]]))

(defn accounts-tab []
  (let [tr           @(re-frame/subscribe [:i18n/tr])
        accounts     @(re-frame/subscribe [:settings/available-accounts])
        current-user @(re-frame/subscribe [:sdk/profile])]
    [:div.settings-tab-content
     [:h2.settings-heading (tr [:settings.accounts/title])]

     [:div.settings-section
      [:p.verification-description
       (tr [:settings.accounts/description])]

      [:div.accounts-list
       (if (empty? accounts)
         [:div.accounts-empty (tr [:settings.accounts/empty-state])]
         (for [acc accounts]
           ^{:key (:userId acc)}
           [account-item {:acc acc
                          :is-active? (= (:userId acc) (:user-id current-user))}]))]

      [:div.tab-footer
       [:button.form-button
        {:on-click #(re-frame/dispatch [:auth/start-login-flow])}
        (str "+ " (tr [:settings.accounts/add-account]))]]]]))



(defn language-time-tab []
  (let [tr          @(re-frame/subscribe [:i18n/tr])
        locale      @(re-frame/subscribe [:i18n/locale])]
    [:div.settings-tab-content
     [:h2.settings-heading (tr [:settings.language/title])]
     [:div.settings-section
      [:p.verification-description (tr [:settings.language/description])]
      [:h3.settings-subheading (tr [:settings.language/system-label])]
      [:div.language-list
       (for [[id label native] [[:en "English" "English"]
                                [:eo "Esperanto" "Esperanto"]]]
         ^{:key id}
         [:div.language-item
          {:class (when (= locale id) "is-active")
           :on-click #(re-frame/dispatch [:i18n/set-locale id])}
          [:div.lang-names
           [:div.lang-native native]
           [:div.lang-english label]]
          (when (= locale id) [icons/check-circle-green])])]]]))



(defn about-tab []
  (let [tr       @(re-frame/subscribe [:i18n/tr])
        locale   @(re-frame/subscribe [:i18n/locale])
        version  @(re-frame/subscribe [:app/version])
        app-name (:app-name config "Matrix Client")]
    [:div.settings-tab-content
     [:h2.settings-heading (tr [:settings.about/title])]
     [:div.settings-section
      [:div.about-header
       [:h3 app-name]
       [:div.version-badge (str "v" version)]]
      [:p.verification-description
       (tr [:settings.about/description] [app-name])]
      [:div.settings-spacer]
      [:h3.settings-subheading (tr [:settings.about/updates-title])]
      [:p.verification-description
       (tr [:settings.about/updates-desc])]
      [:button.form-button
       {:on-click #(re-frame/dispatch [:app/poll-version true])}
       (tr [:settings.about/check-updates])]]]))





(defn settings-sidebar [active-tab]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.settings-sidebar
     [:div.settings-group-label (tr [:settings.groups/user-settings])]
     (for [[id label-key] [[:my-account    :settings.tabs/my-account]
                           [:verification  :settings.tabs/verification]
                           [:accounts      :settings.tabs/accounts]
                           [:notifications :settings.tabs/notifications]
                           [:language-time :settings.tabs/language-time]]]
       ^{:key id}
       [:div.settings-tab
        {:class (when (= active-tab id) "is-active")
         :on-click #(re-frame/dispatch [:settings/set-tab id])}
        (tr [label-key])])
     [:div.settings-group-label {:style {:margin-top "1rem"}}
      (tr [:settings.groups/app])]
     [:div.settings-tab
      {:class (when (= active-tab :about) "is-active")
       :on-click #(re-frame/dispatch [:settings/set-tab :about])}
      (tr [:settings.tabs/about])]]))

(defn settings-content [_props]
  (let [tr         @(re-frame/subscribe [:i18n/tr])
        active-tab @(re-frame/subscribe [:settings/active-tab])
        profile    @(re-frame/subscribe [:sdk/profile])]
    [:<>
     [settings-sidebar active-tab]
     [:div.settings-content
      [:div.close-button
       {:on-click #(re-frame/dispatch [:ui/close-modal])}
       [:span [icons/exit]]
       [:span.esc-text (tr [:settings.modal/esc])]]
      (case active-tab
        :my-account    [my-account-tab profile]
        :verification  [verification-tab]
        :accounts      [accounts-tab]
        :notifications [notifications-tab]
        :language-time [language-time-tab]
        :about         [about-tab]
        [:div {:style {:color "#fff"}} (tr [:settings.modal/not-found])])]]))

(defmethod modal-component :settings [_]
  settings-content)
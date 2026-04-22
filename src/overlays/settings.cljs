(ns overlays.settings
  (:require
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [utils.svg :as icons]
   [overlays.base :refer [modal-component]]
   [utils.global-ui :refer [avatar]]
   [client.session-store :as store]
   [plugins :as plugins]
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

(re-frame/reg-event-db
 :sdk/handle-recovery-stream
 (fn [db [_ state-kw]]
   (assoc db :verification/status state-kw
             :verification/error nil)))


(re-frame/reg-event-fx
 :sdk/submit-verification
 (fn [{:keys [db]} [_ recovery-key]]
   (let [user-id (:active-user-id db)]
     (if-let [pool @state/!engine-pool]
       (do
         (go
           (let [res (<! (main/do-with-pool! pool {:handler :recover-session
                                                   :arguments {:recovery-key recovery-key}}))]
             (if (= (:status res) "success")
               (do
                 (log/info "Session successfully verified and recovered!")
                 (re-frame/dispatch [:push/verify-shadow user-id recovery-key])
                 (re-frame/dispatch [:sdk/verification-success]))
               (do
                 (log/error "Verification failed:" (:msg res))
                 (re-frame/dispatch [:sdk/verification-error (:msg res)])))))
         {:db (assoc db :verification/status :verifying
                        :verification/error nil)})
       (do
         (log/error "Cannot verify: No engine pool active")
         {})))))

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


(defn advanced-tab []
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.settings-tab-content
     [:h2.settings-heading (tr [:settings.advanced/title])]
     [:div.settings-section
      [:p.verification-description
       (tr [:settings.advanced/description])]
      [plugins/plugins-installer]]]))


(def settings-registry
  {;; DB Key                  Hydration Event                       Default Value
   "show_previews"         {:event :push/hydrate-previews-setting :default true      :stage  :login }
   "theme"                 {:event :ui/hydrate-theme              :default :default  :stage  :boot  }
   "disabled_plugin_urls"  {:event :plugins/hydrate-disabled-urls :default []        :stage  :boot  }
   "plugin_urls"           {:event :plugins/hydrate-urls          :default []        :stage  :boot  }})


(re-frame/reg-event-fx
 :settings/load
 (fn [_ [_ idb-key hydrate-event default-val]]
   (-> (store/get-setting idb-key)
       (.then (fn [saved-val]
                (let [final-val (if (nil? saved-val) default-val saved-val)]
                  (re-frame/dispatch [hydrate-event final-val])))))
   {}))

(re-frame/reg-fx
 :settings/save
 (fn [[k v]]
   (store/set-setting! k v)))

(re-frame/reg-event-fx
 :app/load-settings-by-stage
 (fn [_ [_ target-stage]]
   (let [staged-settings (filter (fn [[_ config]]
                                   (= (:stage config) target-stage))
                                 settings-registry)]
     {:fx (mapv (fn [[db-key config]]
                  [:dispatch [:settings/load db-key (:event config) (:default config)]])
                staged-settings)})))



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
      [avatar {:id   (:user-id profile)
               :name (or (:display-name profile) "?")
               :url  (:avatar-url profile)
               :size 40}]
      [:div.status-dot]]]))


(defn verification-tab []
  (r/with-let [!passphrase (r/atom "")]
    (let [tr              @(re-frame/subscribe [:i18n/tr])
          status          @(re-frame/subscribe [:verification/status])
          error           @(re-frame/subscribe [:verification/error])
          is-verifying?   (= status :verifying)
          is-empty?       (empty? @!passphrase)]
      [:div.settings-section
       [:h2.verification-title (tr [:settings.verification/title])]
       (cond
         (= status :enabled)
         [:div.success-banner
          [:div.success-icon [icons/check-circle-green]]
          [:div
           [:div.success-title (tr [:settings.verification.status/success-title])]
           [:div.success-subtitle (tr [:settings.verification.status/success-subtitle])]]]

         (or (= status :incomplete) (= status :verifying) (= status :error))
         [:<>
          [:p.verification-description (tr [:settings.verification/description])]
          [:div.verification-form
           [:label.form-label (tr [:settings.verification.form/label])]
           [:input.form-input
            {:class      (when error "is-invalid")
             :type       "password"
             :value      @!passphrase
             :on-change  #(reset! !passphrase (.. % -target -value))
             :disabled   is-verifying?
             :placeholder (tr [:settings.verification.form/placeholder])}]
           (when error
             [:div.form-error (str (tr [:settings.verification.form/error-prefix]) error)])
           [:button.form-button
            {:class    (when is-verifying? "is-verifying")
             :on-click #(re-frame/dispatch [:sdk/submit-verification @!passphrase])
             :disabled (or is-empty? is-verifying?)}
            (if is-verifying?
              (tr [:settings.verification.status/is-verifying])
              (tr [:settings.verification.status/verify-action]))]]]

         (= status :disabled)
         [:div.warning-banner
          [:div.warning-title (tr [:settings.verification.status/warning-title])]
          [:div.warning-subtitle (tr [:settings.verification.status/warning-subtitle])]]

         :else
         [:div.loading-state
          [:span (tr [:settings.verification.status/checking])]])])))


(defn my-account-tab [profile]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:<>
     [:h2.settings-heading (tr [:settings.profile/title])]
     [:div.profile-card
      [avatar {:id   (:user-id profile)
               :name (or (:display-name profile) "?")
               :url  (:avatar-url profile)
               :size 80}]
      [:div.profile-info
       [:div.profile-name
        (or (:display-name profile)
            (tr [:settings.profile/unknown-user]))]
       [:div.profile-id (:user-id profile)]]]]))

(defn notifications-tab []
  (let [tr              @(re-frame/subscribe [:i18n/tr])
        status          @(re-frame/subscribe [:push/status])
        current-user    @(re-frame/subscribe [:auth/active-user-id])
        push-owner      @(re-frame/subscribe [:push/active-user])
        is-active-here? (and (= status :enabled) (= current-user push-owner))]
    [:div.settings-tab-content
     [:h2.settings-heading (tr [:settings.notifications/title])]
     [:div.settings-section
      [:p.verification-description
       (tr [:settings.notifications/description])]
      (cond
        is-active-here?
        [:div.success-banner
         [:div.success-icon [icons/check-circle-green]]
         [:div.success-text-wrapper
          [:div.success-title (tr [:settings.notifications.status/active-title])]
          [:div.success-subtitle (tr [:settings.notifications.status/active-subtitle])]]]

        (and push-owner (not= current-user push-owner))
        [:div.warning-banner
         [:div.warning-title
          (tr [:settings.notifications.warning/another-active])]
         [:div.warning-subtitle
          (tr [:settings.notifications.warning/swap-active] [push-owner])]]
        :else nil)

      [:div.settings-actions-container
       (when-not is-active-here?
         [:button.form-button
          {:on-click #(re-frame/dispatch [:push/enable])}
          (if push-owner
            (tr [:settings.notifications.warning/confirm-swap])
            (tr [:settings.notifications/enable-btn]))])

       (when push-owner
         [:button.form-button.destructive
          {:on-click #(re-frame/dispatch [:push/disable])}
          (tr [:settings.notifications/disable-btn])])
       [:button.form-button.destructive
        {:on-click #(re-frame/dispatch [:push/clear-all])}
        (tr [:settings.notifications/clear-btn])]]
      (let [enabled? @(re-frame/subscribe [:push/previews-enabled?])]
        [:div.settings-row.toggle-row
         [:div.setting-text
          [:div.setting-title (tr [:settings.notifications/show-previews])]
          [:div.setting-description (tr [:settings.notifications/show-previews-description])]]
         [:label.custom-toggle
          [:input {:type "checkbox"
                   :checked enabled?
                   :on-change #(re-frame/dispatch [:push/toggle-previews (.. % -target -checked)])}]
          [:div.toggle-track
           [:div.toggle-knob]]]])]]))







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
                                [:lorem "Lorem Ipsum" "Lorem Ipsum"]]]
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
      (tr [:settings.tabs/about])]
     [:div.settings-tab
      {:class (when (= active-tab :advanced) "is-active")
       :on-click #(re-frame/dispatch [:settings/set-tab :advanced])}
      (tr [:settings.tabs/advanced])]]))


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
        :advanced      [advanced-tab]
        [:div {:style {:color "#fff"}} (tr [:settings.modal/not-found])])]]))

(defmethod modal-component :settings [_]
  settings-content)
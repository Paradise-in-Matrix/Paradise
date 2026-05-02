(ns auth.events
  (:require [re-frame.core :as re-frame]
            [reagent.core :as r]
            [client.state :as state]
            [cljs-workers.core :as main]
            [cljs.core.async :refer [go <!]]
            [client.session-store :as store]
            [utils.svg :as icons]
            [utils.net :refer [set-auth-context!]]
            [service-worker-handler :refer [register-sw!]]
            [taoensso.timbre :as log]))


(re-frame/reg-event-db
 :auth/set-status
 (fn [db [_ status]]
   (assoc db :auth-status status)))

(re-frame/reg-sub
 :auth/status
 (fn [db _] (:auth-status db)))

(re-frame/reg-sub
 :auth/error
 (fn [db _] (:login-error db)))

(re-frame/reg-event-fx
 :auth/start-login-flow
 (fn [{:keys [db]} _]
   {:db (assoc db :auth-status :logged-out
               :settings/open? false
               :login-error nil)
    :fx [[:dispatch [:settings/close]]
         [:dispatch [:settings/load-accounts]]]}))


(re-frame/reg-event-fx
 :auth/login
 (fn [{:keys [db]} [_ hs user pass]]
   (if-let [pool @state/!engine-pool]
     (go
       (try
         (let [res (<! (main/do-with-pool! pool {:handler :login
                                                 :arguments {:hs hs
                                                             :user user
                                                             :pass pass}}))]
           (if (= (:status res) "success")
             (re-frame/dispatch [:auth/login-success
                                 (assoc res :credentials {:homeserver hs
                                                          :username user
                                                          :password pass})])
             (re-frame/dispatch [:auth/login-failure (:msg res)])))
         (catch :default e
           (re-frame/dispatch [:auth/login-failure (str e)]))))
     (log/error "Cannot login: Engine pool not initialized"))

   {:db (assoc db :auth-status :authenticating
                  :login-error nil)}))

(re-frame/reg-event-fx
 :auth/login-success
 (fn [{:keys [db]} [_ {:keys [user-id hs-url session-data credentials]}]]
   (when session-data
     (register-sw!)
     (set-auth-context! (:accessToken session-data) (:homeserverUrl session-data))
     (store/set-setting! "last_active_user" user-id))
   (log/info "Login successful for:" user-id)
   {:db (cond-> (assoc db :auth-status :logged-in
                          :active-user-id user-id
                          :rooms/data {}
                          :timeline/current-events [])
          hs-url (assoc :homeserver-url hs-url))
    :fx (cond-> [[:dispatch [:app/load-settings-by-stage :login]]
                 [:dispatch [:app/restore-user-session user-id]]
                 [:dispatch [:push/check-status]]]
          (and credentials session-data)
          (conj [:dispatch [:push/create-sleepy-shadow
                            (assoc credentials :homeserver
                                   (:homeserverUrl session-data)
                                   :userId user-id)]]))}))

(re-frame/reg-sub
 :auth/active-user-id
 (fn [db _]
   (:active-user-id db)))

(re-frame/reg-event-fx
 :auth/switch-account
 (fn [{:keys [db]} [_ target-user-id]]
   (store/set-setting! "last_active_user" target-user-id)
   {:db (assoc db :auth-status :checking
                  :settings/open? false
                  :active-user-id nil
                  :rooms/data {}
                  :timeline/current-events [])
    :fx [[:dispatch [:app/bootstrap target-user-id]]]}))

(re-frame/reg-event-db
 :auth/login-failure
 (fn [db [_ error-msg]]
   (assoc db :auth-status :logged-out
             :login-error error-msg)))



(re-frame/reg-fx
 :session/delete-from-store
 (fn [user-id]
   (let [store (store/->SessionStore)]
     (-> (.clear store user-id)
         (.then #(log/info "Successfully wiped OPFS session and crypto store for:" user-id))
         (.catch #(log/error "Failed to wipe session for:" user-id %))))
   (let [req (js/window.indexedDB.deleteDatabase "paradise-settings")]
     (set! (.-onsuccess req) #(log/info "Successfully wiped paradise-settings database."))
     (set! (.-onerror req) #(log/error "Failed to wipe paradise-settings."))
     (set! (.-onblocked req) #(log/warn "Wiping paradise-settings was BLOCKED by an open connection.")))))


(re-frame/reg-event-fx
 :session/nuke-single-account
 (fn [{:keys [db]} [_ target-user-id]]
   (let [is-active? (= target-user-id (:active-user-id db))]
     (merge
      {:session/wipe-settings true
       :fx [[:dispatch [:settings/load-accounts]]]}
      (when is-active?
        {:db (assoc db :auth-status :logged-out
                       :active-user-id nil
                       :rooms/data {}
                       :timeline/current-events [])})))))

(re-frame/reg-fx
 :session/wipe-settings
 (fn [_]
   (let [req (js/window.indexedDB.deleteDatabase "paradise-settings")]
     (set! (.-onsuccess req) #(log/info "Successfully wiped paradise-settings database."))
     (set! (.-onerror req) #(log/error "Failed to wipe paradise-settings."))
     (set! (.-onblocked req) #(log/warn "Wiping paradise-settings was BLOCKED.")))))


(re-frame/reg-event-fx
 :sdk/logout
 (fn [{:keys [db]} _]
   (let [user-id (:active-user-id db)]
     (if user-id
       (do
         (go
           (let [pool @state/!engine-pool
                 res  (<! (main/do-with-pool! pool {:handler :logout-client
                                                    :arguments {:user-id user-id}}))]
             (if (= (:status res) "success")
               (re-frame/dispatch [:session/nuke-single-account user-id])
               (log/error "Homeserver rejected logout:" (:msg res)))))
         {})
       {:dispatch [:session/nuke-everything]}))))


(re-frame/reg-event-fx
 :session/nuke-local-state!
 (fn [{:keys [db]} _]
   (let [store-id (get-in db [:session/meta :store-id])]
     {:db {}
      :browser/hard-wipe store-id})))

(re-frame/reg-fx
 :browser/hard-wipe
 (fn [store-id]
   (.clear js/window.localStorage)
   (let [stores-to-delete (cond-> ["paradise-settings"]
                            store-id (conj (str "paradise-store-" store-id)))
         delete-promises
         (map (fn [db-name]
                (js/Promise.
                 (fn [resolve]
                   (let [req (js/window.indexedDB.deleteDatabase db-name)]
                     (set! (.-onsuccess req) #(resolve true))
                     (set! (.-onerror req) #(resolve false))
                     (set! (.-onblocked req) #(do (log/warn db-name "is blocked!")
                                                  (resolve false)))))))
              stores-to-delete)]
     (-> (js/Promise.all (clj->js delete-promises))
         (.then (fn []
                  (log/info "All databases destroyed. Reloading...")
                  (.reload js/window.location)))))))

(defn login-field [{:keys [id label type value on-change disabled]}]
  [:div.field-group.mb-4
   [:label.field-label {:for id} label]
   [:input.field-input
    {:id id
     :type (or type "text")
     :value value
     :on-change on-change
     :disabled disabled}]])

(defn login-screen []
  (let [fields (r/atom {:hs (or js/process.env.MATRIX_HOMESERVER "https://matrix.org")
                        :user ""
                        :pass ""})]
    (fn []
      (let [tr       @(re-frame/subscribe [:i18n/tr])
            error    @(re-frame/subscribe [:auth/error])
            status   @(re-frame/subscribe [:auth/status])
            accounts @(re-frame/subscribe [:settings/available-accounts])
            auth?    (= status :authenticating)]
        [:div.auth-page-container
         [:div.auth-card
          [:div.auth-header
           [:h2 (tr [:auth/welcome])]
           [:p (tr [:auth/subtitle])]]

          (when error
            [:div.auth-error-message error])

          (when (seq accounts)
            [:div.account-chooser.mb-6
             [:h3.text-sm.font-bold.mb-2 (tr [:auth/continue-as])]
             [:div.accounts-list
              (for [acc accounts]
                ^{:key (:userId acc)}
                [:div.account-chooser-item
                 {:style {:display "flex" :align-items "center" :padding "8px"
                          :border "1px solid var(--border)" :border-radius "8px"
                          :cursor "pointer" :margin-bottom "8px"}
                  :on-click #(re-frame/dispatch [:auth/switch-account (:userId acc)])}
                 [:div.avatar-placeholder {:style {:margin-right "12px"}} [icons/user]]
                 [:div.account-details
                  [:div.account-id {:style {:font-weight "bold"}} (:userId acc)]
                  [:div.account-hs {:style {:font-size "0.8em" :color "gray"}} (:hs_url acc)]]])]
             [:div.divider {:style {:text-align "center" :margin "16px 0" :color "gray"}}
              (tr [:auth/divider])]])

          [:form.auth-form
           {:on-submit (fn [e]
                         (.preventDefault e)
                         (re-frame/dispatch [:auth/login (:hs @fields) (:user @fields) (:pass @fields)]))}

           [login-field
            {:id "hs" :label (tr [:auth.labels/homeserver]) :value (:hs @fields)
             :disabled auth? :on-change #(swap! fields assoc :hs (.. % -target -value))}]

           [login-field
            {:id "user" :label (tr [:auth.labels/username]) :value (:user @fields)
             :disabled auth? :on-change #(swap! fields assoc :user (.. % -target -value))}]

           [login-field
            {:id "pass" :label (tr [:auth.labels/password]) :type "password" :value (:pass @fields)
             :disabled auth? :on-change #(swap! fields assoc :pass (.. % -target -value))}]

           [:button.btn-primary.w-full
            {:type "submit" :disabled auth?}
            (if auth?
              (tr [:auth.actions/logging-in])
              (tr [:auth.actions/login]))]

           [:div.auth-footer
            (tr [:auth/footer-text])
            [:a.link {:href "#"} (tr [:auth.actions/register])]]]]]))))
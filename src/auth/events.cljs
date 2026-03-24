(ns auth.events
  (:require [re-frame.core :as re-frame]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [promesa.core :as p]
            [client.login :as login]
            [utils.svg :as icons]
            [service-worker-handler :refer [register-sw!]]
            [taoensso.timbre :as log]))

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
    :fx [[:dispatch [:settings/close]]]}))

(re-frame/reg-event-fx
 :app/bootstrap
 (fn [{:keys [db]} [_ target-user-id]]
   (log/debug "Bootstrapping account:" (or target-user-id "Default"))
   (login/bootstrap!
    target-user-id
    (fn [client session-data]
      (if client
        (do
          (re-frame/dispatch [:auth/login-success client session-data])
          (re-frame/dispatch [:sdk/fetch-all-emotes])
          (re-frame/dispatch [:sdk/fetch-own-profile]))
        (re-frame/dispatch [:auth/login-failure nil]))))
   {:db (assoc db :auth-status :checking)
    :fx [[:dispatch [:settings/load-accounts]]]}))


(re-frame/reg-event-fx
 :auth/login
 (fn [{:keys [db]} [_ hs user pass]]
   (-> (login/login! hs user pass)
       (p/then (fn [client]
                 (re-frame/dispatch [:auth/login-success client nil])))
       (p/catch (fn [e]
                  (log/error "Login Failed" e)
                  (re-frame/dispatch [:auth/login-failure (.-message e)]))))
   {:db (assoc db :auth-status :authenticating
                  :login-error nil)}))

(re-frame/reg-event-fx
 :auth/login-success
 (fn [{:keys [db]} [_ client session-data]]
   (let [session (if session-data
                   (.-session session-data)
                   (.session client))
         hs-url  (.-homeserverUrl session)]
     (when session
       (register-sw! (js->clj session :keywordize-keys true))
       (login/start-sync! client)
       (re-frame/dispatch [:sdk/fetch-own-profile])
       (log/info "Session registered and sync started for:" (.userId client)))
     {:db (cond-> (assoc db :auth-status :logged-in
                            :client client
                            :rooms/data {}
                            :timeline/current-events [])
            hs-url (assoc :homeserver-url hs-url))})))

(re-frame/reg-event-fx
 :auth/switch-account
 (fn [{:keys [db]} [_ target-user-id]]
   {:db (assoc db :auth-status :checking
                  :settings/open? false
                  :client nil
                  :rooms/data {}
                  :timeline/current-events [])
    :fx [[:dispatch [:app/bootstrap target-user-id]]]}))

#_(re-frame/reg-event-fx
 :auth/login-success
 (fn [{:keys [db]} [_ client session-data]]
   (let [session (some-> session-data .-session)
         hs-url  (some-> session .-homeserverUrl)]
     (if session-data
       (do
         (register-sw! (js->clj session :keywordize-keys true))
         (login/start-sync! client)
         (log/debug "Session restored and sync started!"))
       (re-frame/dispatch [:app/bootstrap]))
     {:db (cond-> (assoc db :auth-status :logged-in :client client)
            hs-url (assoc :homeserver-url hs-url))})))

(re-frame/reg-event-db
 :auth/login-failure
 (fn [db [_ error-msg]]
   (assoc db :auth-status :logged-out
             :login-error error-msg)))


(re-frame/reg-fx
 :idb/delete-session
 (fn [user-id]
   (let [req (.open js/indexedDB "sw-vault" 1)]
     (set! (.-onerror req) #(log/error "Failed to open sw-vault for deletion"))
     (set! (.-onsuccess req)
           (fn [e]
             (let [db    (.. e -target -result)
                   tx    (.transaction db #js ["tokens"] "readwrite")
                   store (.objectStore tx "tokens")]
               (.delete store user-id)
               (log/info "Wiped session from vault:" user-id)))))))

(re-frame/reg-event-fx
 :session/nuke-single-account
 (fn [{:keys [db]} [_ target-user-id]]
   (let [active-user-id (some-> (:client db) .userId)
         is-active?     (= target-user-id active-user-id)]
     (merge
      {:idb/delete-session target-user-id
       :fx [[:dispatch [:settings/load-accounts]]]}
      (when is-active?
        {:db (assoc db :auth-status :logged-out
                       :client nil
                       :rooms/data {}
                       :timeline/current-events [])})))))

(re-frame/reg-fx
 :matrix/logout-client
 (fn [client]
   (-> (.logout client)
       (.then #(log/info "Successfully invalidated token on the homeserver."))
       (.catch #(log/error "Homeserver logout failed. Nuking local state anyway." %))
       (.finally #(re-frame/dispatch [:session/nuke-local-state!])))))

(re-frame/reg-event-fx
 :sdk/logout
 (fn [{:keys [db]} _]
   (let [client (:client db)]
     (if client
       {:matrix/logout-client client}
       {:dispatch [:session/nuke-single-account (.userId client)]}))))

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
   (when store-id
     (let [store-name (str "paradise-store-" store-id)]
       (js/window.indexedDB.deleteDatabase store-name)))
   (.reload js/window.location)))

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




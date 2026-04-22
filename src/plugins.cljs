(ns plugins
  (:require
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [client.state :as state]
   [utils.svg :as icons]
   [plugin-storage]
   [clojure.string :as str]))


(defn build-wrapped-component [base-comp wrappers]
  (reduce (fn [acc-comp plugin]
            (fn [props]
              ((:wrapper plugin) acc-comp props)))
          base-comp
          (sort-by :priority wrappers)))

(def memoized-wrapped-component (memoize build-wrapped-component))

(defn plugin-slot [slot-id props & children]
  (let [plugins   (state/get-slot slot-id)
        wrappers  (filter :wrapper plugins)
        injectors (filter :component plugins)
        base-comp (fn [] (into [:<>] children))]
    [:<>
     (if (seq wrappers)
       [(memoized-wrapped-component base-comp wrappers) props]
       (into [:<>] children))
     (for [{:keys [id component]} injectors]
       ^{:key id} [component props])]))


(re-frame/reg-event-db
 :plugin/register
 (fn [db [_ slot-id plugin-map]]
   (update-in db [:plugins slot-id] (fnil conj []) plugin-map)))

(re-frame/reg-event-db
 :plugin/deregister
 (fn [db [_ slot-id plugin-id]]
   (update-in db [:plugins slot-id]
              (fn [plugins]
                (into [] (remove #(= (:id %) plugin-id) plugins))))))

(re-frame/reg-sub
 :plugins/all
 (fn [db _]
   (:plugins db)))

(re-frame/reg-sub
 :plugins/by-slot
 :<- [:plugins/all]
 (fn [plugins [_ slot-id]]
   (get plugins slot-id [])))

(defn security-warning-modal []
  (let [pending-plugin @(re-frame/subscribe [:plugins/pending-security-approval])
        tr               @(re-frame/subscribe [:i18n/tr])]
    (when pending-plugin
      [:div.security-modal-card
       [:h2.security-modal-title (tr [:settings.plugins/security-title])]
       [:p.security-modal-text
        (tr [:settings.plugins/plugin-request [:strong (:name (:plugin-map pending-plugin))]])]

       [:div.security-script-list
        (for [script (:external-scripts (:plugin-map pending-plugin))]
          ^{:key script} [:div script])]

       [:p.security-modal-warning
        (tr [:settings.plugins/warning-message])]
       [:div.security-modal-actions
        [:button.form-button {:on-click #(re-frame/dispatch [:plugins/cancel-install])} (tr [:settings.plugins/cancel])]
        [:button.form-button.destructive.security-confirm-btn
         {:on-click #(re-frame/dispatch [:plugins/execute-boot-sequence
                                         (:url pending-plugin)
                                         (:plugin-map pending-plugin)])}
         (tr [:settings.plugins/warning-confirmation])]]])))


(defn plugins-installer []
  (let [tr               @(re-frame/subscribe [:i18n/tr])
        plugin-url-input (r/atom "")
        theme-url-input  (r/atom "")]
    (fn []
      (let [installed-urls @(re-frame/subscribe [:plugins/installed-urls])
            active-plugins @(re-frame/subscribe [:plugins/active-list])
            disabled-urls  @(re-frame/subscribe [:plugins/disabled-urls])
            current-theme  @(re-frame/subscribe [:ui/current-theme])
            custom-theme?  (and (string? current-theme)
                                (str/starts-with? current-theme "http"))]
        [:div.plugin-installer-panel
         [:div.theme-section
          [:h3 (tr [:settings.plugins/themes-title])]

          [:div.plugin-input-row
           [:input.form-input.plugin-input
            {:type "text"
             :placeholder (tr [:settings.plugins/placeholder-theme-url])
             :value @theme-url-input
             :on-change #(reset! theme-url-input (.. % -target -value))}]
           [:button.form-button.plugin-action-btn
            {:disabled (empty? @theme-url-input)
             :on-click #(do
                          (re-frame/dispatch [:ui/switch-theme @theme-url-input])
                          (reset! theme-url-input ""))}
            (tr [:settings.plugins/apply])]]
          (when custom-theme?
            [:div.active-theme-card
             [:div.active-theme-info
              [:span.active-theme-label
               (tr [:settings.plugins/active-custom-theme])]
              [:span.active-theme-value current-theme]]
             [:button.form-button.destructive.reset-theme-btn
              {:on-click #(re-frame/dispatch [:ui/reset-theme])}
              (tr [:settings.plugins/reset-default])]])]

         [:div.plugin-section
          [:h3 (tr [:settings.plugins/plugins-title])]
          [:div.plugin-input-row
           [:input.form-input.plugin-input
            {:type "text"
             :placeholder (tr [:settings.plugins/placeholder-plugin-url])
             :value @plugin-url-input
             :on-change #(reset! plugin-url-input (.. % -target -value))}]
           [:button.form-button.plugin-action-btn
            {:disabled (empty? @plugin-url-input)
             :on-click #(do
                          (re-frame/dispatch [:plugins/install-from-url @plugin-url-input])
                          (reset! plugin-url-input ""))}
            (tr [:settings.plugins/install])]]

          (when (seq active-plugins)
           [:<>
            [:h4.active-plugins-header (tr [:settings.plugins/active-plugins])]
            [:div.active-plugins-list
             (for [plugin active-plugins]
               (let [url      (:source-url plugin)
                     enabled? (not (contains? disabled-urls url))]
                 ^{:key (:id plugin)}
                 [:div.active-plugin-item
                  [:div.active-plugin-meta
                   [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
                    [:span.active-plugin-name (:name plugin (tr [:settings.plugins/unknown-plugin]))]
                    [:span.active-plugin-version (str "v" (:version plugin "1.0.0"))]]
                   [:span.active-plugin-desc (:description plugin (tr [:settings.plugins/no-description]))]]
                  [:div {:style {:display "flex" :align-items "center" :gap "16px"}}
                   [:label.custom-toggle
                    [:input {:type "checkbox"
                             :checked enabled?
                             :on-change #(re-frame/dispatch [:plugins/toggle-plugin url (.. % -target -checked)])}]
                    [:div.toggle-track
                     [:div.toggle-knob]]]

                   [:button.uninstall-plugin-btn
                    {:title (tr [:settings.plugins/uninstall-plugin])
                     :on-click #(re-frame/dispatch [:plugins/uninstall-url url])}
                    [icons/trash]]]]))]])]]))))
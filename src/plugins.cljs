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
  (let [pending-plugin @(re-frame/subscribe [:plugins/pending-security-approval])]
    (when pending-plugin
      [:div {:style {:position "fixed" :top 0 :left 0 :width "100%" :height "100%"
                     :background "rgba(0,0,0,0.8)" :display "flex" :justify-content "center"
                     :align-items "center" :z-index 9999}}
       [:div {:style {:background "#2f3136" :padding "24px" :border-radius "8px" :max-width "500px" :border "1px solid #ed4245"}}
        [:h2 {:style {:color "#ed4245" :margin-top 0}} "Security Warning"]
        [:p {:style {:color "#dcddde"}} 
         "The plugin " [:strong (:name (:plugin-map pending-plugin))] 
         " is requesting to load external JavaScript from the internet."]
        
        [:div {:style {:background "#202225" :padding "12px" :border-radius "4px" :margin "16px 0" :font-family "monospace" :color "#b5bac1"}}
         (for [script (:external-scripts (:plugin-map pending-plugin))]
           ^{:key script} [:div script])]
        
        [:p {:style {:color "#dcddde" :font-size "14px"}} 
         "Only allow this if you absolutely trust the developer. Malicious scripts can compromise your session."]
        
        [:div {:style {:display "flex" :justify-content "flex-end" :gap "12px" :margin-top "24px"}}
         [:button.form-button {:on-click #(re-frame/dispatch [:plugins/cancel-install])} "Cancel"]
         [:button.form-button.destructive 
          {:style {:background "#ed4245" :color "white" :border "none"}
           :on-click #(re-frame/dispatch [:plugins/execute-boot-sequence 
                                          (:url pending-plugin) 
                                          (:plugin-map pending-plugin)])} 
          "I understand the risks, Install"]]]])))

(defn plugins-installer []
  (let [plugin-url-input (r/atom "")
        theme-url-input  (r/atom "")]
    (fn []
      (let [installed-urls @(re-frame/subscribe [:plugins/installed-urls])
            current-theme  @(re-frame/subscribe [:ui/current-theme])
            custom-theme?  (and (string? current-theme)
                                (str/starts-with? current-theme "http"))]
        [:div.plugin-installer-panel
         {:style {:border "1px solid #36393f" :padding "20px" :border-radius "8px" :background "#2f3136"}}
         
         [security-warning-modal]

         [:div.theme-section {:style {:margin-bottom "32px" :padding-bottom "24px" :border-bottom "1px solid #404249"}}
          [:h3 {:style {:color "white" :margin-bottom "12px"}} "Custom Theme"]

          [:div {:style {:display "flex" :gap "8px" :margin-bottom "16px"}}
           [:input.form-input {:type "text" :placeholder "https://example.com/my-theme.css"
                               :style {:flex 1}
                               :value @theme-url-input
                               :on-change #(reset! theme-url-input (.. % -target -value))}]
           [:button.form-button
            {:disabled (empty? @theme-url-input)
             :style {:background "#5865F2" :min-width "80px"}
             :on-click #(do
                          (re-frame/dispatch [:ui/switch-theme @theme-url-input])
                          (reset! theme-url-input ""))}
            "Apply"]]
          (when custom-theme?
            [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"
                           :background "#202225" :padding "8px 12px" :border-radius "4px" :border "1px solid #5865F2"}}
             [:div {:style {:display "flex" :flex-direction "column"}}
              [:span {:style {:color "#43b581" :font-size "11px" :text-transform "uppercase" :font-weight "bold" :margin-bottom "4px"}} "Active Custom Theme"]
              [:span {:style {:color "#dcddde" :font-size "13px" :overflow "hidden" :text-overflow "ellipsis" :white-space "nowrap" :max-width "300px"}}
               current-theme]]
             [:button.form-button.destructive
              {:style {:padding "6px 12px" :font-size "12px" :margin 0}
               :on-click #(re-frame/dispatch [:ui/reset-theme])}
              "Reset Default"]])]

         [:div.plugin-section
          [:h3 {:style {:color "white" :margin-bottom "12px"}} "Install Plugin"]
          [:div {:style {:display "flex" :gap "8px" :margin-bottom "24px"}}
           [:input.form-input {:type "text" :placeholder "https://raw.githubusercontent.com/.../plugin.edn"
                               :style {:flex 1}
                               :value @plugin-url-input
                               :on-change #(reset! plugin-url-input (.. % -target -value))}]
           [:button.form-button
            {:disabled (empty? @plugin-url-input)
             :style {:background "#5865F2" :min-width "80px"}
             :on-click #(do
                          (re-frame/dispatch [:plugins/install-from-url @plugin-url-input])
                          (reset! plugin-url-input ""))}
            "Install"]]

          (when (seq installed-urls)
            [:<>
             [:h4 {:style {:color "#b5bac1" :margin-bottom "12px" :font-size "12px" :text-transform "uppercase" :letter-spacing "0.5px"}} 
              "Active Plugins"]
             [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
              (for [url installed-urls]
                ^{:key url}
                [:div {:style {:display "flex" :justify-content "space-between" :align-items "center" 
                               :background "#202225" :padding "8px 12px" :border-radius "4px"}}
                 [:span {:style {:color "#dcddde" :font-size "13px" :overflow "hidden" :text-overflow "ellipsis" :white-space "nowrap" :max-width "80%"}} 
                  url]
                 [:button {:style {:background "transparent" :border "none" :color "#ed4245" :cursor "pointer" :padding "4px"}
                           :title "Uninstall Plugin"
                           :on-click #(re-frame/dispatch [:plugins/uninstall-url url])}
                  "🗑️"]])]])]]))))


(defn plugins-installer []
  (let [tr      @(re-frame/subscribe [:i18n/tr])
        plugin-url-input (r/atom "")
        theme-url-input  (r/atom "")]
    (fn []
      (let [installed-urls @(re-frame/subscribe [:plugins/installed-urls])
            active-plugins @(re-frame/subscribe [:plugins/active-list])
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
            (tr [:settings.plugins/apply])
            ]]
          (when custom-theme?
            [:div.active-theme-card
             [:div.active-theme-info
              [:span.active-theme-label
               (tr [:settings.plugins/active-custom-theme])]
              [:span.active-theme-value current-theme]]
             [:button.form-button.destructive.reset-theme-btn
              {:on-click #(re-frame/dispatch [:ui/reset-theme])}
              (tr [:settings.plugins/reset-default])
              ]])]

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
            (tr [:settings.plugins/install])
            ]]

          (when (seq active-plugins)
           [:<>
            [:h4.active-plugins-header (tr [:settings.plugins/active-plugins])]
            [:div.active-plugins-list
             (for [plugin active-plugins]
               ^{:key (:id plugin)}
               [:div.active-plugin-item
                [:div.active-plugin-meta
                 [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
                  [:span.active-plugin-name (:name plugin (tr [:settings.plugins/unknown-plugin]))]
                  [:span.active-plugin-version (str "v" (:version plugin "1.0.0"))]]
                 [:span.active-plugin-desc (:description plugin (tr [:settings.plugins/no-description]))]]
                [:button.uninstall-plugin-btn
                 {:title (tr [:settings.plugins/uninstall-plugin])
                  :on-click #(re-frame/dispatch [:plugins/uninstall-url (:source-url plugin)])}
                 [icons/trash]]])]])]]))))
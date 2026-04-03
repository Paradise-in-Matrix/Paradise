(ns input.autocomplete
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [utils.helpers :refer [mxc->url]]))

(re-frame/reg-sub
 :mention/filtered-users
 :<- [:room/members-map]
 :<- [:suggestion/state]
 (fn [[members state] _]
   (let [_ (log/debug members)
         query (str/lower-case (or (:query state) ""))]
     (->> members
          (filter (fn [u]
                    (or (str/includes? (str/lower-case (or (:display-name u) "")) query)
                        (str/includes? (str/lower-case (or (:user-id u) "")) query))))
          (take 8)))))

(re-frame/reg-sub
 :emoji/filtered-suggestions
 :<- [:emoji/active-set]
 :<- [:suggestion/state]
 (fn [[packs state] _]
   (let [query (str/lower-case (str (or (:query state) "")))]
     (->> (vals packs)
          (mapcat :images)
          (filter (fn [[shortcode _]]
                    (let [sc-str (if (keyword? shortcode) (name shortcode) (str shortcode))]
                      (str/includes? (str/lower-case sc-str) query))))
          (take 10)))))

(re-frame/reg-event-db
 :suggestion/open-menu
 (fn [db [_ type rect]]
   (assoc db :suggestion/menu
          {:active? true
           :type    type
           :rect    {:top (.-top rect)
                     :left (.-left rect)
                     :height (.-height rect)}
           :query   ""
           :index   0})))

(re-frame/reg-event-db
 :suggestion/filter-menu
 (fn [db [_ query]]
   (assoc-in db [:suggestion/menu :query] query)))

(re-frame/reg-event-db
 :suggestion/close-menu
 (fn [db _]
   (assoc-in db [:suggestion/menu :active?] false)))

(re-frame/reg-event-db
 :suggestion/nav
 (fn [db [_ direction max-items]]
   (update-in db [:suggestion/menu :index]
              (fn [idx]
                (let [current (or idx 0)]
                  (if (= direction :next)
                    (mod (inc current) max-items)
                    (mod (dec current) max-items)))))))

(re-frame/reg-sub :suggestion/state (fn [db _] (:suggestion/menu db)))

(re-frame/reg-event-db
 :suggestion/set-index
 (fn [db [_ idx]]
   (assoc-in db [:suggestion/menu :index] idx)))

(defonce !active-command (atom nil))

(defn handle-suggestion-keydown [^js props type]
  (let [event (.-event props)
        key   (.-key event)
        state @(re-frame/subscribe [:suggestion/state])
        items (if (= type :emoji)
                @(re-frame/subscribe [:emoji/filtered-suggestions])
                @(re-frame/subscribe [:mention/filtered-users]))
        limit (count items)
        idx   (or (:index state) 0)]
    (if (and (#{"ArrowUp" "ArrowDown" "Enter" "Tab"} key) (pos? limit))
      (do
        (case key
          "ArrowUp"   (re-frame/dispatch [:suggestion/set-index (mod (dec idx) limit)])
          "ArrowDown" (re-frame/dispatch [:suggestion/set-index (mod (inc idx) limit)])
          ("Enter" "Tab")
          (let [raw-item (nth items idx)
                selected-item (if (= type :emoji) (second raw-item) raw-item)]
            (when-let [cmd @!active-command]
              (cmd #js {:props selected-item}))))
        (.preventDefault event)
        (.stopPropagation event)
        true)
      false)))


(defn emoji-suggestion-options []
  #js {:char ":"
       :command (fn [^js props]
                  (let [ed (.-editor props)
                        range (.-range props)
                        item (.-props (.-props props))
                        _ (log/debug item)]
                    (-> ed .chain .focus
                        (.deleteRange range)
                        (.insertContent #js {:type "customEmote"
                                             :attrs #js {:src (mxc->url (:url item))
                                                         :shortcode (:shortcode item)}})
                        .run)))
       :render (fn []
                 #js {:onStart (fn [props]
                                 (reset! !active-command (.-command props))
                                 (re-frame/dispatch [:suggestion/open-menu :emoji (.-clientRect props)]))
                      :onUpdate (fn [props]
                                  (reset! !active-command (.-command props))
                                  (re-frame/dispatch [:suggestion/filter-menu (.-query props)]))
                      :onKeyDown #(handle-suggestion-keydown % :emoji)
                      :onExit (fn []
                                (reset! !active-command nil)
                                (re-frame/dispatch [:suggestion/close-menu]))})})

(defn user-mention-options [on-start on-exit]
  #js {:char "@"
       :command (fn [^js props]
                  (let [ed (.-editor props)
                        range (.-range props)
                        user (.-props (.-props props))]
                    (-> ed .chain .focus
                        (.deleteRange range)
                        (.insertContent #js {:type "mention"
                                             :attrs #js {:id (:user-id user)
                                                         :label (:display-name user)}})
                        (.insertContent " ")
                        .run)))
       :render (fn []
                 #js {:onStart (fn [props]
                                 (reset! !active-command (.-command props))
                                 (when on-start (on-start (.-command props)))
                                 (let [active-id @(re-frame/subscribe [:rooms/active-id])]
                                   (re-frame/dispatch [:rooms/fetch-members active-id]))
                                 (re-frame/dispatch [:suggestion/open-menu :mention (.-clientRect props)]))
                      :onUpdate (fn [props]
                                  (reset! !active-command (.-command props))
                                  (re-frame/dispatch [:suggestion/filter-menu (.-query props)]))
                      :onKeyDown #(handle-suggestion-keydown % :mention)
                      :onExit (fn []
                                (reset! !active-command nil)
                                (when on-exit (on-exit))
                                (re-frame/dispatch [:suggestion/close-menu]))})})

(defn suggestion-menu []
  (let [{:keys [active? type rect index] :as state} @(re-frame/subscribe [:suggestion/state])
        emojis  @(re-frame/subscribe [:emoji/filtered-suggestions])
        members @(re-frame/subscribe [:mention/filtered-users])
        tr              @(re-frame/subscribe [:i18n/tr])]
    (when (and active? rect)
      (let [is-emoji? (= type :emoji)
            items     (if is-emoji? emojis members)
            max-items (count items)
            safe-idx  (if (>= index max-items) 0 index)
            render-above? (> (:top rect) 250)]
        [:div.suggestion-popup
         {:class (when render-above? "is-above")
          :style {:top  (if render-above?
                          (str (- (:top rect) 210) "px")
                          (str (+ (:top rect) (:height rect) 5) "px"))
                  :left (str (:left rect) "px")}}
         (if (empty? items)
           [:div.suggestion-no-results (tr [:composer.suggestions/no-results])]
           (doall
            (map-indexed
             (fn [idx item]
               (let [selected? (= idx safe-idx)]
                 ^{:key (if is-emoji? (first item) (:user-id item))}
                 [:div.suggestion-item
                  {:class (when selected? "is-selected")
                   :on-mouse-down (fn [e]
                                    (.preventDefault e)
                                    (.stopPropagation e)
                                    (when-let [cmd @!active-command]
                                      (let [selected (if is-emoji? (second item) item)]
                                        (cmd #js {:props selected}))))}
                  (if is-emoji?
                    (let [[shortcode emote-data] item]
                      [:<>
                       [:img.suggestion-img {:src (mxc->url (:url emote-data))}]
                       [:span.suggestion-text ":" shortcode ":"]])
                    [:<>
                     (if (:avatar-url item)
                       [:img.suggestion-avatar {:src (mxc->url (:avatar-url item))}]
                       [:div.suggestion-avatar-placeholder])
                     [:div.suggestion-details
                      [:span.suggestion-name (:display-name item)]
                      [:span.suggestion-id (:user-id item)]]])]))
             items)))]))))

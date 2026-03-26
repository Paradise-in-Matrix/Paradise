(ns container.members
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [promesa.core :as p]
   [clojure.string :as str]
   ["react-virtuoso" :refer [Virtuoso GroupedVirtuoso]]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]
   [utils.helpers :refer [mxc->url fetch-state-event]]
   [utils.svg :as icons]
   [utils.global-ui :refer [avatar]]
   ))

(defn drain-members-iterator [iterator]
  (let [total-len (.len iterator)
        results (transient [])]
    (loop []
      (let [chunk (.nextChunk iterator 100)]
        (if (and chunk (> (.-length chunk) 0))
          (do
            (reduce (fn [acc item] (conj! acc item)) results chunk)
            (recur))
          (persistent! results))))))

(re-frame/reg-event-fx
 :room/fetch-members
 (fn [{:keys [db]} [_ room-id]]
   (if-let [room (aget (get-in db [:rooms-unfiltered-cache room-id]) "raw-room")
            ]
     (do
       (-> (.members room)
           (.then (fn [iterator]
                    (try
                      (let [all-members (drain-members-iterator iterator)]
                        (re-frame/dispatch [:room/fetch-members-success room-id all-members]))
                      (finally
                        (.uniffiDestroy iterator)))))
           (.catch (fn [err]
                     (js/console.error "Failed to fetch members:" err)
                     (re-frame/dispatch [:room/fetch-members-error room-id err]))))
       {:db (assoc-in db [:room-members room-id :loading?] true)})
     {:db db})))

(re-frame/reg-event-db
 :room/fetch-members-success
 (fn [db [_ room-id raw-members]]
   (let [clean-members (->> raw-members
                            (keep (fn [m]
                                    (when m
                                      (let [user-id (.-userId m)
                                            membership (.-tag (.-membership m))
                                            display-name (.-displayName m)
                                            local-part (or (second (re-find #"^@([^:]+)" user-id)) user-id)
                                            raw-pl (or (some-> m .-powerLevel .-inner .-value) 0)
                                            pl (js/Number raw-pl)]
                                        {:user-id      user-id
                                         :membership   membership
                                         :power-level  pl
                                         :display-name (or display-name user-id)
                                         :sort-name    (str/lower-case (or display-name local-part))
                                         :avatar-url   (when (.-avatarUrl m) (mxc->url (.-avatarUrl m)))}))))
                            (vec))]
     (-> db
         (assoc-in [:room-members room-id :data] clean-members)
         (assoc-in [:room-members room-id :loading?] false)))))

(re-frame/reg-event-db
 :room/set-member-sort
 (fn [db [_ room-id sort-type]]
   (assoc-in db [:room-members room-id :sort-type] sort-type)))

(re-frame/reg-sub
 :room/member-sort-type
 (fn [db [_ room-id]]
   (get-in db [:room-members room-id :sort-type] :power-level)))

(re-frame/reg-sub
 :room/member-counts
 (fn [[_ room-id]]
   (re-frame/subscribe [:room/members room-id]))
 (fn [members _]
   (frequencies (map :membership members))))

(re-frame/reg-sub
 :room/members-map
 (fn [[_ room-id]]
   (re-frame/subscribe [:room/members room-id]))
 (fn [members _]
   (zipmap (map :user-id members) members)))


(re-frame/reg-sub
 :room/filtered-and-sorted-members
 (fn [[_ room-id]]
   [(re-frame/subscribe [:room/members room-id])
    (re-frame/subscribe [:room/member-filter-query room-id])
    (re-frame/subscribe [:room/member-filter-type room-id])
    (re-frame/subscribe [:room/member-sort-type room-id])])
 (fn [[members query f-type s-type] _]
   (let [by-type (filter #(= (:membership %) f-type) members)
         filtered (if (str/blank? query)
                    by-type
                    (let [q (str/lower-case query)]
                      (filter #(or (str/includes? (:sort-name %) q)
                                   (str/includes? (str/lower-case (:user-id %)) q))
                              by-type)))]
     (case s-type
       :alphabetical (sort-by :sort-name filtered)
;;       :latest       filtered
       :power-level  (sort-by (fn [m] [(- (:power-level m)) (:sort-name m)]) filtered)
       (sort-by (fn [m] [(- (:power-level m)) (:sort-name m)]) filtered)))))


(re-frame/reg-event-db
 :room/set-member-filter-type
 (fn [db [_ room-id type]]
   (assoc-in db [:room-members room-id :filter-type] type)))

(re-frame/reg-event-db
 :room/set-member-filter
 (fn [db [_ room-id query]]
   (assoc-in db [:room-members room-id :filter-query] query)))

(re-frame/reg-sub
 :room/member-filter-type
 (fn [db [_ room-id]]
   (get-in db [:room-members room-id :filter-type] "Join")))

(re-frame/reg-sub
 :room/filtered-members
 (fn [[_ room-id]]
   [(re-frame/subscribe [:room/members room-id])
    (re-frame/subscribe [:room/member-filter-query room-id])
    (re-frame/subscribe [:room/member-filter-type room-id])])
 (fn [[members query type] _]
   (let [by-type (filter #(= (:membership %) type) members)]
     (if (str/blank? query)
       by-type
       (let [q (str/lower-case query)]
         (filter #(or (str/includes? (:sort-name %) q)
                      (str/includes? (str/lower-case (:user-id %)) q))
                 by-type))))))

(re-frame/reg-sub
 :room/member-filter-query
 (fn [db [_ room-id]]
   (get-in db [:room-members room-id :filter-query] "")))

(re-frame/reg-sub
 :room/member-groups
 (fn [[_ room-id]]
   [(re-frame/subscribe [:room/filtered-and-sorted-members room-id])
    (re-frame/subscribe [:room/power-level-tags room-id])
    (re-frame/subscribe [:room/member-sort-type room-id])])
 (fn [[members tags sort-type] _]
   (if (= sort-type :power-level)
     (let [grouped (group-by :power-level members)
           pls     (sort > (keys grouped))
           counts  (map #(count (get grouped %)) pls)
           labels  (map #(let [tag (get tags (keyword (str %)))]
                           (or (:name tag) (str "Role: " %)))
                        pls)]
       {:counts counts
        :labels labels
        :flat-members (mapcat #(get grouped %) pls)})
     {:counts [(count members)]
      :labels [(case sort-type :alphabetical "A-Z" #_:latest #_"Latest" "Members")]
      :flat-members members})))

(re-frame/reg-event-fx
 :room/fetch-power-level-tags
 (fn [{:keys [db]} [_ room-id]]
   (let
 [client (:client db)
           session (when client (.session client))
token (.-accessToken session)
homeserver (.-homeserverUrl session)]
     (-> (fetch-state-event homeserver token room-id "in.cinny.room.power_level_tags" "")
         (.then (fn [data]
                  (when data
                    (re-frame/dispatch [:room/fetch-power-level-tags-success
                                        room-id
                                        (js->clj data :keywordize-keys true)])))))
     {:db db})))

(re-frame/reg-event-db
 :room/fetch-power-level-tags-success
 (fn [db [_ room-id tags-data]]
   (assoc-in db [:room-members room-id :power-level-tags] tags-data)))

(re-frame/reg-sub
 :room/power-level-tags
 (fn [db [_ room-id]]
   (get-in db [:room-members room-id :power-level-tags] {})))

(re-frame/reg-sub
 :room/members-loading?
 (fn [db [_ room-id]]
   (get-in db [:room-members room-id :loading?] false)))

(re-frame/reg-sub
 :room/members
 (fn [db [_ room-id]]
   (get-in db [:room-members room-id :data] [])))

(defn filter-pill [room-id label type active-type count]
  [:button.filter-pill
   {:class (when (= type active-type) "active")
    :on-click #(re-frame/dispatch [:room/set-member-filter-type room-id type])}
   (str label " (" (or count 0) ")")])

(defn build-member-actions [tr {:keys [user-id display-name]} active-room]
  [{:id "view-profile"
    :label (tr [:container.member-actions/view-profile])
    :action #(js/console.log "View profile for:" user-id)}
   {:id "mention"
    :label (tr [:container.member-actions/mention] [display-name])
    :action #(js/console.log "Mention:" user-id)}
   {:id "message"
    :label (tr [:container.member-actions/message])
    :action #(js/console.log "DM:" user-id)}
   {:id "kick"
    :label (tr [:container.member-actions/kick])
    :class-name "text-danger"
    :action #(js/console.log "Kick:" user-id)}])

(defn profile-popover-trigger [member custom-tags active-room pos & children]
  (r/with-let [!hover-timer (atom nil)
               clear-timer! #(when @!hover-timer
                               (js/clearTimeout @!hover-timer)
                               (reset! !hover-timer nil))]
    (let [tr @(re-frame/subscribe [:i18n/tr])]
      (into [:div
             {:style {:display "inline-block" :cursor "pointer"}
              :on-mouse-enter (fn [e]
                                (let [rect (.getBoundingClientRect (.-currentTarget e))
                                      px (if (= pos :left)
                                           (- (.-left rect) 265)
                                           (+ (.-right rect) 15))
                                      py (.-top rect)]
                                  (clear-timer!)
                                  (reset! !hover-timer
                                          (js/setTimeout
                                           #(re-frame/dispatch
                                             [:ui/open-popover :profile-preview
                                              {:x         px
                                               :y         py
                                               :width     265
                                               :height    150
                                               :backdrop? false
                                               :member    member
                                               :tags      custom-tags}])
                                           400))))
              :on-mouse-leave (fn []
                                (clear-timer!)
                                (re-frame/dispatch [:ui/close-popover]))
              :on-context-menu (fn [e]
                                 (.preventDefault e)
                                 (.stopPropagation e)
                                 (clear-timer!)
                                 (re-frame/dispatch [:ui/close-popover])
                                 (re-frame/dispatch
                                  [:context-menu/open
                                   {:x (.-clientX e)
                                    :y (.-clientY e)
                                    :items (build-member-actions tr member active-room)}]))}]
            children))))


(defn member-item [m custom-tags active-room]
  (let [pl         (:power-level m)
        tag-data   (get custom-tags (keyword (str pl)))
        role-name  (:name tag-data)
        role-color (or (:color tag-data) "var(--text-primary)")
        icon-url   (some-> tag-data :icon :key mxc->url)]
    [profile-popover-trigger m custom-tags active-room :left
     [:div.member-item
      [avatar {:id (:user-id m) :name (:display-name m) :url (:avatar-url m) :size 32}]
      [:div.member-item-info
       [:div.member-item-name-row
        [:span.member-item-name {:style {:color role-color}} (:display-name m)]
        (when role-name
          [:div.member-item-role-badge {:style {:color role-color :border (str "1px solid " role-color)}}
           (when icon-url
             [:img.member-item-role-icon {:src icon-url}])
           role-name])]
       [:span.member-item-user-id (:user-id m)]]]]))


(defn member-list []
  (let [tr           @(re-frame/subscribe [:i18n/tr])
        active-room @(re-frame/subscribe [:rooms/active-id])
        custom-tags @(re-frame/subscribe [:room/power-level-tags active-room])
        query       @(re-frame/subscribe [:room/member-filter-query active-room])
        filter-type @(re-frame/subscribe [:room/member-filter-type active-room])
        sort-type   @(re-frame/subscribe [:room/member-sort-type active-room])
        counts      @(re-frame/subscribe [:room/member-counts active-room])
        loading?    @(re-frame/subscribe [:room/members-loading? active-room])
        groups      @(re-frame/subscribe [:room/member-groups active-room])
        group-counts (:counts groups)
        group-labels (:labels groups)
        flat-members (:flat-members groups)]
    (when (and (not loading?) (empty? @(re-frame/subscribe [:room/members active-room])))
      (re-frame/dispatch [:room/fetch-members active-room])
      (re-frame/dispatch [:room/fetch-power-level-tags active-room]))

    [:div.member-list
     [:div.member-controls-container
      [:div.member-search-container
       [:div.member-search-icon-wrapper
        [icons/search {:width "14px" :height "14px"}]]
       [:input.member-search-input
        {:type "text"
         :placeholder (tr [:container.members/search-placeholder])
         :value query
         :on-change #(re-frame/dispatch [:room/set-member-filter active-room (.. % -target -value)])}]
       (when-not (str/blank? query)
         [:button.member-search-clear-btn
          {:on-click #(re-frame/dispatch [:room/set-member-filter active-room ""])}
          [icons/exit]])]

      [:div.member-dropdown-row
       [:select.member-dropdown
        {:value filter-type
         :on-change #(re-frame/dispatch [:room/set-member-filter-type active-room (.. % -target -value)])}
        [:option {:value "Join"}   (tr [:container.members.filter/joined] [(get counts "Join" 0)])]
        [:option {:value "Invite"} (tr [:container.members.filter/invited] [(get counts "Invite" 0)])]
        [:option {:value "Leave"}  (tr [:container.members.filter/left] [(get counts "Leave" 0)])]
        [:option {:value "Ban"}    (tr [:container.members.filter/banned] [(get counts "Ban" 0)])]
        ]

       [:select.member-dropdown
        {:value (name sort-type)
         :on-change #(re-frame/dispatch [:room/set-member-sort active-room (keyword (.. % -target -value))])}
        [:option {:value "power-level"}  (tr [:container.members.sort/role])]
        [:option {:value "alphabetical"} (tr [:container.members.sort/name])]
        [:option {:value "latest"}       (tr [:container.members.sort/latest])]
        ]]]

     (if loading?
       [:div.member-list-loading (tr [:container.members/loading])]
       [:div.member-list-scroll
        (if (empty? flat-members)
          [:div.member-list-empty (tr [:container.members/no-results])]
          [:> GroupedVirtuoso
           {:groupCounts (clj->js group-counts)
            :style {:height "100%" :width "100%"}
            :groupContent (fn [index]
                            (r/as-element
                             [:div.member-list-group-header
                              (nth group-labels index)]))
            :itemContent (fn [index _group-index]
                           (r/as-element [member-item (nth flat-members index) custom-tags]))}])])]))


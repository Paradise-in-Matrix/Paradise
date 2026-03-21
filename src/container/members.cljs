
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
     (if (clojure.string/blank? query)
       by-type
       (let [q (clojure.string/lower-case query)]
         (filter #(or (clojure.string/includes? (:sort-name %) q)
                      (clojure.string/includes? (clojure.string/lower-case (:user-id %)) q))
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

(defn build-member-actions [{:keys [user-id display-name]} active-room]
  [{:id "view-profile" :label "View Profile"
    :action #(js/console.log "View profile for:" user-id)}
   {:id "mention" :label (str "Mention @" display-name)
    :action #(js/console.log "Mention:" user-id)}
   {:id "message" :label "Direct Message"
    :action #(js/console.log "DM:" user-id)}
   {:id "kick" :label "Kick Member" :class-name "text-danger"
    :action #(js/console.log "Kick:" user-id)}])


(defn profile-popover-trigger [member custom-tags active-room & children]
  (r/with-let [!hover-timer (atom nil)
               clear-timer! #(when @!hover-timer
                               (js/clearTimeout @!hover-timer)
                               (reset! !hover-timer nil))]
    (into [:div
           {:style {:display "inline-block" :cursor "pointer"}
            :on-mouse-enter (fn [e]
                              (let [rect (.getBoundingClientRect (.-currentTarget e))
                                    px (+ (.-right rect) 15)
                                    py (.-top rect)]
                                (clear-timer!)
                                (reset! !hover-timer
                                        (js/setTimeout
                                         #(re-frame/dispatch [:profile-preview/open
                                                              {:x px :y py :member member :tags custom-tags}])
                                         400))))
            :on-mouse-leave (fn []
                              (clear-timer!)
                              (re-frame/dispatch [:profile-preview/close]))
            :on-context-menu (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (clear-timer!)
                               (re-frame/dispatch [:profile-preview/close])
                               (re-frame/dispatch
                                [:context-menu/open
                                 {:x (.-clientX e)
                                  :y (.-clientY e)
                                  :items (build-member-actions member active-room)}]))}]
          children)))


(defn member-item [m custom-tags active-room]
  (r/with-let [!hover-timer (atom nil)
               clear-timer! #(when @!hover-timer
                               (js/clearTimeout @!hover-timer)
                               (reset! !hover-timer nil))]
    (let [pl         (:power-level m)
          tag-data   (get custom-tags (keyword (str pl)))
          role-name  (:name tag-data)
          role-color (or (:color tag-data) "var(--text-primary)")
          icon-url   (some-> tag-data :icon :key mxc->url)]
      [:div.member-item
       {:on-mouse-enter (fn [e]
                          (let [rect (.getBoundingClientRect (.-currentTarget e))
                                px (- (.-left rect) 260)
                                py (.-top rect)]
                            (clear-timer!)
                            (reset! !hover-timer
                                    (js/setTimeout
                                     #(re-frame/dispatch [:profile-preview/open
                                                          {:x px :y py :member m :tags custom-tags}])
                                     400))))
        :on-mouse-leave (fn []
                          (clear-timer!)
                          (re-frame/dispatch [:profile-preview/close]))
        :on-context-menu (fn [e]
                           (.preventDefault e)
                           (.stopPropagation e)
                           (clear-timer!)
                           (re-frame/dispatch [:profile-preview/close])
                           (re-frame/dispatch
                            [:context-menu/open
                             {:x (.-clientX e)
                              :y (.-clientY e)
                              :items (build-member-actions m active-room)}]))}
       [avatar {:id (:user-id m) :name (:display-name m) :url (:avatar-url m) :size 32}]
       [:div.member-item-info
        [:div.member-item-name-row
         [:span.member-item-name {:style {:color role-color}} (:display-name m)]
         (when role-name
           [:div.member-item-role-badge {:style {:color role-color :border (str "1px solid " role-color)}}
            (when icon-url
              [:img.member-item-role-icon {:src icon-url}])
            role-name])]
        [:span.member-item-user-id (:user-id m)]]])))



(re-frame/reg-event-db
 :profile-preview/open
 (fn [db [_ {:keys [x y member tags]}]]
   (assoc db :profile-preview {:open? true :x x :y y :member member :tags tags})))

(re-frame/reg-event-db
 :profile-preview/close
 (fn [db _]
   (assoc db :profile-preview {:open? false})))

(re-frame/reg-sub
 :profile-preview/state
 (fn [db _]
   (:profile-preview db {:open? false})))


(defn global-profile-preview []
  (let [{:keys [open? x y member tags]} @(re-frame/subscribe [:profile-preview/state])]
    (when (and open? member)
      (let [pl         (:power-level member)
            tag-data   (get tags (keyword (str pl)))
            role-name  (:name tag-data)
            role-color (or (:color tag-data) "var(--text-primary)")
            icon-url   (some-> tag-data :icon :key mxc->url)]
        [:div.profile-preview-card
         {:style {:left (max 10 x) :top y}}
         [:div.profile-preview-cover]
         [:div.profile-preview-content
          [:div.profile-preview-avatar-wrap
           [avatar {:id (:user-id member)
                    :name (:display-name member)
                    :url (mxc->url (:avatar-url member))
                    :size 64}]]
          [:div.profile-preview-id-block
           [:span.profile-preview-name {:style {:color role-color}}
            (:display-name member)]
           [:span.profile-preview-id
            (:user-id member)]]
          (when role-name
            [:div.profile-preview-role-row
             (when icon-url
               [:img.member-item-role-icon {:src icon-url}])
             [:span.profile-preview-role-text {:style {:color role-color}}
              role-name]])]]))))


(defn member-list []
  (let [active-room @(re-frame/subscribe [:rooms/active-id])
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
         :placeholder "Search members..."
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
        [:option {:value "Join"}   (str "Joined (" (get counts "Join" 0) ")")]
        [:option {:value "Invite"} (str "Invited (" (get counts "Invite" 0) ")")]
        [:option {:value "Leave"}  (str "Left (" (get counts "Leave" 0) ")")]
        [:option {:value "Ban"}    (str "Banned (" (get counts "Ban" 0) ")")]]

       [:select.member-dropdown
        {:value (name sort-type)
         :on-change #(re-frame/dispatch [:room/set-member-sort active-room (keyword (.. % -target -value))])}
        [:option {:value "power-level"} "Sort: Role"]
        [:option {:value "alphabetical"} "Sort: Name"]
        [:option {:value "latest"} "Sort: Latest"]]]]

     (if loading?
       [:div.member-list-loading "Loading members..."]
       [:div.member-list-scroll
        (if (empty? flat-members)
          [:div.member-list-empty "No members found"]
          [:> GroupedVirtuoso
           {:groupCounts (clj->js group-counts)
            :style {:height "100%" :width "100%"}
            :groupContent (fn [index]
                            (r/as-element
                             [:div.member-list-group-header
                              (nth group-labels index)]))
            :itemContent (fn [index _group-index]
                           (r/as-element [member-item (nth flat-members index) custom-tags]))}])])]))


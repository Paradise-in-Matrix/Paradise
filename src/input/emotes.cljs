(ns input.emotes
  (:require
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [reagent.core :as r]
   [utils.helpers :refer [mxc->url fetch-state-event fetch-room-state]]
   [utils.global-ui :refer [click-away-wrapper]]
   ["emojibase-data/en/data.json" :as emoji-data]
   ["react" :as react]
   ))

(defn shape-emote-packs [room-id events]
  (->> events
       (filter #(= (:type %) "im.ponies.room_emotes"))
       (map (fn [e] {:pack-id (or (not-empty (:state_key e)) room-id)
                     :data (:content e)}))))

(defn unwrap-rooms [raw-json]
  (let [data (js->clj (js/JSON.parse raw-json) :keywordize-keys true)
        rooms-map (:rooms data)]
    (for [[rid packs] rooms-map
          [pack-name _] packs]
      [(name rid) (name pack-name)])))

(defn fetch-emote-packs [homeserver token [rid pack-name]]
  (-> (fetch-state-event homeserver token rid "im.ponies.room_emotes" pack-name)
      (p/then (fn [json]
                (when json
                  {:pack-id pack-name :data json})))
      (p/catch (fn [err] (log/error "Failed to fetch pack" pack-name err) nil))))

(re-frame/reg-fx
 :principle-fetch-emotes
 (fn [{:keys [token homeserver client]}]
   (p/let [raw-json (.accountData client "im.ponies.emote_rooms")
           fetch-targets (unwrap-rooms raw-json)
           results (p/all (map #(fetch-emote-packs homeserver token %) fetch-targets))]
     (re-frame/dispatch [:sdk/save-all-emotes :account nil (remove nil? results)]))))

(re-frame/reg-fx
 :principle-fetch-room-emotes-fx
 (fn [{:keys [token homeserver source-type room-id]}]
   (-> (fetch-room-state homeserver token room-id nil nil
                         (partial shape-emote-packs room-id))
       (p/then (fn [packs]
                 (when (seq packs)
                   (re-frame/dispatch [:sdk/save-all-emotes source-type room-id packs]))))
       (p/catch #(log/error "Room fetch failed" room-id %)))))

(re-frame/reg-event-fx
 :sdk/fetch-all-emotes
 (fn [{:keys [db]} _]
   (let [client (:client db)
         session (some-> client (.session))]
     (if (and client session)
       {:principle-fetch-emotes {:token (.-accessToken session)
                                 :homeserver (.-homeserverUrl session)
                                 :client client}}
       {}))))

(re-frame/reg-event-fx
 :sdk/fetch-room-emotes
 (fn [{:keys [db]} [_ source-type room-id]]
   (let [client (:client db)
         session (some-> client (.session))]
     (if (and session room-id)
       {:principle-fetch-room-emotes-fx {:token (.-accessToken session)
                                         :homeserver (.-homeserverUrl session)
                                         :source-type source-type
                                         :room-id room-id}}
       {}))))

(re-frame/reg-event-db
 :sdk/save-all-emotes
 (fn [db [_ source-type source-id fetched-packs]]
   (let [structured-packs (into {}
                                (for [{:keys [pack-id data]} (remove nil? fetched-packs)
                                      :let [clj-data    (if (map? data) data (js->clj data :keywordize-keys true))
                                            images      (:images clj-data)
                                            pack-info   (:pack clj-data)
                                            usage-set   (set (:usage pack-info []))
                                            is-sticker? (contains? usage-set "sticker")]
                                      :when (seq images)]
                                  [pack-id {:name             (or (:display_name pack-info) pack-id)
                                            :avatar           (:avatar_url pack-info)
                                            :is-sticker-pack? is-sticker?
                                            :images           images}]))]
     (if (= source-type :account)
       (assoc-in db [:emoji/packs :account] structured-packs)
       (assoc-in db [:emoji/packs source-type source-id] structured-packs)))))

(re-frame/reg-sub
 :emoji/active-set
 (fn [db _]
   (let [active-space (:active-space-id db)
         active-room (:active-room-id db)
         account-packs (get-in db [:emoji/packs :account] {})
         space-packs (when active-space (get-in db [:emoji/packs :space active-space] {}))
         room-packs (when active-room (get-in db [:emoji/packs :room active-room] {}))]
     (merge account-packs space-packs room-packs))))

(defonce categorized-system-packs
  (let [unwrap (fn [mod] (if (and mod (.-default mod)) (.-default mod) mod))
        raw-data (js->clj (unwrap emoji-data) :keywordize-keys true)
        group-meta {0 {:name "Smileys & Emotion" :icon "😀"}
                    1 {:name "People & Body"     :icon "👋"}
                    ;; 2 is Component (Skipped)
                    3 {:name "Animals & Nature"  :icon "🐶"}
                    4 {:name "Food & Drink"      :icon "🍔"}
                    5 {:name "Travel & Places"   :icon "✈️"}
                    6 {:name "Activities"        :icon "⚽"}
                    7 {:name "Objects"           :icon "💡"}
                    8 {:name "Symbols"           :icon "💖"}
                    9 {:name "Flags"             :icon "🚩"}}]
    (->> raw-data
         (group-by :group)
         (reduce-kv (fn [acc group-id emojis]
                      (if (or (nil? group-id) (= group-id 9))
                        acc
                        (let [meta    (get group-meta group-id {:name "Other" :icon "📦"})
                              pack-id (str "system-" group-id)]
                          (assoc acc pack-id
                                 {:name             (:name meta)
                                  :hero-icon        (:icon meta)
                                  :is-unicode?      true
                                  :system-priority  group-id
                                  :images (into {}
                                                (for [e emojis
                                                      :let [char (or (:emoji e) (:unicode e))
                                                            slug (or (first (:shortcodes e))
                                                                     (:annotation e)
                                                                     (:name e)
                                                                     (:hexcode e))]
                                                      :when (and char slug)]
                                                  [(str slug) {:unicode (str char)}]))}))))
                    {}))))








(defn emoji-sticker-board [{:keys [on-close on-insert-emoji on-insert-native on-send-sticker]}]
  (re-frame/dispatch [:sdk/fetch-all-emotes])
  (let [selected-pack-id (r/atom nil)
        sticker-mode?    (r/atom false)
        tr-sub           (re-frame/subscribe [:i18n/tr])]
    (fn [{:keys [on-close on-insert-emoji on-insert-native on-send-sticker]}]
      (let [tr           @tr-sub
            matrix-packs @(re-frame/subscribe [:emoji/active-set])
            popover-active @(re-frame/subscribe [:ui/active-popover])
            packs        (merge categorized-system-packs matrix-packs)
            sorted-ids   (sort-by #(get-in packs [% :system-priority] 999) (keys packs))
            active-id    (or @selected-pack-id (first sorted-ids))
            active-pack  (get packs active-id)
            content      [:div.emoji-popover

         (if (empty? packs)
            [:div.emoji-loading (tr [:composer.emotes/no-emotes])]
            [:div.emoji-container
             [:div.emoji-sidebar
              (for [pack-id sorted-ids]
                (let [pack-data (get packs pack-id)
                      pname     (or (:name pack-data) (str pack-id))
                      avatar    (:avatar pack-data)]
                  [:div.sidebar-pack-item
                   {:key      pack-id
                    :class    (when (= active-id pack-id) "active")
                    :on-click #(reset! selected-pack-id pack-id)
                    :title    pname}
                   (cond
                     (:is-unicode? pack-data)
                     [:span.pack-text-icon (:hero-icon pack-data)]
                     avatar
                     [:img.pack-icon {:src (mxc->url avatar)}]
                     :else
                     [:span.pack-text-icon (subs pname 0 (min 2 (count pname)))])
                   ]))]
             [:div.emoji-grid-area


              [:div.emoji-pack-header
               [:div.emoji-pack-title (or (:name active-pack) (tr [:composer.emotes/default-title]))]
                              (when-not (:is-unicode? active-pack)
                 [:div.emoji-mode-switch
                  [:button {:class    (when-not @sticker-mode? "active")
                            :on-click #(reset! sticker-mode? false)}
                   (tr [:composer.emotes/mode-inline])]
                  [:button {:class    (when @sticker-mode? "active")
                            :on-click #(reset! sticker-mode? true)}
                   (tr [:composer.emotes/mode-sticker])]])]

              [:div.emoji-grid
               (for [[shortcode item] (get active-pack :images {})]
                 [:div.emoji-cell
                  {:key      shortcode
                   :on-click (fn []
                               (cond
                                 (:is-unicode? active-pack)
                                 (on-insert-native (:unicode item))
                                 (or @sticker-mode? (:is-sticker-pack? active-pack))
                                 (on-send-sticker (:url item) shortcode (:info item))
                                 :else
                                 (on-insert-emoji shortcode (:url item))))}

                  (if (:is-unicode? active-pack)
                    [:span.native-emoji {:title shortcode} (:unicode item)]
                    [:img.emoji-img
                     {:src      (mxc->url (:url item))
                      :title    shortcode
                      :loading  "lazy"
                      :alt      shortcode}])])]]])]
            ]

        (if (nil? popover-active)
          [click-away-wrapper
           {:on-close on-close :z-index 19}
           content
           ]
          content)
        ))))





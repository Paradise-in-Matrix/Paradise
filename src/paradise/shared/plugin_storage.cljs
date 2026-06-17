(ns paradise.shared.plugin-storage
  (:require
   [re-frame.core :as rf]
   [cljs.reader :as reader]
   [cljs.core.async :refer [go <!]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [promesa.core :as p]
   [cljs-workers.core :as main]
   [paradise.shared.sci-runner.ui :as runner]
   [paradise.shared.client.state :as state]
   [paradise.shared.client.registry]
   ))


(defn inject-css! [id url]
  (when-not (.getElementById js/document id)
    (let [link (.createElement js/document "link")]
      (set! (.-id link) id)
      (set! (.-rel link) "stylesheet")
      (set! (.-href link) url)
      (.appendChild js/document.head link))))

(defn inject-script! [url]
  (p/create
   (fn [resolve-fn reject]
     (let [script (.createElement js/document "script")]
       (set! (.-src script) url)
       (set! (.-crossOrigin script) "anonymous")
       (set! (.-onload script) #(resolve-fn true))
       (set! (.-onerror script) #(reject (str "Failed to load script: " url)))
       (.appendChild js/document.head script)))))

(defn remove-css! [id]
  (when-let [link (.getElementById js/document id)]
    (.remove link)))

(rf/reg-event-fx
 :plugins/hydrate-urls
 (fn [{:keys [db]} [_ urls]]
   (let [valid-urls (if (coll? urls) (vec urls) [])]
     {:db         (assoc db :plugins/installed-urls valid-urls)
      :dispatch-n (mapv (fn [url] [:plugins/fetch-and-boot url]) valid-urls)})))

(rf/reg-sub
 :plugins/disabled-urls
 (fn [db _]
   (set (:plugins/disabled-urls db #{}))))

(rf/reg-event-fx
 :plugins/toggle-plugin
 (fn [{:keys [db]} [_ url enabled?]]
   (let [disabled     (set (:plugins/disabled-urls db #{}))
         new-disabled (if enabled? (disj disabled url) (conj disabled url))
         plugin       (->> (:plugins/active db) (vals) (filter #(= (:source-url %) url)) first)
         plugin-id    (:id plugin)]
     (when (and (not enabled?) plugin-id)
       (remove-css! (str "plugin-css-" (name plugin-id)))
       (paradise.shared.client.registry/remove-plugin-overrides! plugin-id)
       (paradise.shared.client.registry/remove-plugin-slots! plugin-id))

     {:db (assoc db :plugins/disabled-urls new-disabled)
      :fx (cond-> [[:dispatch [:settings/save "disabled_plugin_urls" (clj->js (vec new-disabled))]]]
            enabled? (conj [:dispatch [:plugins/execute-boot-sequence url plugin]]))})))

(rf/reg-event-fx
 :plugins/install-from-url
 (fn [{:keys [db]} [_ url]]
   {:db (assoc db :plugins/installing? true)
    :dispatch [:plugins/fetch-and-boot url]}))

(rf/reg-event-fx
 :plugins/installation-success
 (fn [{:keys [db]} [_ source-url plugin-map]]
   (let [current-urls (:plugins/installed-urls db [])
         new-urls     (vec (distinct (conj current-urls source-url)))]
     {:db (-> db
              (assoc :plugins/pending-security-approval nil
                     :plugins/installing? false
                     :plugins/installed-urls new-urls)
              (update-in [:plugins/active (:id plugin-map)]
                         (constantly (assoc plugin-map :source-url source-url))))
      :dispatch [:settings/save "plugin_urls" (clj->js new-urls)]})))

(rf/reg-event-fx
 :plugins/uninstall-url
 (fn [{:keys [db]} [_ url]]
   (let [current-urls (:plugins/installed-urls db [])
         new-urls     (vec (remove #(= % url) current-urls))
         plugin       (->> (:plugins/active db) (vals) (filter #(= (:source-url %) url)) first)
         plugin-id    (:id plugin)]
     (when plugin-id
       (remove-css! (str "plugin-css-" (name plugin-id)))
       (paradise.shared.client.registry/remove-plugin-overrides! plugin-id))

     {:db (-> db
              (assoc :plugins/installed-urls new-urls)
              (update :plugins/active dissoc plugin-id))
      :dispatch [:settings/save "plugin_urls" (clj->js new-urls)]})))


(rf/reg-event-db
 :plugins/hydrate-disabled-urls
 (fn [db [_ urls]]
   (let [valid-urls (if (coll? urls) (set urls) #{})]
     (assoc db :plugins/disabled-urls valid-urls))))

(rf/reg-event-fx
 :plugins/fetch-and-boot
 (fn [{:keys [db]} [_ url]]
   (-> (js/fetch url)
       (.then (fn [resp]
                (if (.-ok resp) (.text resp)
                    (throw (js/Error. (str "Failed to fetch plugin: " (.-status resp)))))))
       (.then (fn [edn-string]
                (let [plugin-map    (reader/read-string edn-string)
                      disabled-urls (:plugins/disabled-urls db #{})
                      disabled?     (contains? disabled-urls url)]
                  (if disabled?
                    (rf/dispatch [:plugins/installation-success url plugin-map])
                    (if (seq (:external-scripts plugin-map))
                      (rf/dispatch [:plugins/prompt-security-warning url plugin-map])
                      (rf/dispatch [:plugins/execute-boot-sequence url plugin-map]))))))
       (.catch (fn [err]
                 (js/console.error "Plugin boot failed for" url "-" err))))
   {:db (assoc db :plugins/installing? true)}))


(rf/reg-event-fx
 :plugins/execute-boot-sequence
 (fn [{:keys [db]} [_ url plugin-map]]
   (go
     (try
       (when-let [css-url (:theme-url plugin-map)]
         (inject-css! (str "plugin-css-" (name (:id plugin-map))) css-url))

       (when-let [scripts (:external-scripts plugin-map)]
         (doseq [script-url scripts]
           (<p! (inject-script! script-url))))

       (when-let [modules (:modules plugin-map)]
         (doseq [{:keys [ns target code]} modules]
           (case target
             :engine
             (let [res (<! (main/do-with-pool! @state/!engine-pool
                                               {:handler :evaluate-worker-form
                                                :arguments {:plugin-id (:id plugin-map)
                                                            :form-str code}}))]
               (when (= (:status res) "error")
                 (throw (js/Error. (str "Engine Eval Failed for " ns ": " (:msg res))))))

             :ui
             (runner/evaluate-ui-form (:id plugin-map) code)

             (js/console.warn "Unknown target for namespace:" ns "- skipping evaluation."))))

       (rf/dispatch [:plugins/installation-success url plugin-map])

       (catch :default e
         (js/console.error "Plugin Boot Sequence Failed:" e)
         (rf/dispatch [:plugins/installation-failed url (str e)]))))
   {}))


(rf/reg-event-db
 :plugins/installation-failed
 (fn [db [_ source-url error-msg]]
   (assoc db :plugins/pending-security-approval nil
             :plugins/installing? false)))

(rf/reg-event-db
 :plugins/prompt-security-warning
 (fn [db [_ url plugin-map]]
   (assoc db :plugins/pending-security-approval {:url url :plugin-map plugin-map})))

(rf/reg-event-db
 :plugins/cancel-install
 (fn [db _]
   (assoc db :plugins/pending-security-approval nil
             :plugins/installing? false)))


(rf/reg-sub
 :plugins/active-list
 (fn [db _]
   (vals (:plugins/active db {}))))

(rf/reg-sub :plugins/installed-urls (fn [db _] (:plugins/installed-urls db [])))
(rf/reg-sub :plugins/pending-security-approval (fn [db _] (:plugins/pending-security-approval db)))

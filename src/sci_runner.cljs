(ns sci-runner
  (:require
   [sci.core :as sci]
   [cljs.core.async :refer [take! chan put! close!]]
   [cljs.js :as cljs]
   [cljs.analyzer :as ana]
   [cljs.env :as env]
   [shadow.cljs.bootstrap.browser :as boot]
   [cljs.core.async :as async]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [sci.configs.reagent.reagent :as sci-reagent]
   [sci.configs.re-frame.re-frame :as sci-re-frame]
   [clojure.string]
   [cljs-workers.core :as main]
   [sci-shared]
   [promesa.core]

   [container.timeline.item]
   [input.composer]
   [utils.logger]
   [utils.helpers]
   [utils.images]
   [utils.global-ui]
   [utils.svg]
   [utils.macros :refer [expose-ns]]
   [client.state :as state]))




(defonce !current-eval-plugin (atom nil))

(def defoverride-macro
  (sci/new-macro-var
   'defoverride
   (fn [_form _env comp-name args & body]
     (let [plugin-id @!current-eval-plugin]
       (list 'do
             (list* 'defn comp-name args body)
             (list 'swap! 'client.state/!active-overrides 'assoc (keyword (name comp-name))
                   (list 'hash-map :plugin-id plugin-id :fn comp-name)))))))

(def log-info-macro (sci/new-macro-var 'info (fn [form _env & args] (list* 'taoensso.timbre/plugin-info @!current-eval-plugin (:line (meta form)) args))))
(def log-warn-macro (sci/new-macro-var 'warn (fn [form _env & args] (list* 'taoensso.timbre/plugin-warn @!current-eval-plugin (:line (meta form)) args))))
(def log-error-macro (sci/new-macro-var 'error (fn [form _env & args] (list* 'taoensso.timbre/plugin-error @!current-eval-plugin (:line (meta form)) args))))
(def log-debug-macro (sci/new-macro-var 'debug (fn [form _env & args] (list* 'taoensso.timbre/plugin-debug @!current-eval-plugin (:line (meta form)) args))))

(defn safe-reg-slot-item [slot-id item]
  (state/reg-slot-item slot-id (assoc item :plugin-id @!current-eval-plugin)))


(def plugin-context
  (sci/init
   {:namespaces
    (merge
     sci-shared/common-namespaces
     (:namespaces sci-reagent/config)
     (:namespaces sci-re-frame/config)
     {'user {'defoverride defoverride-macro}
      'sci-runner {'!current-eval-plugin !current-eval-plugin}

      'taoensso.timbre {'info  log-info-macro
                        'warn  log-warn-macro
                        'error log-error-macro
                        'debug log-debug-macro
                        'plugin-info  utils.logger/plugin-info
                        'plugin-warn  utils.logger/plugin-warn
                        'plugin-error utils.logger/plugin-error
                        'plugin-debug utils.logger/plugin-debug}
      'utils.global-ui (expose-ns utils.global-ui)
      'container.timeline.item (expose-ns container.timeline.item)
      'utils.macros   (expose-ns utils.macros)
      'utils.helpers  (expose-ns utils.helpers)
      'utils.images   (expose-ns utils.images)
      'utils.svg      (expose-ns utils.svg)
      'input.composer (expose-ns input.composer)
      'client.state  {'!components state/!components
                      'reg-slot-item safe-reg-slot-item
                      '!active-overrides state/!active-overrides
                      'remove-plugin-overrides! state/remove-plugin-overrides!
                      'get-slot state/get-slot
                      '!config state/!config
                      '!engine-pool state/!engine-pool}
      'cljs-workers.core {'do-with-pool! main/do-with-pool!}})

    :classes sci-shared/common-classes}))

(defn evaluate-ui-form [plugin-id form-str]
  (reset! !current-eval-plugin plugin-id)
  (sci/eval-string* plugin-context form-str))


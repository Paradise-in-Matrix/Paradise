(ns sci-runner
  (:require
   [sci.core :as sci]
   [re-frame.core :as rf]
   [reagent.core :as r]
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


(defn safe-reg-slot-item [slot-id item]
  (state/reg-slot-item slot-id (assoc item :plugin-id @!current-eval-plugin)))

(def plugin-context
  (sci/init
   {:namespaces
    {'user {'defoverride defoverride-macro}
     're-frame.core {'dispatch rf/dispatch
                     'subscribe rf/subscribe}
     'reagent.core  {'as-element r/as-element}
     'client.state  {'!components state/!components
                     'reg-slot-item safe-reg-slot-item
                     '!active-overrides state/!active-overrides
                     'remove-plugin-overrides! state/remove-plugin-overrides!
                     'get-slot state/get-slot
                     '!config state/!config}}
    :classes {'js goog/global}}))


(defn evaluate-ui-form [plugin-id form-str]
  (reset! !current-eval-plugin plugin-id)
  (sci/eval-string* plugin-context form-str))




(ns sci-runner
  (:require
   [sci.core :as sci]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [client.state :as state]))




(def defoverride-macro
  (sci/new-macro-var
   'defoverride
   (fn [_form _env comp-name args & body]
     (list 'do
           (list* 'defn comp-name args body)
           (list 'swap! 'client.state/!components 'assoc (keyword comp-name) comp-name)))))

(def plugin-context
  (sci/init
   {:namespaces
    {'user {'defoverride defoverride-macro}
     're-frame.core {'dispatch rf/dispatch
                     'subscribe rf/subscribe}
     'reagent.core  {'as-element r/as-element}
     'client.state  {'!components state/!components
                     'reg-slot-item state/reg-slot-item
                     'get-slot state/get-slot
                     '!config state/!config}}
    :classes {'js goog/global}}))


(defn evaluate-ui-form [form-str]
  (sci/eval-string form-str plugin-context))


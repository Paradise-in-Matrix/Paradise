(ns client.key-handler
  (:require [reagent.core :as r]
   [utils.macros :refer [defui]]
   [re-frame.core :as re-frame]
            ))


(defui global-key-listener []
  (r/with-let [handler (fn [e]
                         (when (and (= (.-key e) "k")
                                    (or (.-metaKey e) (.-ctrlKey e)))
                           (.preventDefault e)
                           (re-frame/dispatch [:ui/open-modal :quick-switcher
                                               {:backdrop-props {:class "lightbox-backdrop"}
                                                :window-props   {:style {:background "transparent"
                                                                      :box-shadow "none"}}}])))]
    (.addEventListener js/window "keydown" handler)
    (finally
      (.removeEventListener js/window "keydown" handler))))
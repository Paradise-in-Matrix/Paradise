(ns paradise.shared.client.registry
  (:require [reagent.core :as r]
            [cljs-workers.mesh]
            ))

(defonce !components (r/atom {}))
(defonce !pure-fns (atom {}))
(defonce !anon-fns (atom {}))
(defonce !active-overrides (r/atom {}))
(defonce !slots (r/atom {}))
(defonce !live-callbacks (atom {}))

(defn stash-lambdas [x]
  (cond
    (fn? x) (let [id (keyword (str "cb-" (random-uuid)))]
              (swap! !live-callbacks assoc id x)
              {:__type :plugin-cb :id id})
    (map? x) (into {} (map (fn [[k v]] [k (stash-lambdas v)]) x))
    (vector? x) (mapv stash-lambdas x)
    (seq? x) (doall (map stash-lambdas x))
    :else x))

(defn hydrate-lambdas [x]
  (cond
    (and (map? x) (= (:__type x) :plugin-cb))
    (get @!live-callbacks (:id x))

    (map? x) (into {} (map (fn [[k v]] [k (hydrate-lambdas v)]) x))
    (vector? x) (mapv hydrate-lambdas x)
    (seq? x) (doall (map hydrate-lambdas x))
    :else x))

(defn dehydrate [x]
  (cond
    (and (fn? x) (some? (.-$fn_ptr x)))
    {:$fn_ptr (.-$fn_ptr x) :$env (dehydrate (.-$env x))}

    (map? x)
    (reduce-kv (fn [m k v] (assoc m k (dehydrate v))) {} x)

    (sequential? x)
    (map dehydrate x)

    :else x))
(ns build
  (:require [clojure.tools.build.api :as b]))

(defn prep [_]
  (b/process {:command-args ["npx" "shadow-cljs" "release" "app"]}))

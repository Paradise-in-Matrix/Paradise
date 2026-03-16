(ns utils.macros
  (:require-macros [utils.macros :refer [load-static-config]]))

(def config (load-static-config "config.edn"))
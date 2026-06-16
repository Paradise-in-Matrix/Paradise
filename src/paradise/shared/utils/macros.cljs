(ns paradise.shared.utils.macros
  (:require-macros [paradise.shared.utils.macros :refer [load-static-config]]))
(def config (load-static-config "config.edn"))
(def i18n-data (load-static-config "i18n.edn"))
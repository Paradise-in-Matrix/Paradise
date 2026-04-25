(ns worker-sci-runner
  (:require [sci.core :as sci]
            [sci-shared]
            [cljs-workers.worker :as worker]
            [worker.state :as state]
            [utils.net :as net]))

(def worker-context
  (sci/init
   {:namespaces
    (merge
     sci-shared/common-namespaces
     {'worker.state {'register-handler state/register-handler
                     '!client state/!client
                     '!media-cache state/!media-cache}
      'utils.net {'fetch net/fetch}
      'cljs-workers.worker {'register worker/register}
      }
     )
    :classes sci-shared/common-classes}))

(defn evaluate-worker-form [form-str]
  (try
    (sci/eval-string* worker-context form-str)
    {:status "success"}
    (catch :default e
      {:status "error" :msg (str e)})))


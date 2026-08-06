(ns paradise.binding.streams
  (:require [paradise.binding.matrix :as matrix]
            [paradise.binding.nostr :as nostr]
            ))

(defn init-engine-streams! [engine-id]
  (case (keyword engine-id)
    :matrix (matrix/init!)
    :nostr  (nostr/init!)
    nil))


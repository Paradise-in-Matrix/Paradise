(ns utils.net
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ))

(defonce !auth-context (atom {:token nil :hs-url nil}))

(defn set-auth-context! [token hs-url]
  (reset! !auth-context {:token token :hs-url hs-url}))

(defn- safe-auth-path? [request-url hs-url]
  (try
    (let [req-obj (js/URL. request-url)
          hs-obj  (js/URL. hs-url)
          path    (.-pathname req-obj)]
      (and (= (.-origin req-obj) (.-origin hs-obj))
           (str/includes? path "/_matrix/client/v1/media/")))
    (catch :default _ false)))

(defn ensure-protocol [hs-url]
  (if (or (str/starts-with? hs-url "http://")
          (str/starts-with? hs-url "https://"))
    hs-url
    (str "https://" hs-url)))

(defn- wait-for-auth []
  (p/create
   (fn [resolve]
     (let [{:keys [token hs-url]} @!auth-context]
       (if (and token hs-url)
         (resolve true)
         (let [watch-key (keyword (gensym "auth-watch"))
               timer     (js/setTimeout #(do
                                           (remove-watch !auth-context watch-key)
                                           (resolve false))
                                        2000)]
           (add-watch !auth-context watch-key
                      (fn [_ _ _ new-state]
                        (when (and (:token new-state) (:hs-url new-state))
                          (js/clearTimeout timer)
                          (remove-watch !auth-context watch-key)
                          (resolve true))))))))))

(defn fetch
  ([url] (fetch url nil))
  ([raw-url options]
   (p/let [opts         (cond
                          (nil? options) #js {}
                          (map? options) (reduce-kv (fn [acc k v]
                                                      (aset acc (name k) v) acc)
                                                    #js {} options)
                          :else (js/Object.assign #js {} options))
           is-media?    (str/includes? raw-url "/_matrix/client/v1/media/")
           needs-wait?  (or (.-auth opts) is-media?)
           _            (when needs-wait?
                          (wait-for-auth))
           {:keys [token hs-url]} @!auth-context
           final-url    (cond
                          (or (str/starts-with? raw-url "http://")
                              (str/starts-with? raw-url "https://"))
                          raw-url
                          (or (str/starts-with? raw-url "/")
                              (str/starts-with? raw-url "./")
                              (str/starts-with? raw-url "../")
                              (not (str/includes? raw-url "/")))
                          raw-url
                          :else
                          (ensure-protocol raw-url))
           is-absolute? (or (str/starts-with? final-url "http://")
                            (str/starts-with? final-url "https://"))
           safe-hs-url  (when hs-url (ensure-protocol hs-url))
           method       (or (.-method opts) "GET")
           headers      (or (.-headers opts) #js {})
           safe-headers (js/Object.assign #js {} headers)
           wants-auth?  (or (.-auth opts)
                            is-media?
                            (and is-absolute?
                                 safe-hs-url
                                 (safe-auth-path? final-url safe-hs-url)))]
     (when (and token wants-auth?)
       (aset safe-headers "Authorization" (str "Bearer " token)))
     (js-delete opts "auth")
     (aset opts "headers" safe-headers)
     (-> (js/fetch final-url opts)
         (p/catch (fn [err]
                    (js/console.error "Fetch failed on URL:" final-url)
                    (throw err)))))))

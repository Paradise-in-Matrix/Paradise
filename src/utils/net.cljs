(ns utils.net
  (:require ["@capacitor/core" :refer [Capacitor CapacitorHttp]]
            [promesa.core :as p]
            [taoensso.timbre :as log]
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

(defn fetch
  ([url] (fetch url nil))
  ([raw-url options]
   (let [{:keys [token hs-url]} @!auth-context
         opts         (cond
                        (nil? options) #js {}
                        (map? options) (reduce-kv (fn [acc k v]
                                                    (aset acc (name k) v) acc)
                                                  #js {} options)
                        :else (js/Object.assign #js {} options))
         method       (or (.-method opts) "GET")
         headers      (or (.-headers opts) #js {})
         safe-headers (js/Object.assign #js {} headers)
         is-absolute? (or (str/starts-with? raw-url "http://")
                          (str/starts-with? raw-url "https://"))
         wants-auth?  (or (.-auth opts)
                          (and is-absolute?
                               hs-url
                               (safe-auth-path? raw-url hs-url)))]
     (when (and token wants-auth?)
       (aset safe-headers "Authorization" (str "Bearer " token)))
     (js-delete opts "auth")
     (aset opts "headers" safe-headers)
     (if (and is-absolute? (.isNativePlatform Capacitor))
       (let [cap-req #js {:url raw-url
                          :method method
                          :headers safe-headers
                          :data (.-body opts)}]
         (-> (.request CapacitorHttp cap-req)
             (p/then (fn [response]
                       (let [status (.-status response)
                             data   (.-data response)]
                         #js {:ok     (and (>= status 200) (< status 300))
                              :status status
                              :json   (fn [] (p/resolved data))
                              :text   (fn [] (p/resolved (if (string? data) data (js/JSON.stringify data))))})))))
       (js/fetch raw-url opts)))))

(defn fetch
  ([url] (fetch url nil))
  ([raw-url options]
   (let [{:keys [token hs-url]} @!auth-context
         
         opts         (cond
                        (nil? options) #js {}
                        (map? options) (reduce-kv (fn [acc k v] 
                                                    (aset acc (name k) v) acc) 
                                                  #js {} options)
                        :else (js/Object.assign #js {} options))
         
         method       (or (.-method opts) "GET")
         headers      (or (.-headers opts) #js {})
         safe-headers (js/Object.assign #js {} headers)
         
         is-absolute? (or (str/starts-with? raw-url "http://")
                          (str/starts-with? raw-url "https://"))
                          
         wants-auth?  (or (.-auth opts)
                          (and is-absolute?
                               hs-url
                               (safe-auth-path? raw-url hs-url)))]
     
     (when (and token wants-auth?)
       (aset safe-headers "Authorization" (str "Bearer " token)))
       
     (js-delete opts "auth")
     (aset opts "headers" safe-headers)
     
     (if (and is-absolute? (.isNativePlatform Capacitor))
       (let [cap-req #js {:url raw-url
                          :method method
                          :headers safe-headers
                          :data (.-body opts)}]
         (-> (.request CapacitorHttp cap-req)
             (p/then (fn [response]
                       (let [status (.-status response)
                             data   (.-data response)]
                         #js {:ok     (and (>= status 200) (< status 300))
                              :status status
                              :json   (fn [] (p/resolved data))
                              :text   (fn [] (p/resolved (if (string? data) data (js/JSON.stringify data))))})))))
       (js/fetch raw-url opts)))))


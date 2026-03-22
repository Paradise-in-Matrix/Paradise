(ns build-hooks
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn copy-wasm
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [source (io/file "node_modules/ffi-bindings/src/generated-compat/wasm-bindgen/index_bg.wasm")
        target (io/file "build/ffi-bindings/wasm-bindgen/index_bg.wasm")]
    (when (.exists source)
      (io/make-parents target)
      (io/copy source target)
      (println "--- WASM copied to public/js ---")))
  build-state)

#_(defn generate-lang-map
{:shadow.build/stage :configure}
  [build-state & args]
  (let [source-dir (io/file "node_modules/@element-hq/web-shared-components/src/i18n/strings")
        target-dir (io/file "build/i18n")
        files (filter #(.endsWith (.getName %) ".json") (file-seq source-dir))]
    (println "Syncing I18n JSONs to resources/public/i18n...")
    (.mkdirs target-dir)
    (let [mapping (into {} (for [f files]
                             (let [name (.getName f)
                                   lang (second (re-find #"^([^.]+)" name))]
                               (io/copy f (io/file target-dir name))
                               [lang (str "/i18n/" name)])))
          content (str "(ns client.i18n-map)\n\n"
                       "(def urls " (pr-str mapping) ")")]
      (io/make-parents "src/client/i18n_map.cljs")
      (spit "src/client/i18n_map.cljs" content))
    build-state))

(defn include-themes
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [source-dir (io/file "themes")
        target-root (io/file "build/themes")
        source-path (.getAbsolutePath source-dir)]
    (when (.exists source-dir)
      (doseq [source-file (file-seq source-dir)
              :when (.isFile source-file)]
        (let [file-path (.getAbsolutePath source-file)
              rel-path (subs file-path (count source-path))
              target-file (io/file target-root (if (.startsWith rel-path "/")
                                                (subs rel-path 1)
                                                rel-path))]
          (io/make-parents target-file)
          (io/copy source-file target-file)))
      (println "--- All themes recursively copied to resources/public/themes ---"))
    build-state))

(defn include-css
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [source-dir (io/file "css")
        target-root (io/file "build/css")
        source-path (.getAbsolutePath source-dir)]
    (when (.exists source-dir)
      (doseq [source-file (file-seq source-dir)
              :when (.isFile source-file)]
        (let [file-path (.getAbsolutePath source-file)
              rel-path (subs file-path (count source-path))
              target-file (io/file target-root (if (.startsWith rel-path "/")
                                                 (subs rel-path 1)
                                                 rel-path))]
          (io/make-parents target-file)
          (io/copy source-file target-file)))
      (println "--- All themes recursively copied to resources/public/themes ---"))
    build-state))

(defn include-root-files
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [resource-dir "build/"
        root-files   ["index.html" "config.edn"]]
    (doseq [f root-files
            :let [source (io/file f)
                  target (io/file resource-dir f)]]
      (when (.exists source)
        (io/make-parents target)
        (io/copy source target))))
  build-state)

(defn copy-element-call
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [source (clojure.java.io/file "node_modules/@element-hq/element-call-embedded/dist")
        target (clojure.java.io/file "build/element-call")]
    (if (.exists source)
      (do
        (when (.exists target)
          (run! #(clojure.java.io/delete-file % true) (reverse (file-seq target))))
        (doseq [f (file-seq source)]
          (let [relative-path (.substring (.getPath f) (count (.getPath source)))
                dest-file (clojure.java.io/file target (if (.startsWith relative-path "/")
                                                         (.substring relative-path 1)
                                                         relative-path))]
            (if (.isDirectory f)
              (.mkdirs dest-file)
              (do
                (clojure.java.io/make-parents dest-file)
                (clojure.java.io/copy f dest-file)))))
        (println "--- Element Call Bridge (Full Dist) copied to build/element-call ---"))
      (println "!!! Warning: Element Call source not found in node_modules !!!"))
    build-state))
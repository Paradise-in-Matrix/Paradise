(ns utils.build
  (:require
   [utils.macros]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.walk :as walk]
   [clojure.string :as str]
   [cljs.tagged-literals :as tags]
   [clojure.core.async]
   [clojure.tools.analyzer.passes.jvm.validate]))

(defn sanitize-ast [form]
  (walk/prewalk
    (fn [x]
      (cond
        (or (seq? x) (vector? x) (map? x) (set? x) (list? x)) x
        (or (symbol? x) (keyword? x) (string? x) (number? x) (boolean? x) (nil? x)) x
        (instance? java.util.regex.Pattern x) x
        (instance? cljs.tagged_literals.JSValue x)
        (list 'cljs.core/clj->js (:val x))
        (instance? java.lang.Class x)
        (symbol (.getName ^java.lang.Class x))
        (instance? clojure.lang.Var x)
        (symbol (str (.name (.ns ^clojure.lang.Var x)) "/" (.sym ^clojure.lang.Var x)))
        :else
        (do
          (println "\n SCRUBBED JVM OBJECT \n")
          (println "Type: " (type x))
          (println "Value:" x)
          (println "----------------------------------\n")
          (symbol (str "ROGUE_OBJECT_" (.getSimpleName (type x)))))))
    form))


(defn scrub-jvm-artifacts [form]
  (walk/prewalk
    (fn [x]
      (cond
        (and (seq? x) (= (first x) 'clojure.lang.Var/getThreadBindingFrame)) nil
        (and (seq? x) (= (first x) 'clojure.lang.Var/resetThreadBindingFrame)) nil
        (and (seq? x) (= (first x) 'java.util.concurrent.atomic.AtomicReferenceArray.)) (list 'clojure.core/make-array (second x))
        (or (= x 'java.lang.Throwable) (= x java.lang.Throwable)) ':default
        (and (seq? x) (symbol? (first x)) (= (name (first x)) "return-chan"))
        (let [state-arr (second x)
              ret-val   (nth x 2)]
          (list 'let ['c (list 'clojure.core/aget state-arr 5)]
                (list 'if-not (list 'nil? ret-val)
                      (list 'cljs.core.async/put! 'c ret-val (list 'fn ['_] (list 'cljs.core.async/close! 'c)))
                      (list 'cljs.core.async/close! 'c))
                'c))

        (and (seq? x) (symbol? (first x)) (= (name (first x)) "aget-object") (str/includes? (namespace (first x)) "ioc-macros"))
        (cons 'clojure.core/aget (rest x))

        (and (seq? x) (symbol? (first x)) (= (name (first x)) "aset-object") (str/includes? (namespace (first x)) "ioc-macros"))
        (cons 'clojure.core/aset (rest x))

        (= x 'clojure.core/identical?) 'cljs.core/keyword-identical?

        (symbol? x)
        (let [ns-str   (namespace x)
              name-str (name x)]
          (cond
            (and ns-str (str/includes? ns-str "ioc-macros") (str/ends-with? name-str "-IDX"))
            (case name-str
              "STATE-IDX" 1
              "EXCEPTION-IDX" 2
              "BINDINGS-IDX" 3
              "RUN-FN-IDX" 4
              "USER-START-IDX" 5
              x)

            ns-str
            (let [cljs-ns  (str/replace ns-str #"^clojure" "cljs")
                  final-ns (if (= cljs-ns "cljs.core.async.impl.ioc-macros")
                             "cljs.core.async.impl.ioc-helpers"
                             cljs-ns)]
              (symbol final-ns name-str))

            :else x))
        :else x))
    form))


(defn custom-macro? [sym]
  (when (symbol? sym)
    (let [macro-var (ns-resolve 'utils.macros (symbol (name sym)))]
      (and macro-var
           (:macro (meta macro-var))
           (= (ns-name (:ns (meta macro-var))) 'utils.macros)))))

(defn expand-macros [form]
  (with-redefs [clojure.tools.analyzer.passes.jvm.validate/validate identity]
    (walk/prewalk
      (fn [x]
        (if (seq? x)
          (let [sym (first x)]
            (cond
              (#{'go 'cljs.core.async/go} sym)
              (let [unrolled-promises (walk/prewalk
                                       (fn [node]
                                         (if (and (seq? node) (= (first node) '<p!))
                                           (list '<! (list 'cljs.core.async.interop/p->c (second node)))
                                           node))
                                       (rest x))
                    qualified         (walk/prewalk
                                       #(case %
                                          <! 'clojure.core.async/<!
                                          put! 'clojure.core.async/put!
                                          timeout 'clojure.core.async/timeout
                                          alts! 'clojure.core.async/alts!
                                          %)
                                       unrolled-promises)
                    expanded          (macroexpand-1 `(clojure.core.async/go ~@qualified))]
                (scrub-jvm-artifacts expanded))

              (custom-macro? sym)
              (let [qualified-macro (symbol "utils.macros" (name sym))]
                (macroexpand-1 (cons qualified-macro (rest x))))
              :else x))
          x))
      form)))


(defn read-cljs [s]
  (binding [*data-readers* tags/*cljs-data-readers*]
    (read-string s)))

(defn process-and-expand-file [file-path]
  (let [raw-code     (slurp file-path)
        wrapped-text (str "(do\n" raw-code "\n)")
        ast-form     (read-cljs wrapped-text)
        expanded-ast (expand-macros ast-form)
        pure-ast     (sanitize-ast expanded-ast)]
    (pr-str pure-ast)))

(defn -main [& args]
  (let [metadata        (edn/read-string (slurp "manifest.edn"))
        ui-form-str     (process-and-expand-file "src/ui.cljs")
        worker-form-str (process-and-expand-file "src/worker.cljs")
        payload         (assoc metadata
                               :ui-form-str ui-form-str
                               :worker-form-str worker-form-str)

        out-dir         (io/file "dist")
        out-file        (io/file out-dir "plugin.edn")]

    (when-not (.exists out-dir)
      (.mkdir out-dir))

    (spit out-file (pr-str payload))
    (println "Plugin packed and AOT-expanded successfully to" (.getPath out-file))))
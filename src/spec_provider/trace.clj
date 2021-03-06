(ns spec-provider.trace
  (:require [clojure.spec :as s]
            [clojure.walk :as walk]
            [spec-provider.stats :as stats]
            [spec-provider.provider :as provider]))

(defn- record-arg-values! [fn-name a args]
  (swap! a update-in [fn-name :args] #(stats/update-stats % args {::stats/positional true})))

(defn- record-return-value! [fn-name a val]
  (swap! a update-in [fn-name :return] #(stats/update-stats % val {}))
  val)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::as (s/cat :as #{:as}
                   :name symbol?))

(s/def ::simple-arg symbol?)
(s/def ::vector-destr (s/and vector?
                             (s/cat :args    (s/* ::arg)
                                    :varargs (s/? (s/cat :amp #{'&}
                                                         :name symbol?))
                                    :as      (s/? ::as))))

(s/def ::map-destr (s/or :keys-destr ::keys-destr
                         :syms-destr ::syms-destr
                         :strs-destr ::strs-destr
                         :explicit-destr ::explicit-destr))

(s/def ::map-as symbol?)

(s/def ::or (s/map-of simple-symbol? any?))

(s/def ::keys (s/coll-of symbol? :kind vector?))
(s/def ::keys-destr (s/keys :req-un [::keys] :opt-un [::map-as ::or]))

(s/def ::syms (s/coll-of symbol? :kind vector?))
(s/def ::syms-destr (s/keys :req-un [::syms] :opt-un [::map-as ::or]))

(s/def ::strs (s/coll-of symbol? :kind vector?))
(s/def ::strs-destr (s/keys :req-un [::strs] :opt-un [::map-as ::or]))

(s/def ::explicit-destr (s/and
                         (s/keys :opt-un [::map-as ::or] :conform-keys true)
                         (s/map-of (s/or :name symbol?
                                         :nested-destr ::arg)
                                   (s/or :destr-key any?) ;;or with single case just to get tagged value
                                   :conform-keys true)))

(s/def ::arg (s/and (complement #{'&})
                    (s/or :name ::simple-arg
                          :map-destr ::map-destr
                          :vector-destr ::vector-destr)))

(s/def ::args (s/coll-of ::arg :kind vector?))

(comment ;; to test
  (pprint (s/conform
           ::args
           '[a b [[deep1 deep2] v1 v2 & rest :as foo]
             c d {:keys [foo bar] :as foo2 :or {foo 1 bar 2}}
             {a "foo" [x y] :point c :cc}
             [& rest]])))

(comment ;; to test
  (pprint (s/conform
           :clojure.core.specs/arg-list
           '[a b [[deep1 deep2] v1 v2 & rest :as foo]
             c d {:keys [foo bar] :as foo2 :or {foo 1 bar 2}}
             {a "foo" [x y] :point c :cc}
             [& rest]])))

(comment
  (->> (s/registry) keys (sort-by str) pprint)

  (pprint (s/form (s/get-spec :clojure.core/let)))

  (pprint (s/form (s/get-spec :clojure.core.specs/arg-list)))
  (pprint (s/form (s/get-spec :clojure.core.specs/binding-form)))
  (pprint (s/form (s/get-spec :clojure.core.specs/map-binding-form)))
  (pprint (s/form (s/get-spec :clojure.core.specs/map-bindings)))
  (pprint (s/form (s/get-spec :clojure.core.specs/map-special-binding)))
  (pprint (s/form (s/get-spec :clojure.core.specs/or)))
  (pprint (s/form (s/get-spec :clojure.core.specs/map-binding))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- spec-provider-generated? [x]
  (-> x meta :spec-provider-generated true?))

(defn- extract-arg-names [args]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (:as x) (spec-provider-generated? (:as x)))
       (dissoc x :as) ;;remove :as when generated by spec-provider
       x))
   (s/conform ::args args)))

(defn record-args! [fn-name a args]
  (if (get (set args) '&)
    (swap! a assoc-in [fn-name :arg-names :var-args] args)
    (swap! a assoc-in [fn-name :arg-names (count args)] args)))

(defn- add-as [x]
  (cond (not (map? x)) x
        (:as x) x
        :else (assoc x :as (with-meta (gensym "spec-provider-name-")
                             {:spec-provider-generated true}))))

(defn- handle-map-destruct [x]
  (if-not (map? x) x (:as x)))

(defn- instrument-body [fn-name atom-sym body]
  (let [args (mapv add-as (first body))]
    `(~args
      ~(let [[normal-args [_ var-arg]] (split-with #(not= '& %) args)]
         (if var-arg
           `(record-arg-values! ~fn-name ~atom-sym
                                (conj
                                 ~(mapv handle-map-destruct normal-args)
                                 ~(handle-map-destruct var-arg)))
           `(record-arg-values! ~fn-name ~atom-sym ~(mapv handle-map-destruct args))))
      ~@(drop 1 (butlast body))
      (record-return-value! ~fn-name ~atom-sym ~(last body)))))

(defmacro instrument [trace-atom defn-code]
  (assert (= 'defn (first defn-code)))
  (let [fn-name  (second defn-code)
        start    (if (string? (nth defn-code 2))
                   (take 3 defn-code)
                   (take 2 defn-code))
        doc      (if (string? (nth defn-code 2)) (nth defn-code 2) "")
        rest     (if (string? (nth defn-code 2))
                   (drop 3 defn-code)
                   (drop 2 defn-code))
        bodies   (if (vector? (first rest))
                   [rest]
                   (vec rest))
        atom-sym (gensym "spec-provider-atom-")]
    `(let [~atom-sym ~trace-atom]
       (do
         ~@(for [body bodies]
             `(record-args! ~(str *ns* "/" fn-name) ~atom-sym (quote ~(first body)))))
       (defn ~fn-name ~doc
         ~@(map #(instrument-body (str *ns* "/" fn-name) atom-sym %) bodies)))))

(defn- set-cat-names [cat-spec names]
  (let [parts (partition 2 (drop 1 (second cat-spec)))]
    (list
     `s/spec ;;TODO assumes that all s/cats are wrapped in an s/spec
     (concat (list `s/cat)
             (mapcat (fn [name [_ spec]] [name spec]) names parts)))))

(defn spec-form [s]
  (nth s 2))

(defn update-args [[_ _ _ args :as spec] fun]
  (into () (-> (into [] spec)
               (update 3 fun))))

(defn fn-spec [trace-atom fn-name]
  (let [stats        (get @trace-atom (str fn-name))
        arg-names    (map keyword (:arg-names stats))
        arg-specs    (provider/summarize-stats (:args stats) fn-name)
        return-specs (provider/summarize-stats (:return stats) fn-name)
        pre-specs    (concat
                      (butlast arg-specs)
                      (butlast return-specs))]
    (concat
     pre-specs
     [(list `s/fdef (symbol fn-name)
            :args (-> arg-specs last spec-form)
            :ret  (-> return-specs last spec-form))])))

(defn pprint-fn-spec [trace-atom fn-name domain-ns clojure-spec-ns]
  (provider/pprint-specs (fn-spec trace-atom fn-name) domain-ns clojure-spec-ns))

(defn clear-registry! [reg]
  (reset! reg {}))

(defonce reg (atom {}))

(comment
  (instrument
   reg
   (defn foo "doc"
     ([a b c d e f g h i j & rest]
      (swap! (atom []) conj 1)
      (swap! (atom []) conj 2)
      ;;{:result (* d (+ a b c))}
      6)
     ([a b [[v1 v2] v3] c d {:keys [foo bar]} {:keys [baz] :as bazz}]
      (swap! (atom []) conj 1)
      (swap! (atom []) conj 2)
      ;;{:result (* d (+ a b c))}
      6)))
  )

(comment
  (foo 1 2 [[3 4] 5] 6 7 {:foo 8 :bar 9} {})
  (pprint-fn-spec reg 'spec-provider.trace/foo 'spec-provider.trace 's)
  (-> reg deref (get "spec-provider.trace/foo") :arg-names)
  )

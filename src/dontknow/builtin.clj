(ns dontknow.builtin
  (:require [clojure.string]
            [dontknow.trie :refer :all]))

; Builtins used in trace-choices.vnts and other extant metaprob sources:
;   trace_has_key
;   trace_get
;   trace_has
;   trace_set
;   trace_set_subtrace_at
;   lookup
;   mk_nil
;
;   pprint  [clojure conflict - takes a trace]
;   error
;   first rest   [clojure conflict]
;   last    [clojure conflict]
;   range   [clojure conflict]
;   length
;   map   [clojure conflict]
;   list_to_array
;   add (from metaprob '+')
;   add (from =)
;   eq
;   neq

;   name_for_definiens
;   make_env
;   match_bind      - extends an environment (implemented as trace).
;   env_lookup
;   capture_tag_address   - for 'this'
;   resolve_tag_address   - for with_address

;   interpret    -- what is this?
;   interpret_prim

(declare from-clojure)

(defmacro define
  "like def, but allows patterns"
  [pattern rhs]

  (letfn [(var-for-pattern [pat]
            (if (symbol? pat)
              pat
              (symbol (clojure.string/join "|" (map var-for-pattern pat)))))

          ;; Returns a list [[var val] ...]
          ;; to be turned into, say, (block (define var val) ...)
          ;; or into (let [var val ...] ...)

          (explode-pattern [pattern rhs]
            (if (symbol? pattern)
              (list (list pattern rhs))
              (let [var (var-for-pattern pattern)]
                (cons (list var rhs)
                      (mapcat (fn [subpattern i]
                                (if (= subpattern '_)
                                  (list)
                                  (explode-pattern subpattern `(nth ~var ~i))))
                              pattern
                              (range (count pattern)))))))]

    `(do ~@(map (fn [[var val]] `(def ~var ~val))
                (explode-pattern pattern rhs)))))

(defn make-program [fun params body ns]
  (let [exp (from-clojure `(program ~params ~@body))
        env ns]
    (with-meta fun {:trace (trie-from-map {"name" (new-trie exp)
                                           "source" exp
                                           "environment" (new-trie env)}
                                          "prob prog")})))

(defmacro program
  "like fn, but for metaprob programs"
  [params & body]
  `(make-program (fn ~params (block ~@body))
                 '~params
                 '~body
                 ;; *ns* will be ok at top level as a file is loaded,
                 ;; but will be nonsense at other times.  Fix somehow.
                 ;; (should be lexical, not dynamic.)
                 *ns*))

; Similar to sp_to_prob_prog
;  return make_prob_prog(
;    name=name,
;    simulator=sp.simulate,
;    interpreter=python_interpreter_prob_prog(sp.py_propose),
;    tracer=python_tracer_prob_prog(sp.py_propose),
;    proposer=python_proposer_prob_prog(sp.py_propose),
;    ptc=python_ptc_prob_prog(sp.py_propose))

(defn make-primitive [name fun]
  (letfn [(simulate [args]          ;sp.simulate
            (apply fun args))
          (py-propose [args _intervention _target _output] ;sp.py_propose
            [(apply fun args) 0])]
    (with-meta fun {:trace (trie-from-map {"name" (new-trie name)
                                           "executable" (new-trie simulate)
                                           "custom_interpreter" (new-trie py-propose)
                                           "custom_choice_trace" (new-trie py-propose)
                                           "custom_proposer" (new-trie py-propose)
                                           "custom_choice_tracing_proposer" (new-trie py-propose)}
                                          "prob prog")})))

(defmacro define-primitive [name params & body]
  `(def ~name
     (make-primitive '~name
                     (fn ~params ~@body))))

(defmacro block
  "like do, but for metaprob - supports local definitions"
  [& forms]
  (letfn [(definition? [form]
            (and (list? form)
                 (= (first form) 'def)))
          (definition-pattern [form]
            (second form))
          (definition-rhs [form]
            (nth form 2))
          (program-definition? [form]
            (and (definition? form)
                 (symbol? (definition-pattern form))
                 (let [rhs (definition-rhs form)]
                   (and (list? rhs)
                        (= (first rhs) 'program)))))
          (qons [x y]
            (if (list? y)
              (conj y x)
              (conj (concat (list) y) x)))
          (process-definition [form]
            (assert program-definition? form)
            (let [rhs (definition-rhs form)       ;a program-expression
                  prog-pattern (definition-pattern rhs)
                  prog-body (rest (rest rhs))]
              (qons (definition-pattern form)
                    (qons prog-pattern
                          prog-body))))

          (block-to-body [forms]
            (if (empty? forms)
              '()
              (let [more (block-to-body (rest forms))]    ; list of forms
                (if (definition? (first forms))
                  (let [pattern (definition-pattern (first forms))
                        rhs (definition-rhs (first forms))]
                    ;; A definition must not be last expression in a block
                    (if (empty? (rest forms))
                      (print (format "** Warning: Definition of %s occurs at end of block\n"
                                     pattern)))
                    (if (program-definition? (first forms))
                      (let [spec (process-definition (first forms))
                            more1 (first more)]
                        (if (and (list? more1)
                                 (= (first more1) 'letfn))
                          (do (assert (empty? (rest more)))
                              ;; more1 = (letfn [...] ...)
                              ;;    (letfn [...] & body)
                              ;; => (letfn [(name pattern & prog-body) ...] & body)
                              (let [[_ specs & body] more1]
                                (list             ;Single form
                                 (qons 'letfn
                                       (qons (vec (cons spec specs))
                                             body)))))
                          ;; more1 = something else
                          (list                   ;Single form
                           (qons 'letfn
                                 (qons [spec]
                                       more)))))
                      ;; Definition, but not of a function
                      ;; (first forms) has the form (def pattern rhs)
                      (let [more1 (first more)]
                        (if (and (list? more1)
                                 (= (first more1) 'let))
                          ;; Combine two lets into one
                          (do (assert (empty? (rest more)))
                              (let [[_ specs & body] more1]
                                (list             ;Single form
                                 (qons 'let
                                       (qons (vec (cons pattern (cons rhs specs)))
                                             body)))))
                          (list                   ;Single form
                           (qons 'let
                                 (qons [pattern rhs]
                                       more)))))))
                  ;; Not a definition
                  (qons (first forms) more)))))

          (formlist-to-form [forms]
            (assert (seq? forms))
            (if (empty? forms)
              'nil
              (if (empty? (rest forms))
                (first forms)
                (if (list? forms)
                  (qons 'do forms)
                  (qons 'do (concat (list) forms))))))]
    (formlist-to-form (block-to-body forms))))

; If an object has a :trace meta-property, then return that
; meta-property value.  Otherwise, just return the object.

(defn tracify [x]
  (if (trie? x)
    x
    (let [m (meta x)]
      (if (map? m)
        (if (contains? m :trace)
          (get m :trace)
          x)
        x))))

(define-primitive eq [x y] (= x y))
(define-primitive neq [x y] (not (= x y)))
(define-primitive add [x y]
  (if (and (number? x) (number? y))
    (+ x y)
    (if (and (seq? x) (seq? y))
      (concat x y)
      (assert false "bad place to be"))))

(define-primitive gt [x y] (> x y))
(define-primitive gte [x y] (>= x y))
(define-primitive lt [x y] (< x y))
(define-primitive lte [x y] (<= x y))

; Used in prelude.vnts:
;   is_metaprob_array
;     The null array has no value and no subtraces; same as metaprob nil.
;     Hmm.

(define-primitive length [x]
  (if (trie? x)
    (trie-count x)
    (count x)))

(define-primitive first-noncolliding [mp-list]
  (if (trace? mp-list)
    (value mp-list)
    (first mp-list)))

(define-primitive rest-noncolliding [mp-list]
  (if (trace? mp-list)
    (subtrie mp-list "rest")
    (rest mp-list)))

(define-primitive last-noncolliding [mp-list]
  (if (trace? mp-list)
    (if (has-subtrie? mp-list "rest")
      (if (not (has-subtrie? (subtrie mp-list "rest") "rest"))
         mp-list
         (last-noncolliding (subtrie mp-list "rest")))
      mp-list)
    (last mp-list)))

(defn empty?-noncolliding [mp-list]
  (not (has-subtrie? mp-list "rest")))

(define-primitive mk_nil [] (new-trie))                 ; {{ }}

(define-primitive list_to_array [mp-list]
  (let [arr (mk_nil)]
    (letfn [(r [mp-list n]
              (if (empty?-noncolliding mp-list)
                arr
                (do (set-value-at! arr n (first-noncolliding mp-list))
                    (r (rest-noncolliding mp-list)
                       (+ n 1)))))]
      (r mp-list 0))))

(define-primitive map-noncolliding [mp-fn mp-seq]
  ;; Do something - need to thread the trace through
  0)

(define-primitive pair [thing mp*list]
  (trie-from-map {:rest mp*list} thing))

; Copied from prelude.clj
(define-primitive _range [n k]
  (if (gte k n) (mk_nil) (pair k (_range n (add k 1)))))

(define-primitive range-noncolliding [n]
  (_range n 0))

(define-primitive trace_get [tr] (value (tracify tr)))        ; *e
(define-primitive trace_has [tr] (has-value? (tracify tr)))
(define-primitive trace_set [tr val]            ; e[e] := e
  (set-value! (tracify tr) val))
(define-primitive trace_set_at [tr addr val] (set-value-at! (tracify tr) addr val))
(define-primitive trace_set_subtrace_at [tr addr sub] (set-subtrie-at! (tracify tr) addr sub))
(define-primitive trace_has_key [tr key] (has-subtrie? (tracify tr) key))
(define-primitive trace_subkeys [tr] (trie-keys (tracify tr)))
(define-primitive lookup [tr addr]
  (subtrace-at (tracify tr) addr))  ; e[e]

(define-primitive make_env [parent]
  (cons (ref {}) parent))

(define-primitive match_bind [pattern inputs env]
  (dosync
   (letfn [(mb [pattern inputs]
             (if (not (seq? pattern))
               (ref-set (first env) pattern inputs)
               (if (not (empty? pattern))
                 (do (mb (first pattern) (first inputs))
                     (mb (rest pattern) (rest inputs))))))]
     (mb pattern inputs))
   env))

(define-primitive env_lookup [env name]
  (assert (not (empty? env)))
  (or (get (deref (first env)) name)
      (env_lookup (rest env) name)))

;; Called 'apply' in lisp
;; Same as in metacirc-stub.vnts

(define-primitive interpret [proposer inputs intervention-trace]
  ;; proposer is (fn [args _intervention _target _output] ...)
  (if (has-value? intervention-trace)
    (value intervention-trace)
    (proposer (subtrie-values-to-seq inputs)
              intervention-trace
              (new-trie)
              (new-trie))))
  

;; def interpret_prim(f, args, intervention_trace):
;;   if intervention_trace.has():
;;     return intervention_trace.get()
;;   else:
;;     return f(metaprob_collection_to_python_list(args))

;; This one isn't used.

(define-primitive interpret_prim [proposer inputs intervention-trace]
  0)

(define-primitive pprint-noncolliding [x]
  ;; x is a trie.  need to prettyprint it somehow.
  0)

(define-primitive error [x]
  (assert (string? x))
  (Exception. x))

; Other builtins

(define-primitive flip [weight] (<= (rand) weight))

(define-primitive uniform [a b] (+ (rand (- b a)) a))

(define-primitive capture_tag_address [& stuff]
  stuff)

(define-primitive resolve_tag_address [stuff]
  stuff)

; Metaprob arrays (node with children 0 ... size-1)

(defn mp*array-from-seq [sequ]
  (trie-from-map (zipmap (range (count sequ))
                         (for [member sequ] (new-trie member)))))

; Size of a metaprob array

(defn mp*size [trie]
  (letfn [(size-from [trie i]
            (if (has-subtrie? trie i)
              (+ 1 (size-from trie (+ i 1)))
              0))]
    (size-from trie 0)))

(defn seq-from-mp*array [mp*array]
  (for [i (range (mp*size mp*array))]
    (value-at mp*array [i])))

; Convert metaprob array to metaprob list ?
(defn array-to-list [a] 0)

; Translated from prelude.vnts.

; Constructors for metaprob-list type

(def mp*nil (new-trie 'nil))

; Predicates for metaprob-list type

(defn mp*null? [thing]
  ;; (= mp*list mp*nil)  ??
  (and (trie? thing)
       (has-value? thing)
       (= (value thing) 'nil)
       (not (has-subtrie? thing :rest))))

(defn mp*pair? [thing]
  (and (trie? thing)
       (has-subtrie? thing :rest)))

(defn mp*list? [thing]
  (and (trie? thing)
       (or (has-subtrie? thing :rest)
           (= (value thing) 'nil))))

; Selectors

(defn mp*first [mp*list]
  (value mp*list))

(defn mp*rest [mp*list]
  (subtrie mp*list :rest))

; Higher level operators

(defn mp*list-to-clojure-seq [s]
  (if (mp*null? s)
    []
    (let [[hd tl] s]
      (conj (mp*list-to-clojure-seq tl) hd))))

(defn mp*seq-to-clojure-seq [s]
  (if (trie? s)
    (if (mp*list? s)
      (mp*list-to-clojure-seq s)
      (for [i (range (mp*size s))]
        (value-at s [i])))
    s))

(declare mp*map)

(defn mp*apply [mp*fn mp*seq]
  (apply mp*fn (mp*seq-to-clojure-seq mp*seq)))

(defn mp*list-map [mp*fn mp*list]
  (if (mp*null? mp*list)
    []
    (pair (mp*apply mp*fn (mp*first mp*list))
          (mp*map mp*fn (mp*rest mp*list)))))

(defn mp*array-map [mp*fn mp*array]
  (let [n (mp*size mp*array)
        r (range n)]
    (trie-from-map (zipmap r
                           (for [i r] (mp*apply mp*fn (value-at mp*array [i])))))))

(defn mp*map [mp*fn thing]
  (if (trie? thing)
    (if (mp*list? thing)
      (mp*list-map mp*fn thing)
      (mp*array-map mp*fn thing))
    (for [x thing] (mp*apply mp*fn x))))



(define-primitive name-for-definiens [pattern]
  (if (symbol? pattern)
    (if (= pattern '_)
      'definiens
      pattern)
    'definiens))

; -----------------------------------------------------------------------------

; Convert a clojure expression to a metaprob parse tree / trie.
; Assumes that the input is in the image of the to_clojure converter.

(defn from-clojure-seq [seq val]
  (trie-from-seq (map from-clojure seq) val))

(defn from-clojure-program [exp]
  (let [[_ pattern & body] exp]
    (let [body-exp (if (= (count body) 1)
                     (first body)
                     (cons 'block body))]
      (trie-from-map {"pattern" (from-clojure pattern)
                      "body" (from-clojure body-exp)}
                     "program"))))

(defn from-clojure-if [exp]
  (let [[_ pred thn els] exp]
    (trie-from-map {"predicate" (from-clojure pred)
                    "then" (from-clojure thn)
                    "else" (from-clojure els)}
                   "if")))

(defn from-clojure-block [exp]
  (from-clojure-seq (rest exp) "block"))

(defn from-clojure-with-address [exp]
  (let [[_ tag ex] exp]
    (trie-from-map {"tag" (from-clojure tag)
                    "expression" (from-clojure ex)}
                   "with_address")))

; This doesn't handle _ properly.  Fix later.

(defn from-clojure-definition [exp]
  (let [[_ pattern rhs] exp
        key (if (symbol? pattern) (str pattern) "definiens")]
    (trie-from-map {"pattern" (from-clojure pattern)
                    key (from-clojure rhs)}
                   "definition")))

(defn from-clojure-application [exp]
  (from-clojure-seq exp "application"))

(defn from-clojure-tuple [exp]
  (from-clojure-seq exp "tuple"))

(defn from-clojure-1 [exp]
  (cond (vector? exp) (from-clojure-tuple exp)
        ;; I don't know why this is sometimes a non-list seq.
        (seq? exp) (case (first exp)
                     program (from-clojure-program exp)
                     if (from-clojure-if exp)
                     block (from-clojure-block exp)
                     splice (trie-from-map {"expression" (from-clojure exp)} "splice")
                     unquote (trie-from-map {"expression" (from-clojure exp)} "unquote")
                     with-address (from-clojure-with-address exp)
                     define (from-clojure-definition exp)
                     ;; else
                     (from-clojure-application exp))
        (= exp 'this) (trie-from-map {} "this")
        (symbol? exp) (trie-from-map {"name" (new-trie (str exp))} "variable")
        ;; Literal
        true (do (assert (or (number? exp)
                             (string? exp)
                             (boolean? exp))
                         ["bogus expression" exp])
                 (trie-from-map {"value" (new-trie exp)} "literal"))))
        

(defn from-clojure [exp]
  (let [answer (from-clojure-1 exp)]
    (assert (trie? answer) ["bad answer" answer])
    answer))

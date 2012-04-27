(ns pallet.algo.fsmop
  "Operations

Operate provides orchestration. It assumes that operations can succeed, fail,
time-out or be aborted. It assumes some things happen in parallel, and that some
things need to be sequenced.


## Operation primitive FSM contract

An operation primitive must produce a FSM specification.

The FSM must respond to the :start event in it's initial state. The event data
sent with the :start event will be the current global state. The default initial
state is :init. The default response for the :start event in the :init state is
to set the primitive's :state-data to the global state, and to transition to
the :running state.

The FSM must have a :completed and a :failed state.

It must respond to the :abort event, which is sent on a user initiated abort
of an operation. The :abort event should cause the FSM to end in the :aborted
state.

The :init, :completed, :aborted and :failed states will be implicitly added if
not declared.

The result should be placed on the :result key of the state-data.

A failure reason should be placed on the :fail-reason key of the state-data.

State event functions (on-enter and on-exit) should return true if they do
anything to change the state of the FSM, and further event functions should not
be called for the transition.


## The `operation` FSM comprehension

An expression under the `operation` FSM comprehension results in a compound
FSM. It is returned as a function, that takes a state, and returns a map of
functions to control the resulting FSM.

"
  (:require
   [clojure.tools.logging :as logging])
  (:use
   [clojure.pprint :only [pprint]]
   [clojure.set :only [union]]
   [pallet.computation.event-machine :only [event-machine]]
   [pallet.computation.fsm-dsl
    :only [event-handler event-machine-config fsm-name initial-state
           initial-state-data on-enter on-exit
           state valid-transitions configured-states using-fsm-features]]
   [pallet.computation.fsm-utils :only [swap!!]]
   [pallet.map-merge :only [merge-keys merge-key]]
   [pallet.thread.executor :only [executor]]
   [slingshot.slingshot :only [throw+]]))

;;; ## thread pools
(defonce operate-executor (executor {:prefix "operate"
                                     :thread-group-name "pallet-operate"}))

(defn execute
  "Execute a function in the operate-executor thread pool."
  [f]
  (pallet.thread.executor/execute operate-executor f))

(defonce scheduled-executor (executor {:prefix "op-sched"
                                       :thread-group-name "pallet-operate"
                                       :scheduled true
                                       :pool-size 3}))

(defn execute-after
  "Execute a function after a specified delay in the scheduled-executor thread
  pool. Returns a ScheduledFuture."
  [f delay delay-units]
  (pallet.thread.executor/execute-after scheduled-executor f delay delay-units))

;;; ## FSM helpers

(defn update-state
  "Convenience update function."
  [state state-kw f & args]
  (-> (apply update-in state [:state-data] f args)
      (assoc :state-kw state-kw)))

(defn do-nothing-event-handler [state _ _] state)

(defn default-init-event-handler
  "Default event handler for the :init state"
  [state event event-data]
  (case event
    :start (assoc state :state-kw :running :state-data event-data)
    :abort (assoc state :state-kw :aborted :state-data event-data)))

;;; ### state keys
(def op-env-key ::env)
(def op-steps-key ::steps)
(def op-todo-steps-key ::todo-steps)
(def op-promise-key ::promise)
(def op-fsm-machines-key ::machines)
(def op-compute-service-key ::compute)
(def op-result-fn-key ::result-fn)
(def op-overall-result-key ::overall-result-f)
(def op-timeouts-key ::timeouts)

;;; ## Operation Step Processing
(defn- step-fsm
  "Generate a fsm for an operation step."
  [environment {:keys [f] :as step}]
  (assoc step :fsm (f environment)))

(def ^{:doc "Base FSM for primitive FSM."
       :private true}
  default-primitive-fsm
  (event-machine-config
    (initial-state :init)
    (state :init
      (valid-transitions :aborted :running)
      (event-handler default-init-event-handler))
    (state :completed
      (event-handler do-nothing-event-handler))
    (state :aborted
      (event-handler do-nothing-event-handler))
    (state :failed
      (event-handler do-nothing-event-handler))))

(defmethod merge-key ::merge-guarded-chain
  [_ _ val-in-result val-in-latter]
  (fn [state] (when-not (val-in-result state)
                (val-in-latter state))))

(defn merge-fsms
  "Merge operation primitve FSM's."
  [& fsm-configs]
  (apply
   merge-keys
   {:on-enter ::merge-guarded-chain
    :on-exit ::merge-guarded-chain
    :fsm/fsm-features :union}
   fsm-configs))

;;; ## Fundamental primitives
(defn fail
  "An operation primitive that does nothing but fail immediately."
  ([reason]
     (letfn [(init [state event event-data]
               (case event
                 :start (assoc state
                          :state-kw :failed
                          :state-data (assoc event-data :fail-reason reason))))]
       (event-machine-config
         (fsm-name "fail")
         (state :init
           (valid-transitions :failed)
           (event-handler init)))))
  ([] (fail nil)))

;; Not sure if we need this - or just let result take no arguments
(defn succeed
  "An operation primitive that does nothing but succeed immediately."
  []
  (letfn [(init [state event event-data]
            (case event
              :start (assoc state :state-kw :completed
                            :state-data event-data)))]
    (event-machine-config
      (fsm-name "succeed")
      (state :init
        (valid-transitions :completed)
        (event-handler init)))))

(defn result
  "An operation primitive that does nothing but succeed immediately with the
   specified result `value`."
  [value]
  (letfn [(init [state event event-data]
            (logging/debugf "result - init: %s" event)
            (case event
              :start (assoc state
                       :state-kw :completed
                       :state-data (assoc event-data :result value))))]
    (event-machine-config
      (fsm-name "result")
      (state :init
        (valid-transitions :completed)
        (event-handler init)))))

(defn delay-for
  "An operation primitive that does nothing for the given `delay`. This uses the
  stateful-fsm's timeout mechanism. Not the timeout primitive. The primitive
  transitions to :completed after the given delay."
  [delay delay-units]
  (letfn [(init [state event event-data]
            (logging/debugf "delay-for init: event %s" event)
            (case event
              :start (assoc state
                       :state-kw :running
                       :timeout {delay-units delay}
                       :state-data event-data)))
          (timed-out [{:keys [em] :as state}]
            (logging/debug "delay-for timed out, completed.")
            ((:transition em) #(assoc % :state-kw :completed)))]
    (event-machine-config
      (fsm-name "delay-for")
      (state :init
        (valid-transitions :running)
        (event-handler init))
      (state :running
        (valid-transitions :completed :failed :timed-out :aborted)
        (event-handler do-nothing-event-handler))
      (state :timed-out
        (valid-transitions :completed :aborted)
        (on-enter timed-out)))))


;;; ## Higher order primitives

(defn timeout
  "Execute an expression with a timeout. The timeout is applied to each
  state. Any transition out of a state will cancel the timeout."
  [fsm-config delay delay-units]
  (letfn [(add-timeout [timeout-name]
            (fn add-timeout [{:keys [em state-data] :as state}]
              (let [f (execute-after
                       #((:transition em)
                         (fn [state]
                           (update-state
                            state :failed
                            assoc :fail-reason {:reason :timed-out})))
                       delay
                       delay-units)]
                (swap!
                 (op-timeouts-key state-data)
                 assoc timeout-name f))))
          (remove-timeout [timeout-name]
            (fn remove-timeout [{:keys [state-data] :as state}]
              ;; timeouts aren't necessarily in the :init state-data
              (when-let [timeouts (op-timeouts-key state-data)]
                (let [[to-map _] (swap!!
                                  timeouts
                                  dissoc timeout-name)]
                  (try
                    (future-cancel (timeout-name to-map))
                    (catch Exception e
                      (logging/warnf
                       e "Problem canceling timeout %s" timeout-name)))))))
          (add-timeout-transitions [state-kw]
            (let [timeout-name (gensym (str "to-" (name state-kw)))]
              (event-machine-config
                (state state-kw
                  (on-enter (add-timeout timeout-name))
                  (on-exit (remove-timeout timeout-name))))))]
    (->>
     (configured-states fsm-config)
     (remove #{:completed :failed :timed-out})
     (map add-timeout-transitions)
     (reduce merge-fsms fsm-config))))

(defn map*
  "Execute a set of fsms"
  [fsm-configs]
  (letfn [(patch-fsm [event]
            (letfn [(op-completed [state]
                      (logging/debug "map* op-completed")
                      (event :op-complete state))
                    (op-failed [state]
                      (logging/debug "map* op-failed")
                      (event :op-fail state))]
              (event-machine-config
                (state :completed
                  (on-enter op-completed))
                (state :failed
                  (on-enter op-failed)))))
          (wire-fsms [{:keys [em] :as state}]
            (let [{:keys [event]} em
                  patch-fsm (patch-fsm event)]
              (for [fsm-config fsm-configs]
                (merge-fsms
                 default-primitive-fsm
                 fsm-config
                 patch-fsm))))
          (init [state event event-data]
            (logging/debugf "map* init: event %s" event)
            (logging/debugf "init has em %s" (:em event-data))
            (case event
              :start
              (let [configs (wire-fsms state)
                    fsms (map event-machine configs)]
                (if (seq fsms)
                  (update-state
                   state :running
                   merge event-data {::fsms fsms ::pending-fsms (set fsms)})
                  (assoc state :state-kw :completed :state-data event-data)))))
          (on-running [{:keys [state-data] :as state}]
            (logging/debug "map* on running")
            (let [fsms (::fsms state-data)]
              (logging/debugf "map* on-running starting %s fsms" (count fsms))
              (doseq [{:keys [event] :as fsm} fsms]
                (execute #(event :start state)))))
          (maybe-finish [{:keys [state-data] :as state}]
            (logging/debugf
             "maybe-finish pending count %s"
             (count (::pending-fsms state-data)))
            (if (seq (::pending-fsms state-data))
              state
              (assoc state :state-kw :ops-complete)))
          (running [{:keys [state-data] :as state} event event-data]
            (logging/debugf
             "running pending count %s"
             (count (::pending-fsms state-data)))
            (logging/debugf "running has em %s" (:em state))
            (case event
              :op-complete
              (let [{:keys [em]} event-data
                    state-data (-> state-data
                                   (update-in [::pending-fsms] disj em)
                                   (update-in [::completed-states]
                                              conj event-data))]
                (logging/debugf
                 "op-complete result: %s"
                 (-> event-data :state-data :result))
                (maybe-finish (assoc state :state-data state-data)))
              :op-fail
              (let [{:keys [em]} event-data
                    state-data (-> state-data
                                   (update-in [::pending-fsms] disj em)
                                   (update-in [::failed-states]
                                              conj event-data))]
                (maybe-finish (assoc state :state-data state-data)))))
          (ops-complete [{:keys [state-data] :as state} event event-data]
            (logging/debugf "ops-complete has em %s" (:em state))
            ;; (logging/debugf
            ;;  "ops-complete - result: %s"
            ;;  (-> state :state-data op-result-sym-key))
            (case event
              :abort (update-state state :aborted assoc :fail-reason event-data)
              :fail (update-state
                     state :failed
                     assoc :fail-reason
                     {:reason :failed-ops
                      :failed-reasons (map (comp :fail-reason :state-data)
                                           (::failed-states state-data))
                      :failed-states (::failed-states state-data)
                      :completed-states (::completed-states state-data)})
              :complete (do
                          ;; (logging/debugf
                          ;;  "complete result: %s %s"
                          ;;  (op-result-sym-key state-data)
                          ;;  (vec (map (comp :result :state-data)
                          ;;            (::completed-states state-data))))
                          (update-state
                           state :completed
                           assoc :result
                           (map (comp :result :state-data)
                                (::completed-states state-data))))))
          (on-ops-complete [{:keys [em state-data] :as state}]
            ;; (logging/debugf
            ;;  "on-ops-complete - result: %s"
            ;;  (-> state :state-data op-result-sym-key))
            (let [{:keys [event]} em]
              (if (seq (::failed-states state-data))
                (event :fail nil)
                (event :complete nil))))]
    (event-machine-config
      (fsm-name "map*")
      (state :init
        (valid-transitions :running :completed)
        (event-handler init))
      (state :running
        (valid-transitions :running :ops-complete :failed :aborted)
        (event-handler running)
        (on-enter on-running))
      (state :ops-complete
        (valid-transitions :completed :failed :aborted)
        (event-handler ops-complete)
        (on-enter on-ops-complete)))))


;; (defn- operate-fsm
;;   "Construct a fsm for coordinating the steps of the operation."
;;   [operation initial-environment]
;;   (let [{:keys [event] :as op-fsm}
;;         (event-machine
;;          (event-machine-config
;;            (fsm-name "operate-fsm")
;;            (initial-state :init)
;;            (state :init
;;              (valid-transitions :running :completed)
;;              (event-handler operate-init))
;;            (state :running
;;              (valid-transitions :step-completed :step-failed)
;;              (event-handler operate-running))
;;            (state :step-completed
;;              (valid-transitions :completed :running :failed :aborted)
;;              (on-enter operate-on-step-completed)
;;              (event-handler operate-step-completed))
;;            (state :step-failed
;;              (valid-transitions :failed :aborted)
;;              (on-enter operate-on-step-failed)
;;              (event-handler operate-step-failed))
;;            (state :completed
;;              (valid-transitions :completed)
;;              (on-enter operate-on-completed))
;;            (state :failed
;;              (valid-transitions :failed)
;;              (on-enter operate-on-failed))
;;            (state :failed
;;              (valid-transitions :aborted)
;;              (on-enter operate-on-failed))))
;;         step-fsms (:steps operation)
;;         op-promise (promise)
;;         state-data {op-env-key initial-environment
;;                     op-steps-key step-fsms
;;                     op-todo-steps-key (vec (reverse step-fsms))
;;                     op-promise-key op-promise
;;                     op-fsm-machines-key []
;;                     op-timeouts-key (atom {})
;;                     op-overall-result-sym-key (:result-sym operation)}]
;;     (logging/debug "Starting operation fsm")
;;     (event :start state-data)
;;     [op-fsm op-promise]))


;;; ## dofsm
;;;
;;; Provides a scope in which FSM's are executed sequentially.

(defn- wire-step-fsm
  "Wire a fsm configuration to the controlling fsm."
  [{:keys [event] :as op-fsm} step-fsm]
  (assert event)
  (update-in step-fsm [:fsm]
             (fn [fsm]
               (merge-fsms
                default-primitive-fsm
                fsm
                (event-machine-config
                  (state :completed
                    (on-enter (fn op-step-completed [state]
                                (event :step-complete state))))
                  (state :failed
                    (on-enter (fn op-step-failed [state]
                                (event :step-fail state)))))))))

;; this is similar to a monadic bind function
(defn- run-step
  "Run an operation step based on the operation primitive."
  [{:keys [state-data] :as state} {:keys [result-f] :as step}]
  {:pre [step result-f]}
  (let [fsm-config (step-fsm (op-env-key state-data) step)
        fsm-config (wire-step-fsm (:em state) fsm-config)
        _ (logging/tracef "run-step config %s" fsm-config)
        {:keys [event] :as fsm} (event-machine (:fsm fsm-config))
        state-data (-> (:state-data state)
                       (update-in [op-todo-steps-key] pop)
                       (update-in [op-fsm-machines-key] conj fsm)
                       (assoc-in [op-result-fn-key] result-f))]
    (execute (fn run-step-f []
               (event :start state-data)))
    (assoc state :state-kw :running :state-data state-data)))

(defn- next-step
  "Return the next primitive to be executed in the operation."
  [state]
  (peek (get-in state [:state-data op-todo-steps-key])))

;;; ## Operation controller FSM
(defn- operate-init
  [state event event-data]
  (case event
    :start (let [state (assoc state
                         :state-data (merge event-data (:state-data state)))]
             (if-let [next-step (next-step state)]
               (run-step state next-step)
               (assoc state :state-kw :completed)))))

(defn- operate-running
  [state event event-data]
  (case event
    :step-complete
    (let [result (get-in event-data [:state-data :result])
          result-fn (get-in event-data [:state-data op-result-fn-key])]
      (update-state
       state :step-completed
       (partial merge-keys {})
       (update-in event-data [op-env-key] result-fn result)))

    :step-fail
    (update-state
     state :step-failed
     (partial merge-keys {}) (:state-data event-data))))

(defn- operate-step-completed
  [state event event-data]
  (case event
    :run-next-step (let [next-step (next-step state)]
                     (try
                       (run-step state next-step)
                       (catch Exception e
                         (update-state
                          state :failed
                          assoc :fail-reason {:exception e}))))
    :complete (assoc state :state-kw :completed)))

(defn- operate-on-step-completed
  [state]
  (if-let [next-step (next-step state)]
    ((-> state :em :event) :run-next-step nil)
    ((-> state :em :event) :complete nil)))

(defn- operate-step-failed
  [state event event-data]
  (logging/debugf "operate-step-failed event %s" event)
  (case event
    :fail (assoc state :state-kw :failed)))

(defn- operate-on-step-failed
  [state]
  ((-> state :em :event) :fail nil))

(defn- operate-on-completed
  [{:keys [state-kw] :as state}]
  (deliver
   (get-in state [:state-data op-promise-key])
   ((get-in state [:state-data op-overall-result-key])
    (get-in state [:state-data op-env-key]))))

(defn- operate-on-failed
  [{:keys [state-kw] :as state}]
  (deliver
   (get-in state [:state-data op-promise-key])
   (get-in state [:state-data :fail-reason])))

;;; ### sequential FSM
(defn seq-fsm [op-name {:keys [steps result-f]}]
  (event-machine-config
    (fsm-name op-name)
    (initial-state :init)
    (initial-state-data
     {op-env-key {}
      op-steps-key steps
      op-todo-steps-key (vec (reverse steps))
      op-fsm-machines-key []
      op-timeouts-key (atom {})
      op-overall-result-key result-f})
    (state :init
      (valid-transitions :running :completed)
      (event-handler operate-init))
    (state :running
      (valid-transitions :step-completed :step-failed)
      (event-handler operate-running))
    (state :step-completed
      (valid-transitions :completed :running :failed :aborted)
      (on-enter operate-on-step-completed)
      (event-handler operate-step-completed))
    (state :step-failed
      (valid-transitions :failed :aborted)
      (on-enter operate-on-step-failed)
      (event-handler operate-step-failed))
    (state :completed
      (valid-transitions :completed)
      (on-enter operate-on-completed))
    (state :failed
      (valid-transitions :failed)
      (on-enter operate-on-failed))
    (state :failed
      (valid-transitions :aborted)
      (on-enter operate-on-failed))))

;;; ### FSM step conversion
(defn ^{:internal true} symbols
  [form]
  (cond
    (or
     (list? form)
     (instance? clojure.lang.IMapEntry form)
     (seq? form)
     (coll? form))
    (->> (map symbols form) flatten (filter identity))
    (symbol? form) [form]
    :else nil))

(defn ^{:internal true} eval-in-env-fn
  "Give a form, return a function of a single `env` argument and that evaluates
the form in the given environment."
  [form syms]
  (letfn [(sym-binding [sym] (list sym `(get ~'env '~sym)))]
    `(fn eval-in-env-fn [~'env]
       (let [~@(mapcat sym-binding syms)]
         ~form))))

(defmacro ^{:internal true} locals-map
  []
  (zipmap (map #(list 'quote %) (keys &env)) (keys &env)))

(defn ^{:internal true} set-in-env-fn
  "Give an expression that is a valid lhs in a binding, return a function of an
  `env` argument and a value that assigns the results of destructuring into
  `env`."
  [expr]
  (let [env (gensym "env") result (gensym "result")]
    `(fn [~env ~result]
       (let [~expr ~result
             locals# (locals-map)]
         (merge
          ~env
          (apply
           dissoc locals# '~env '~result
           (->> (keys locals#)
                (remove #(not (re-matches #".*__[0-9]+" (name %)))))))))))

(defn seq-steps
  "Takes FSM comprehension forms and translates them"
  [steps result]
  (letfn [(quote-if-symbol [s] (if (symbol? s) (list 'quote s) s))]
    (let [steps (->> steps
                     (partition 2)
                     (reductions
                      (fn [prev [res op]]
                        (let [visible (union (:syms prev) (set (symbols res)))]
                          (hash-map
                           :result-f (set-in-env-fn res)
                           :op-sym (list 'quote op)
                           :syms visible
                           :f (eval-in-env-fn op visible))))
                      {})
                     (drop 1)
                     vec)
          final-syms (:syms (last steps))
          steps (vec (map
                      (fn [step] (update-in step [:syms] #(list 'quote %)))
                      steps))]
      `{:steps ~steps
        :result-f ~(eval-in-env-fn result final-syms)})))

(defmacro dofsm
  "A comprehension that results in a compound FSM specification."
  {:indent 1}
  [op-name steps result]
  `(seq-fsm ~(name op-name) ~(seq-steps steps result)))



;;; ## User visible interface
(defprotocol Control
  "Operation control protocol."
  (abort [_] "Abort the operation.")
  (status [_] "Return the status of the operation.")
  (complete? [_] "Predicate to test if operation is complete.")
  (failed? [_] "Predicate to test if operation is failed.")
  (wait-for [_] "wait on the result of the completed operation"))

;; Represents a running operation
(deftype Operation
  [fsm completed-promise]
  Control
  (abort [_] ((:event fsm) :abort nil))
  (status [_] ((:state fsm)))
  (complete? [_] (= :completed (:state-kw ((:state fsm)))))
  (failed? [_] (= :failed (:state-kw ((:state fsm)))))
  (wait-for [_] @completed-promise)
  clojure.lang.IDeref
  (deref [_] @completed-promise))

(defn operate
  "Start the specified `operation` on the given arguments. The call returns an
  object that implements the Control protocol."
  [operation]
  (let [completed-promise (promise)
        {:keys [event] :as fsm} (event-machine operation)]
;;TODO wire result here, so operate can run with arbitrary machines, not just
;;seq*
    (event :start {op-promise-key completed-promise})
    ;; (when-not (= (count (:args operation)) (count args))
    ;;   (throw
    ;;    (IllegalArgumentException.
    ;;     (str "Operation " (:op-name operation)
    ;;          " expects " (vec (:args operation))))))
    (Operation. fsm completed-promise)))

(defn report-operation
  "Print a report on the status of an operation."
  [operation]
  (println "------------------------------")
  (let [status (status operation)
        state-data (:state-data status)
        steps (vec (map :op-sym (op-steps-key state-data)))]
    (println "current state: " (:state-kw status))
    (println "steps:" steps)
    (doseq [[step machine] (map vector steps (op-fsm-machines-key state-data))
            :let [state ((:state machine))]]
      (println step (:state-kw state)))
    (println "env:")
    (pprint (op-env-key state-data)))
  (println "------------------------------"))

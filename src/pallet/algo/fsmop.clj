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
   [clojure.pprint :refer [pprint]]
   [clojure.set :refer [union]]
   [clojure.string :refer [join]]
   [clojure.tools.logging :as logging]
   [pallet.algo.fsm.event-machine :refer [event-machine]]
   [pallet.algo.fsm.fsm-dsl
    :refer [event-handler event-machine-config fsm-name initial-state
            initial-state-data on-enter on-exit
            state valid-transitions configured-states using-fsm-features
            using-stateful-fsm-features]]
   [pallet.algo.fsm.fsm-utils :refer [swap!!]]
   [pallet.map-merge :refer [merge-keys merge-key]]
   [pallet.thread.executor :refer [executor]]))

;;; ## thread pools
(defn report-exceptions
  [f]
  (fn report-exceptions []
    (try
      (f)
      (catch Exception e
        (logging/errorf e "Unexpected exception")))))

(defonce ^{:defonce true}
  operate-executor (executor {:prefix "operate"
                              :thread-group-name "pallet-operate"}))

(defn execute
  "Execute a function in the operate-executor thread pool."
  [f]
  (pallet.thread.executor/execute operate-executor (report-exceptions f)))

(defonce ^{:defonce true}
  scheduled-executor (executor {:prefix "op-sched"
                                :thread-group-name "pallet-operate"
                                :scheduled true
                                :pool-size 3}))

(defn execute-after
  "Execute a function after a specified delay in the scheduled-executor thread
  pool. Returns a ScheduledFuture."
  [f delay delay-units]
  (pallet.thread.executor/execute-after
   scheduled-executor (report-exceptions f) delay delay-units))

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
    (using-fsm-features :lock-transition)
    (initial-state :init)
    (state :init
      (valid-transitions :aborted :running)
      (event-handler default-init-event-handler))
    (state :completed
      (valid-transitions :completed)
      (event-handler do-nothing-event-handler))
    (state :aborted
      (valid-transitions :aborted)
      (event-handler do-nothing-event-handler))
    (state :failed
      (valid-transitions :failed)
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
    :transitions :union
    :fsm/fsm-features :concat
    :fsm/stateful-fsm-features :concat}
   fsm-configs))

;;; ## Environment stack
(def op-state-stack-key ::op-state)
(defn push
  "Push a value onto a stack, using a vector if the stack is nil."
  [stack v]
  (conj (or stack []) v))

(defn push-op-state
  "Push a new `op-state` map onto the ::opt-state key in the FSM's `state` map."
  [state op-state]
  (update-in state [:state-data op-state-stack-key] push op-state))

(defn pop-op-state
  "Pop a `op-state` map off the ::opt-state key in the FSM's `state` map."
  [state]
  (update-in state [:state-data op-state-stack-key] pop))

(defn get-op-state
  "Get the current `op-state` map off the ::opt-state key in the FSM's `state`
  map."
  [state]
  (-> (get-in state [:state-data op-state-stack-key]) peek))

(defn op-state-stack-depth
  "op-state stack depth"
  [state]
  (-> (get-in state [:state-data op-state-stack-key]) count))

;;; ## Fundamental primitives
(defn fail
  "An operation primitive that does nothing but fail immediately."
  ([reason]
     (letfn [(init [state event event-data]
               (case event
                 :start (update-state
                         state :failed
                         assoc :fail-reason reason)))]
       (event-machine-config
         (fsm-name "fail")
         (state :init
           (valid-transitions :failed)
           (event-handler init)))))
  ([] (fail nil)))

(defn succeed
  "An operation primitive that does nothing but succeed or fail immediately
  based on the passed flag. The default is to succeed."
  ([flag fail-reason]
     (letfn [(init [state event event-data]
               (case event
                 :start (if flag
                          (assoc state
                            :state-kw :completed :state-data event-data)
                          (assoc state
                            :state-kw :failed
                            :state-data (assoc event-data
                                          :fail-reason fail-reason)))))]
       (event-machine-config
         (fsm-name "succeed")
         (using-fsm-features :lock-transition)
         (state :init
           (valid-transitions :completed :failed)
           (event-handler init)))))
  ([flag] (succeed flag nil))
  ([] (succeed true nil)))

(defn result
  "An operation primitive that does nothing but succeed immediately with the
   specified result `value`."
  [value]
  (letfn [(init [state event event-data]
            (case event
              :start (assoc state
                       :state-kw :completed
                       :state-data (assoc event-data :result value))))]
    (event-machine-config
      (fsm-name "result")
      (using-fsm-features :lock-transition)
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
      (using-fsm-features :lock-transition)
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
                       delay-units)
                    op-state (get-op-state state)]
                (swap!
                 (op-timeouts-key op-state)
                 assoc timeout-name f))))
          (remove-timeout [timeout-name]
            (fn remove-timeout [{:keys [state-data] :as state}]
              ;; timeouts aren't necessarily in the :init state-data
              (when-let [timeouts (op-timeouts-key (get-op-state state))]
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
               (using-fsm-features :lock-transition)
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
                (using-fsm-features :lock-transition)
                (state :completed
                  (on-enter op-completed))
                (state :failed
                  (on-enter op-failed))
                (state :aborted
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
            (case event
              :start
              (let [configs (wire-fsms state)
                    fsms (map event-machine configs)]
                (if (seq fsms)
                  (->
                   state
                   (update-state :running merge event-data)
                   (push-op-state {::fsms fsms ::pending-fsms (set fsms)}))
                  (assoc state
                    :state-kw :completed
                    :state-data (assoc event-data :result nil))))))
          (on-running [{:keys [state-data] :as state}]
            (logging/debug "map* on running")
            (let [fsms (::fsms (get-op-state state))]
              (logging/debugf "map* on-running starting %s fsms" (count fsms))
              (doseq [{:keys [event] :as fsm} fsms]
                (execute #(event :start state)))))
          (maybe-finish [{:keys [state-data] :as state}]
            (logging/debugf
             "maybe-finish pending count %s"
             (count (::pending-fsms (get-op-state state))))
            (if (seq (::pending-fsms (get-op-state state)))
              state
              (assoc state :state-kw :ops-complete)))
          (running [{:keys [state-data] :as state} event event-data]
            (logging/debugf
             "running pending count %s"
             (count (::pending-fsms (get-op-state state))))
            (case event
              :op-complete
              (let [{:keys [em]} event-data
                    op-state (->
                              (get-op-state state)
                              (update-in [::pending-fsms] disj em)
                              (update-in [::completed-states]
                                         conj (:state-data event-data)))]
                (logging/debugf
                 "op-complete result: %s"
                 (-> event-data :state-data :result))
                (maybe-finish (-> state pop-op-state (push-op-state op-state))))
              :op-fail
              (let [{:keys [em]} event-data
                    op-state (->
                              (get-op-state state)
                              (update-in [::pending-fsms] disj em)
                              (update-in [::failed-states]
                                         conj (:state-data event-data)))]
                (maybe-finish (-> state pop-op-state (push-op-state op-state))))
              :abort
              (do
                (doseq [machine (::pending-fsms (get-op-state state))
                        :let [event (:event machine)]]
                  (event :abort event-data))
                state)))
          (ops-complete [{:keys [state-data] :as state} event event-data]
            (case event
              :abort (->
                      state
                      pop-op-state
                      (update-state :aborted assoc :fail-reason event-data))
              :fail (let [op-state (get-op-state state)]
                      (->
                       state
                       pop-op-state
                       (update-state
                        :failed
                        assoc
                        :fail-reason
                        {:reason :failed-ops
                         :fail-reasons (map
                                        :fail-reason
                                        (::failed-states op-state))}
                        :result
                        (map :result
                             (::completed-states (get-op-state state))))))
              :complete (->
                         state
                         pop-op-state
                         (update-state
                          :completed
                          assoc :result
                          (map :result
                               (::completed-states (get-op-state state)))))))
          (on-ops-complete [{:keys [em state-data] :as state}]
            (let [{:keys [event]} em]
              (if (seq (::failed-states (get-op-state state)))
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






;;; ## Sequencing: dofsm and seq*
;;;
;;; Provide a FSM in which FSM's are executed sequentially.


;; (defn update-op-state
;;   "Pop a `op-state` map off the ::opt-state key in the FSM's `state` map."
;;   [state f & args]
;;   (update-in state [:state-data op-state-stack-key]
;;              (fn [op-state]
;;                (let [op-state-m (peek op-state)
;;                      op-state (pop op-state)]
;;                  (conj op-state (apply f op-state-m args))))))


;;; ### Sequential Steps

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
                                (execute #(event :step-complete state)))))
                  (state :failed
                    (on-enter (fn op-step-failed [state]
                                (execute #(event :step-fail state)))))
                  (state :aborted
                    (on-enter (fn op-step-aborted [state]
                                (execute #(event :step-abort state))))))))))

(defn- run-step
  "Run an operation step based on the operation primitive."
  [{:keys [state-data] :as state} {:keys [result-f] :as step}]
  {:pre [step result-f]}
  (let [op-state (get-op-state state)
        fsm-config (step-fsm (op-env-key op-state) step)
        fsm-config (wire-step-fsm (:em state) fsm-config)
        _ (logging/tracef "run-step config %s" fsm-config)
        {:keys [event] :as fsm} (event-machine (:fsm fsm-config))
        op-state (-> op-state
                     (update-in [op-todo-steps-key] pop)
                     (update-in [op-fsm-machines-key] conj fsm)
                     (assoc-in [op-result-fn-key] result-f))
        state (-> state pop-op-state (push-op-state op-state))]
    (execute (fn run-step-f []
               (event :start (:state-data state))))
    (assoc state :state-kw :running)))

(defn- next-step
  "Return the next primitive to be executed in the operation."
  [state]
  (-> state get-op-state op-todo-steps-key peek))

;;; ### Operation controller FSM
;;;
;;; The initial state set by the FSM config is the operation-state.
(defn- seq-init
  [state event event-data]
  (logging/debugf "seq-init on %s event" event)
  (case event
    :start (let [init-state state
                 state (-> (push-op-state
                            (assoc state :state-data event-data)
                            (:state-data state))
                           (dissoc
                            op-env-key op-steps-key op-todo-steps-key
                            op-fsm-machines-key op-timeouts-key
                            op-overall-result-key))]
             (if-let [next-step (next-step state)]
               (try
                 (run-step state next-step)
                 (catch Exception e
                   (update-state
                    state :failed
                    assoc :fail-reason {:exception e})))
               (->
                state
                pop-op-state
                (update-state
                 :completed
                 assoc :result
                 ((get-in init-state [:state-data op-overall-result-key])
                  (get-in init-state [:state-data op-env-key]))))))
    :abort (-> state (assoc :state-kw :aborted) pop-op-state)
    (do
      (logging/errorf "seq-init invalid event %s" event)
      (throw
       (ex-info
        "Invalid event in seq-init"
        {:state state
         :event event
         :event-data event-data
         :reason :invalid-event})))))

(defn- seq-running
  [state event event-data]
  (logging/debugf "seq-running")
  (case event
    :step-complete
    (let [result (get-in event-data [:state-data :result])
          history (get-in event-data [:history])
          op-state (get-op-state event-data)
          result-fn (get op-state op-result-fn-key)]
      (try
        (->
         state
         (update-in [:history] conj history)
         (update-state
          :step-completed
          (partial merge-keys {})
          (-> event-data
              pop-op-state
              (push-op-state (update-in op-state [op-env-key] result-fn result))
              :state-data)))
        (catch Exception e
          (update-state
           state :failed
           assoc :fail-reason {:exception e}))))

    :step-fail
    (let [history (get-in event-data [:history])]
      (->
       state
       (update-in [:history] conj history)
       (update-state
        :step-failed
        (partial merge-keys {})
        (:state-data event-data))))

    :step-abort
    (let [history (get-in event-data [:history])]
      (->
       state
       (update-in [:history] conj history)
       (update-state
        :aborted
        (partial merge-keys {})
        (:state-data event-data))))

    :abort (if-let [machine (peek (op-fsm-machines-key (get-op-state state)))]
             (do
               ((:event machine) :abort nil)
               state)
             (-> state (assoc :state-kw :aborted) pop-op-state))))

(defn- seq-step-completed
  [state event event-data]
  (case event
    :run-next-step (let [next-step (next-step state)]
                     (try
                       (run-step state next-step)
                       (catch Exception e
                         (update-state
                          state :failed
                          assoc :fail-reason {:exception e}))))
    :complete (let [op-state (-> state get-op-state)]
                (->
                 (pop-op-state state)
                 (update-state
                  :completed
                  assoc :result
                  ((get op-state op-overall-result-key)
                   (get op-state op-env-key)))))
    :abort (-> state (assoc :state-kw :aborted) pop-op-state)))

(defn- seq-on-step-completed
  [state]
  (if-let [next-step (next-step state)]
    ((-> state :em :event) :run-next-step nil)
    ((-> state :em :event) :complete nil)))

(defn- seq-step-failed
  [state event event-data]
  (logging/debugf "seq-step-failed event %s" event)
  (case event
    :fail (-> state (assoc :state-kw :failed) pop-op-state)
    :abort (-> state (assoc :state-kw :aborted) pop-op-state)))

(defn- seq-on-step-failed
  [state]
  ((-> state :em :event) :fail state))

;;; ### Sequential FSM
(defn seq-fsm [op-name {:keys [steps result-f]} initial-env]
  (event-machine-config
    (fsm-name (and op-name (keyword (str "dofsm-" (name op-name)))))
    (using-stateful-fsm-features :history)
    (using-fsm-features :lock-transition)
    (initial-state :init)
    (initial-state-data
     {op-env-key initial-env
      op-steps-key steps
      op-todo-steps-key (vec (reverse steps))
      op-fsm-machines-key []
      op-timeouts-key (atom {})
      op-overall-result-key result-f})
    (state :init
      (valid-transitions :running :completed :aborted :failed)
      (event-handler seq-init))
    (state :running
      (valid-transitions :step-completed :step-failed :aborted :running :failed)
      (event-handler seq-running))
    (state :step-completed
      (valid-transitions :completed :running :failed :aborted)
      (on-enter seq-on-step-completed)
      (event-handler seq-step-completed))
    (state :step-failed
      (valid-transitions :failed :aborted)
      (on-enter seq-on-step-failed)
      (event-handler seq-step-failed))))

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
    `(fn ~(gensym "eval-in-env-fn") [~'env]
       (let [~@(mapcat sym-binding syms)]
         ~form))))

(defmacro ^{:internal true} locals-map
  []
  (zipmap (map #(list 'quote %) (keys &env)) (keys &env)))

(defn ^{:internal true} kill-except
  [&env & locals]
  (->>
   (remove (set locals) (keys &env))
   (mapcat #(vector % ::killed))))

(defn ^{:internal true} set-in-env-fn
  "Give an expression that is a valid lhs in a binding, return a function of an
  `env` argument and a value that assigns the results of destructuring into
  `env`."
  [expr &env]
  (let [env (gensym "env")
        result (gensym "result")
        captured (gensym "captured")
        set-in-env-name (gensym "set-in-env")]
    `(fn ~set-in-env-name [~env ~result]
       (let [~@(kill-except &env env result)
             ~expr ~result
             locals# (locals-map)
             env# (into
                   {}
                   (->>
                    (apply
                     dissoc locals# '~env '~result
                     (->> (keys locals#)
                          (remove #(not (re-matches #".*__[0-9]+" (name %))))))
                    (remove #(= ::killed (val %)))))]
         (merge ~env env#)))))

(defn seq-steps
  "Takes FSM comprehension forms and translates them"
  [steps result &env]
  (letfn [(quote-if-symbol [s] (if (symbol? s) (list 'quote s) s))]
    (let [steps (->> steps
                     (partition 2)
                     (reductions
                      (fn [prev [res op]]
                        (let [visible (union (:syms prev) (set (symbols res)))]
                          (hash-map
                           :result-f (set-in-env-fn res &env)
                           :op-sym (list 'quote op)
                           :syms visible
                           :f (eval-in-env-fn op (:syms prev)))))
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
  `(seq-fsm ~(name op-name) ~(seq-steps steps result &env) {}))

(defn reduce*
  "A reduce function where the passed reducing function `f` should return a FSM
  configuration, whose result when executed, will be passed to the next call of
  the reducing function."
  [f init-value s]
  (let [result-sym (gensym "result")
        result-f (fn reduce*-result-f [env]
                   (logging/debugf
                    "getting %s -> %s" result-sym (get env result-sym))
                   (get env result-sym))
        set-f (fn reduce*-set-f [env v]
                (logging/debugf "setting %s to %s" result-sym v)
                (assoc env result-sym v))]
    (seq-fsm
     "reduce*"
     {:result-f result-f
      :steps (map
              (fn reduce*-step-map [arg]
                (hash-map
                 :result-f set-f
                 :op-sym 'reduce*
                 :syms #{result-sym}
                 :f (fn reduce*-step [env]
                      (logging/debugf "reduce*-step %s" arg)
                      (f (get env result-sym) arg))))
              s)}
     {result-sym init-value})))


;;; ## User visible execution interface
(defprotocol Control
  "Operation control protocol."
  (abort [_] "Abort the operation.")
  (status [_] "Return the status of the operation.")
  (complete? [_] "Predicate to test if operation is complete.  Returns false if
the operation failed with an error, true if the operation succeeded, or nil
otherwise")
  (failed? [_]
    "Predicate to test if operation is failed.  Returns false if the operation
completed without error, true if the operation failed, or nil otherwise.")
  (running? [_] "Predicate to test if the operation is running.")
  (wait-for [_] [_ timeout-ms timeout-val]
    "wait on the result of the completed operation"))

;; Represents a running operation
(deftype Operation
  [fsm completed-promise]
  Control
  (abort [_] ((:event fsm) :abort nil))
  (status [_] ((:state fsm)))
  (complete? [_] (or (= :completed (:state-kw ((:state fsm))))
                     (if (realized? completed-promise)
                       false
                       nil)))
  (failed? [_] (or (= :failed (:state-kw ((:state fsm))))
                   (if (realized? completed-promise)
                     false
                     nil)))
  (running? [_] (not (realized? completed-promise)))
  (wait-for [_] @completed-promise)
  (wait-for [_ timeout-ms timeout-val]
    (deref completed-promise timeout-ms timeout-val))
  clojure.lang.IDeref
  (deref [op]
    (if-let [e (:exception @completed-promise)]
      (throw e)
      @completed-promise))
  clojure.lang.IBlockingDeref
  (deref [op timeout-ms timeout-val]
    (let [v (deref completed-promise timeout-ms timeout-val)]
      (if (= v timeout-val)
        v
        (if-let [e (:exception v)]
          (throw e)
          v)))))

(defmethod print-method Operation [^Operation op writer]
  (let [state ((:state (.fsm op)))
        history (:history state)
        n (:fsm/name (first history))
        states (doall (map :state-kw history))]
    (.write writer "#<")
    (.write writer (.getSimpleName (class op)))
    (when n
      (.write writer " ")
      (.write writer (name n)))
    (.write writer ": ")
    (.write writer (let [x (failed? op)]
                     (cond
                      x "failed"
                      (nil? x) "running"
                      :else "complete")))
    (when (seq states)
      (.write writer " ")
      (.write writer (join " " states))))
  (.write writer ">"))

;;; ## Operate
(defn- operate-on-completed
  "on-enter function for completed state."
  [{:keys [state-kw] :as state}]
  (let [op-state (get-op-state state)]
    (deliver
     (get-in state [:state-data op-promise-key])
     (get-in state [:state-data :result]))))

(defn- operate-on-failed
  "on-enter function for failed and aborted states."
  [{:keys [state-kw] :as state}]
  (deliver
   (get-in state [:state-data op-promise-key])
   (get-in state [:state-data :fail-reason])))

(def operate-machine-config
  (event-machine-config
    (state :completed
      (valid-transitions :completed)
      (on-enter operate-on-completed))
    (state :failed
      (valid-transitions :failed)
      (on-enter operate-on-failed))
    (state :aborted
      (valid-transitions :aborted)
      (on-enter operate-on-failed))
    (state :timed-out
      (valid-transitions :timed-out)
      (on-enter operate-on-failed))))

(defn operate
  "Start the specified `operation` on the given arguments. The call returns an
  object that implements the Control protocol."
  [operation]
  (let [completed-promise (promise)
        {:keys [event] :as fsm} (event-machine
                                 (merge-fsms operation operate-machine-config))]
    (event :start {op-promise-key completed-promise})
    (Operation. fsm completed-promise)))

(defn sanitise-history
  [{:keys [env steps history history-results] :as options} h]
  (if (map? h)
    (->
      h
      (update-in
       [:state-data]
       dissoc op-state-stack-key op-env-key op-steps-key
       op-todo-steps-key op-fsm-machines-key op-timeouts-key
       op-overall-result-key op-promise-key
       (when-not history-results :result))
      (dissoc op-promise-key))
    (when (seq h)
      (filter identity (map (partial sanitise-history options) h)))))

(defn- report-operation-state
  [state op-state indent
   {:keys [env steps history history-results] :as options}]
  (let [steps-seq (vec (map :op-sym (op-steps-key op-state)))]
    (when env
      (println indent "env:")
      (println)
      (pprint (op-env-key op-state)) ;; can't set initial indent :(
      (println))
    (when-let [history (and history (seq (:history state)))]
      (println)
      (println indent "history:")
      (pprint (->> history
                   (map (partial sanitise-history options))
                   (filter identity)))
      (println))
    (doseq [[step machine] (map vector steps-seq (op-fsm-machines-key op-state))
            :let [m-state ((:state machine))
                  m-op-state (get-op-state m-state)]]
      (println indent step (:state-kw m-state)
               (or
                (and (= :failed (:state-kw m-state))
                     (-> m-state :state-data :fail-reason))
                ""))

      (when (not= (op-state-stack-depth state) (op-state-stack-depth m-state))
        (report-operation-state
         m-state m-op-state (str indent "  ") options)))))

(defn report-operation
  "Print a report on the status of an operation."
  [operation & {:keys [env steps history] :as options}]
  (println "------------------------------")
  (let [state (status operation)
        op-state (get-op-state state)
        steps-seq (vec (map :op-sym (op-steps-key op-state)))]
    (println "status:" (:state-kw state))
    (when-let [fail-reason (and (= :failed (:state-kw state))
                                (-> state :state-data :fail-reason))]
      (println "  " fail-reason))
    (when steps
      (println "steps:" steps-seq))
    (report-operation-state state op-state "" options))
  (println "------------------------------"))

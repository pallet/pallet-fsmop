(ns pallet.algo.fsmop-test
  (:require
   [clojure.tools.logging :as logging])
  (:use
   clojure.test
   pallet.algo.fsmop)
  (:import
   pallet.algo.fsmop.Operation))

(deftest dofsm-test
  (testing "empty"
    (let [operation (dofsm succeed-test [] 1)
          op (operate operation)]
      (is (= 1 @op))
      (is (complete? op))
      (is (not (failed? op))))))

(deftest succeed-test
  (testing "succeed"
    (let [operation (dofsm succeed-test
                      [_ (succeed)]
                      _)
          op (operate operation)]
      (is (nil? @op))
      (is (complete? op))
      (is (not (failed? op)))))
  (testing "succeed with flag"
    (let [operation (dofsm succeed-test
                      [_ (succeed true)
                       _ (succeed true :reason)]
                      _)
          op (operate operation)]
      (is (nil? @op))
      (is (complete? op))
      (is (not (failed? op)))))
  (testing "succeed fail with reason"
    (let [operation (dofsm succeed-test
                      [_ (succeed false :reason)]
                      _)
          op (operate operation)]
      (is (= :reason @op))
      (is (not (complete? op)))
      (is (failed? op))))
  (testing "succeed, succeed"
    (let [operation (dofsm succeed-test
                      [_ (succeed)
                       _ (succeed)]
                      _)
          op (operate operation)]
      @op
      (is (complete? op))
      (is (not (failed? op))))))

(deftest fail-test
  (testing "fail"
    (let [operation (dofsm fail-test
                      [_ (fail)]
                      _)
          op (operate operation)]
      @op
      (is (not (complete? op)))
      (is (failed? op))))
  (testing "fail with reason"
    (let [operation (dofsm fail
                      [_ (fail :bad)]
                      _)
          op (operate operation)]
      (is (= :bad @op))
      (is (not (complete? op)))
      (is (failed? op))))
  (testing "fail, succeed"
    (let [operation (dofsm fail
                      [_ (fail)
                       _ (succeed)]
                      _)
          op (operate operation)]
      @op
      (is (not (complete? op)))
      (is (failed? op))))
  (testing "succeed, fail, succeed"
    (let [operation (dofsm fail
                      [_ (succeed)
                       _ (fail)
                       _ (succeed)]
                      _)
          op (operate operation)]
      @op
      (is (not (complete? op)))
      (is (failed? op)))))

(deftest result-test
  (testing "result"
    (let [operation (fn [v] (dofsm result
                              [x (result v)]
                              x))
          op (operate (operation :ok))]
      (is (= :ok @op))
      (is (complete? op))
      (is (not (failed? op)))))
  (testing "result destructuring"
    (let [operation (fn [v] (dofsm result
                              [[x y] (result v)]
                              x))
          op (operate (operation [:ok 1]))]
      (is (= :ok @op))
      (is (complete? op))
      (is (not (failed? op)))))
  (testing "final result destructuring"
    (let [operation (fn [v] (dofsm result
                              [[x y] (result v)]
                              [y x]))
          op (operate (operation [:ok 1]))]
      (is (= [1 :ok] @op))
      (is (complete? op))
      (is (not (failed? op)))))
  (testing "result, fail"
    (let [operation (fn [v] (dofsm result
                              [x (result v)
                               _ (fail :bad)]
                              x))
          op (operate (operation :ok))]
      (is (= :bad @op))
      (is (not (complete? op)))
      (is (failed? op))))
  (testing "ordering of steps"
    (let [operation (fn [v] (dofsm result
                              [x (result 1)
                               x (result (+ x 2))]
                              x))
          op (operate (operation :ok))]
      (is (= 3 @op))
      (is (complete? op))
      (is (not (failed? op))))))


(defmacro time-body
  "Evaluates body and returns a vector of the expression's result, and the time
  it took in ms."
  {:added "1.0"} [& body]
  `(let [start# (. System (nanoTime))
         ret# (do ~@body)]
     [ret#
      (/ (double (- (. System (nanoTime)) start#)) 1000000.0)]))

(deftest delay-test
  (testing "time-body"
    (let [[r t] (time-body (Thread/sleep 1000))]
      (is (< (- t 1000) 500))))
  (testing "delay-for"
    (let [delay-op (fn [delay-length]
                     (dofsm delay-op
                       [_ (delay-for delay-length :ms)]
                       _))
          ;; start operation
          [op t] (time-body (let [op (operate (delay-op 1000))]
                              (is (instance? Operation op))
                              (is (not (complete? op)))
                              (is (nil? @op))
                              op))]
      (is (complete? op))
      ;; if this fails, check the volume of debugging info being logged
      (is (< (- t 1000) 500)))))

(deftest timeout-test
  (testing "timeout fires"
    (let [operation (fn [delay-length]
                      (dofsm delay-op
                        [_ (timeout
                            (delay-for delay-length :ms)
                            (/ delay-length 2) :ms)]
                        _))
          ;; start operation
          [op t] (time-body (let [op (operate (operation 1000))]
                              (is (instance? Operation op))
                              (is (not (complete? op)))
                              (is (= {:reason :timed-out} @op))
                              op))]
      (is (not (complete? op)))
      (is (failed? op))
      ;; if this fails, check the volume of debugging info being logged
      (is (< (- t 500) 400))))
  (testing "timeout doesn't fire"
    (let [operation (fn [delay-length]
                      (dofsm delay-op
                        [_ (timeout
                            (delay-for delay-length :ms)
                            (* delay-length 2) :ms)]
                        _))
          ;; start operation
          [op t] (time-body (let [op (operate (operation 500))]
                              (is (instance? Operation op))
                              (is (not (complete? op)))
                              (is (nil? @op))
                              op))]
      (is (complete? op))
      (is (not (failed? op)))
      ;; if this fails, check the volume of debugging info being logged
      (is (< (- t 500) 400)))))

(deftest map*-test
  (testing "map* with result tasks"
    (let [operation (fn [n]
                      (dofsm map*-success
                        [x (map* (repeat n (result 1)))]
                        x))]
      (testing "one task"
        (let [op (operate (operation 1))]
          (is (= [1] @op))
          (is (complete? op))
          (is (not (failed? op)))))
      (testing "three tasks"
        (let [op (operate (operation 3))]
          (is (= [1 1 1] @op))
          (is (complete? op))
          (is (not (failed? op)))))
      (testing "zero tasks"
        (let [op (operate (operation 0))]
          (is (nil? @op))
          (is (complete? op))
          (is (not (failed? op)))))))
  (testing "zero args after non-nil result"
    (let [operation (dofsm map*-success
                      [x (result 1)
                       x (map* nil)]
                      x)]
      (testing "returns nil"
        (let [op (operate operation)]
          (is (nil? @op))
          (is (complete? op))
          (is (not (failed? op)))))))
  (testing "tasks with fail"
    (let [operation (dofsm map*-success
                      [x (map* [(result 1) (fail :because)])]
                      x)
          op (operate operation)]
      (is (= {:reason :failed-ops :fail-reasons [:because]}
             @op))
      (is (failed? op))
      (is (not (complete? op)))))
  (testing "nested tasks"
    (let [operation (fn [n]
                      (dofsm map*-success
                        [x (map* (repeat n (map* (repeat 2 (result 1)))))]
                        x))
          op (operate (operation 3))]
      (is (= [[1 1] [1 1] [1 1]] @op))
      (is (complete? op))
      (is (not (failed? op)))))
  (testing "nested tasks with fail"
    (let [operation (fn [n]
                      (dofsm map*-success
                        [x (map* (repeat n (map* [(result 1) (fail :nok)])))]
                        x))
          op (operate (operation 3))]
      (is (= {:reason :failed-ops
              :fail-reasons [{:reason :failed-ops, :fail-reasons [:nok]}
                             {:reason :failed-ops, :fail-reasons [:nok]}
                             {:reason :failed-ops, :fail-reasons [:nok]}]}
             @op))
      (is (failed? op))
      (is (not (complete? op))))))

(deftest reduce*-test
  (testing "reduce* with result tasks"
    (let [operation (fn [s]
                      (dofsm reduce*-test
                        [x (reduce* (fn [res v] (result (+ res v))) 0 s)]
                        x))]
      (testing "one task"
        (let [op (operate (operation [1 2 3]))]
          (is (= 6 @op))
          (is (complete? op))
          (is (not (failed? op)))))
      (testing "no tasks"
        (let [op (operate (operation []))]
          (is (= 0 @op))
          (is (complete? op))
          (is (not (failed? op))))))))

(deftest nested-dofsm-test
  (testing "nested dofsm"
    (let [operation (fn [v] (dofsm result-outer
                              [x (dofsm result-inner
                                   [y (result v)]
                                   y)]
                              x))
          op (operate (operation :ok))]
      (is (= :ok @op))
      (is (complete? op))
      (is (not (failed? op)))))
  (testing "nested failed dofsm"
    (let [operation (fn [v] (dofsm result-outer
                              [x (dofsm fail-inner
                                   [y (fail :reason)]
                                   y)]
                              x))
          op (operate (operation :ok))]
      (is (= :reason @op))
      (is (not (complete? op)))
      (is (failed? op))))
  (testing "double nested failed dofsm"
    (let [operation (fn [v] (dofsm result-outer
                              [w (dofsm result-inter
                                   [x (dofsm fail-inner
                                        [y (fail :reason)]
                                        y)]
                                   x)]
                              w))
          op (operate (operation :ok))]
      (is (= :reason @op))
      (is (not (complete? op)))
      (is (failed? op))))
  (testing "nested exception"
    (let [e (Exception. "e")
          operation (fn [v] (dofsm result-outer
                              [x (dofsm fail-inner
                                   [y (fail {:exception e})]
                                   y)]
                              x))
          op (operate (operation :ok))]
      (is (= {:exception e} @op))
      (is (= {:exception e} (wait-for op)))
      (is (not (complete? op)))
      (is (failed? op)))))

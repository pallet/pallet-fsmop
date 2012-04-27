# pallet-fsmop

A finite state machine composition library.

The library is built on composing FSM's with well defined initial and final
states. The FSM's are built using [Pallet-fsm][pallet-fsm], a library
for constructing arbitrary finite state machines.

The fsm's that can be composed should have an :init initial state, and should
respond to the :start event.  They should have :completed, :failed, :aborted,
and maybe :timed-out as terminal states.

## Usage

FSM's are built as functions, and then executed using the `operate` function,
which returns a data-structure. The return data-structure can be deref'd to
block until completion.

The library comes with a comprehension `dofsm` for building FSMs. In this first
example, we use the `result` fsm, which takes a non-fsm value and creates a fsm
that returns it.

```clj
(let [op (fn [v] (dofsm result
                   [x (result 1)
                    x (result (+ x 2))]
                   x))
      op (operate (op :ok))]
  @op ; => 3
  (complete? op) ; => true
  (failed? op) ; => nil
```

Other built-in fsms are `delay-for`, `success` and `fail`.

The library also provides higher order fsm's. The `timeout` form adds a timeout
to another fsm.

```clj
(let [op (fn [delay-length]
           (dofsm delay-op
             [_ (timeout
                 (delay-for delay-length :ms)
                 500 :ms)]
             _))
      op (operate (op 500))]
  @op)
```

The code above will time-out, if passed a delay-length greater than 500ms.

Another higher order fsm is `map*`, that will run a sequence of fsm's in
parallel and wait for them to all complete.

## Installation

To use pallet-fsmop, add the following to your :dependencies:

[pallet-fsm-op "0.1.0-SNAPSHOT"]

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.

[pallet-fsm]: https://github.com/pallet/pallet-fsm "Pallet-fsm library"

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

### `dofsm` comprehension

The library comes with a comprehension `dofsm` for building FSMs. In this first
example, we use the `result` FSM function, which takes a non-FSM value and
creates a FSM that returns it.

```clj
(let [op (fn [v] (dofsm my-calc
                   [x (result 1)
                    x (result (+ x 2))]
                   x))
      op (operate (op :ok))]
  @op ; => 3
  (complete? op) ; => true
  (failed? op) ; => nil
```

`dofsm` takes a name, a sequence of result and FSM config bindings and a result
expression. It returns a FSM specification that can be run by `operate`.

Other built-in fsms are `delay-for`, `success`, `result` and `fail`.

### Higher order FSMs

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

Other higher order FSMs are `map*`, that will run a sequence of fsm's in
parallel and wait for them to all complete, and `reduce*`, that will thread
results through a sequence of FSMs.

### Running a FSM

A FSM configuration can be run by the `operate` function. The function returns a
FSM `Operation` object that can be used to obtain the state of the FSM operation
via the `Control` protocol.

You can wait for the FSM to complete by `deref`'ing the FSM operation.

### Inspection

The `report-operation` function can be used to inspect the state of an operation
that is running.


## Installation

To use pallet-fsmop, add the following to your `:dependencies` in `project.clj`:

```clj
[pallet-fsmop "0.1.0-SNAPSHOT"]
```

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.

[pallet-fsm]: https://github.com/pallet/pallet-fsm "Pallet-fsm library"

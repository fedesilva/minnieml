  # Specification: Zero-Cost Stream Fusion for MinnieML

  ## 1. Abstract
  This specification defines the mechanism for "Stream Fusion"—compiling high-level functional iterator chains (`map`, `filter`, `flatMap`) into efficient, closure-free, register-based loops.

  The system relies on a **Pull-Based State Machine** model. Streams are not heap-allocated iterators; they are compile-time "recipes" that the compiler specializes and inlines. The final machine code preserves the semantics of functional composition while matching the performance of hand-written nested C loops.

  ## 2. Core Primitives

  ### 2.1 The Step Signal
  The heartbeat of the fusion engine. Every iteration of a loop produces one of three signals.

  enum Step 'State 'Elem =
    | Stop                 # Terminate the loop
    | Skip: 'State         # Continue looping with new state (produced no value)
    | Yield: 'Elem, 'State # Found a value, continue with new state
  ;

  ### 2.2 The Stream Protocol
  The contract that allows any container or generator to participate in fusion. It separates the **Container** (`'Self`) from the **Cursor** (`'State`).

  # 'Self:  The container or generator type (e.g., Array, FileHandle, MapStream)
  # 'State: The cursor type needed to traverse it (e.g., Int, Tuple)
  # 'Elem:  The type of value produced
  protocol Stream 'Self 'State 'Elem =
    # Creates the initial cursor state
    fn init: 'Self -> 'State
    
    # Advances the state machine by one step
    # Must be pure and side-effect free regarding the stream structure
    fn step: 'Self -> 'State -> Step 'State 'Elem
  ;

  ## 3. Structural Representations (The Recipes)

  These structs represent the *intent* to perform an operation. They hold the necessary data (closures, source collections) but perform no work upon instantiation.

  ### 3.1 Sources
  # A wrapper to treat a standard Array as a Stream source
  struct ArrayStream 'T =
    arr: Array 'T
  ;

  ### 3.2 Combinators
  # Represents a stream transformed by a function 'F
  struct MapStream 'Src 'F =
    source: 'Src
    f: 'F
  ;

  # Represents a stream filtered by a predicate 'P
  struct FilterStream 'Src 'P =
    source: 'Src
    pred: 'P
  ;

  # Represents a nested stream (Monadic Bind)
  struct FlatMapStream 'Src 'F =
    source: 'Src
    f: 'F
  ;

  ## 4. Implementation Logic

  These implementations define the logic that the compiler will **inline** into the final loop.

  ### 4.1 Array Source
  *State:* `Int` (Index)

  instance Stream (ArrayStream 'T) Int 'T =
    fn init(s) = 0
    
    fn step(s, idx) =
      if idx < s.arr.length then
        Yield(s.arr[idx], idx + 1)
      else
        Stop
  ;

  ### 4.2 Map (Functor)
  *State:* Inherits `'S` from Source.

  instance Stream (MapStream 'Src 'F) 'S 'B
    given (Stream 'Src 'S 'A) = # Constraint: Source yields 'A
    
    fn init(ms) = Stream.init(ms.source)
    
    fn step(ms, state) =
      let result = Stream.step(ms.source, state)
      result match
      | Stop -> Stop
      | Skip next -> Skip(next)
      | Yield (val, next) -> 
          # Apply transformation immediately
          Yield(ms.f(val), next)
  ;

  ### 4.3 Filter (Refinement)
  *State:* Inherits `'S` from Source.

  instance Stream (FilterStream 'Src 'P) 'S 'A
    given (Stream 'Src 'S 'A) =
    
    fn init(fs) = Stream.init(fs.source)
    
    fn step(fs, state) =
      let result = Stream.step(fs.source, state)
      result match
      | Stop -> Stop
      | Skip next -> Skip(next)
      | Yield (val, next) ->
          if fs.pred(val) then
            Yield(val, next)
          else
            # Discard value, loop again with updated state
            Skip(next)
  ;

  ### 4.4 FlatMap (Monad)
  *State:* Product Tuple `('OuterS, Maybe 'InnerS)`.
  This logic fuses nested loops into a single state machine.

  # Constraints:
  # 'Src yields 'A
  # 'F maps 'A -> 'InnerStream
  # 'InnerStream yields 'B and has state 'InnerS
  instance Stream (FlatMapStream 'Src 'F) ('OuterS, Maybe 'InnerS) 'B =

    fn init(fm) = 
      (Stream.init(fm.source), None)

    fn step(fm, state) =
      state match
      # Phase 1: Inner stream is exhausted or not started.
      # Action: Advance outer stream.
      | (out_s, None) ->
          let res = Stream.step(fm.source, out_s)
          res match
          | Stop -> Stop
          | Skip next_o -> Skip(next_o, None)
          | Yield (item, next_o) ->
              # Generate the inner stream
              let inner_stream = fm.f(item)
              let inner_s = Stream.init(inner_stream)
              # Transition to Phase 2
              Skip(next_o, One(inner_s))

      # Phase 2: Inner stream is active.
      # Action: Advance inner stream.
      | (out_s, One in_s) ->
          # Note: Access to the current 'inner_stream' object is required here.
          let res = Stream.step(current_inner, in_s)
          res match
          | Stop -> 
              # Inner finished, revert to Phase 1
              Skip(out_s, None)
          | Skip next_i -> 
              Skip(out_s, One(next_i))
          | Yield (val, next_i) -> 
              Yield(val, (out_s, One(next_i)))
  ;

  ## 5. Compiler Strategy & Back-end Rules

  ### 5.1 Monomorphization (Specialization)
  MinnieML is strictly typed. The compiler must perform full monomorphization on Stream chains.
  * Input: `arr.map(f).filter(p)`
  * Type: `FilterStream (MapStream (ArrayStream Int) typeof(f)) typeof(p)`
  * Action: The compiler generates a specialized version of the `step` function for *this exact type signature*.

  ### 5.2 State Flattening (Scalar Replacement)
  The State tuple for complex chains must be flattened into registers.
  * Logical State: `(OuterInt, Maybe (InnerInt))`
  * Physical Storage:
      * `Register A`: Outer Index (`Int`)
      * `Register B`: Inner Active Flag (`Bool`)
      * `Register C`: Inner Index (`Int`)

  *Handling Inner Stream Objects:*
  For `flatMap`, the inner stream object generated by `f` might contain captured data. If `f` is a closure, the compiler must allocate stack space ("stack spilling") to hold the environment of the current inner stream while it is active.

  ### 5.3 Control Flow Synthesis (Unstaging)
  When a terminal operation (e.g., `fold`, `reduce`, `collect`) is called, the compiler emits the loop.

  Algorithm:
  1.  Emit **Init Code**: Call `Stream.init` to set up initial registers.
  2.  Emit **Label**: `LOOP_START:`
  3.  Emit **Body**: Inline the specialized `Stream.step` logic.
      * `Yield (x, s)` -> Run fold logic with `x`; Update registers with `s`; `goto LOOP_START`.
      * `Skip (s)` -> Update registers with `s`; `goto LOOP_START`.
      * `Stop` -> Break loop.

  ## 6. Example Trace

  Source Code:
  let result = arr       # [1, 2, 3]
    .map(x -> x + 1)
    .flatMap(y -> range(0, y))
    .foldLeft((acc, n) -> acc + n, 0)

  **Compiled Pseudo-Assembly:**
  // Registers allocated via State Flattening
  int r_outer_idx = 0;      // Outer Array Index
  bool r_inner_active = 0;  // Maybe Tag
  int r_inner_current = 0;  // Range State (Current)
  int r_inner_limit = 0;    // Range Limit (Captured from map)
  int r_acc = 0;            // Fold Accumulator

  LOOP_START:
    if (r_inner_active == 0) {
      // -- Outer Loop --
      if (r_outer_idx >= arr.length) goto DONE;
      
      int x = arr[r_outer_idx];
      r_outer_idx++; // Advance outer
      
      // -- Map Logic --
      int y = x + 1;
      
      // -- FlatMap Init (Range) --
      r_inner_current = 0;
      r_inner_limit = y;
      r_inner_active = 1;
      goto LOOP_START; // Skip
    } 
    else {
      // -- Inner Loop --
      if (r_inner_current >= r_inner_limit) {
        r_inner_active = 0; // Inner Stop
        goto LOOP_START;    // Skip back to outer
      }
      
      int n = r_inner_current;
      r_inner_current++; // Advance inner
      
      // -- Fold Logic --
      r_acc = r_acc + n;
      goto LOOP_START;
    }

  DONE:
    return r_acc;

  ## 7. Limitations & Constraints
  1.  Finite Types: The compiler must be able to deduce the size of the state tuple at compile time.
  2.  Side Effects: The `step` function assumes strictly sequential execution.
  3.  Recursion: Recursive `flatMap` requires explicit stack management if not tail-recursive.
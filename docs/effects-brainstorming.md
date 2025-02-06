# Design Summary: A Static, Type-Directed Effect System with Controlled Continuations

This design proposes a unified, statically-checked system for handling effects, combining ideas from protocols/type classes and row polymorphism while keeping advanced control flow hidden from the user. Below is a synopsis of the key design decisions and the intended syntax.

## Key Design Decisions

### Static Effect Tracking:

#### Explicit or Inferred Types:
Types (including effect annotations) can be explicitly declared (e.g., open_file (name: String) -> FFI Int) or inferred by the compiler. This ensures that effects are first-class citizens in the type system.
Unified Effects & Protocols:

Effects are defined similarly to protocols. For example, you might declare an effect as:

```mml
# effect FileIO implies FFI (an FFI effect and a handler need to exist)
effect FileIO <: FFI =
   fn openFile(name: String): Int  // Syntactic sugar for type: {FileIO} Int
   # Additional file I/O operations...
}
```



Handlers (instances) are provided for different platforms (e.g., glibc-x86, js-browser), ensuring that the concrete implementation is resolved via static instance resolution.


#### Row Polymorphism

Row polymorphism is employed to track multiple effects in a single, unified type. This avoids the need for deeply nested monad transformers and simplifies effect composition.

#### Syntactic Sugar with Controlled Continuations:

Effectful computations are written in direct style using a delimited do ... ; block.
Inside this block, the operator := is used for effectful operations. Internally, these may be implemented using continuations (capturing the rest of the computation) while hiding these details from the user.

A break keyword is available within the do block to allow for early exit from the computation, similar to early return or short-circuit operators (like Rust’s ?).

#### Selective Advanced Control Flow

Most effects are tracked statically, preserving referential transparency in the majority of the code.
Only a select subset of effects—if explicitly marked—can manipulate control flow (e.g., altering the stack). Such advanced features can be implemented via compile-time AST rewriting, ensuring that complexity remains encapsulated.
Robust Error Messaging:

Recognizing that error reporting is challenging at all levels (parsing, type inference, effect tracking), the system is designed to provide clear, actionable error messages, even as additional layers (such as row polymorphism) are introduced.

### Syntax Synopsis and draft rules

Operators within the do-block:
  - `:=` indicates an effectful call (internally may use continuations), establishes a boundary.
  - `break` allows an early exit to the last `:=` before the current one
      or the `do` block if there is no `:=` to jump to.
  - `throw` allows an early non local exit all the way to the next `catch` block not stoping at the `do` block boundary.
  - `catch` allows to catch a thrown value, a `catch` just after a `do` prevents bubbling.
    - `catch {'E}` can be used to catch thrown value of type `E`.

Inference for thrown values works the same as multi branch functions or pattern matching where the type is the unification, if possible or the union of all possible types.


```mml

type Error = String;

# Effect Declaration (similar to a protocol)
# Effect types are identified by the {},
# this way `{IO} List String` is clearer thatn `IO List String`.
# Effects, if written explicitely, are listed within {}
effect FileIO <: FFI, Throw  =
   fn open_file(path: String): Int    | Error         # Sugar for {FileIO & Throw} Int | Error
   fn read_file(handle: Int):  String | Error         # Sugar for {FileIO & Throw} String | Error
   # ... other file I/O functions
;

#-  a record to hold configuration -#
type Config = {
  # ...
}

#-
Referentially transparent, aka pure function: just takes a string and returns a Config.
Same input, same output
-#
fn parseStringConfig(path: String): Config = ???;

# Effectful Computation using a 'do' block:
fn readConfig (path: String) : {FileIO & Throw }  Config | Error = do
   fileHandle := open_file path;  // 'fileHandle' obtained via an effectful operation
   # match on the result
   let data := fileHandle match
      # Early exit from the 'do' block, returning the value passed to throw
      | Error s -> throw Error "Something went wrong: " ++ s
      | h       -> configContent := read_file h
   ;
   parseStringConfig data
;
```

```mml
fn try_reading_file(path: String): {FileIO & Throw & Cell} String | Error = do
   # mutable cell, also an effect
   let counterMut := mut 0;
   # unpacking the counter sets an effect boundary `:=`,
   # break will jump back here
   let count := get counterMut;
   # check the count; if too big, throw
   if count > 3 then
      # breaks to outermost `do`
      throw Error "Too many tries"
   else
      open_file(path) match
         # breaks to last `:=`
         | Error s -> break incr counterMut;
         | # ok here we continue
           # and read the file into a string
           ???
   ;
;
```


Summary
This design leverages static, type-directed effect tracking with row polymorphism to maintain clarity, modularity, and safety.

Effects are made explicit in types (either inferred or annotated), and a unified system allows for both pure protocols and effectful
operations.

Advanced control flow is managed within delimited do blocks—using := for effectful calls and throw and break for early exits—with any underlying continuations abstracted away from the user.

The resulting system minimizes boilerplate and the complexity of monadic stacks while providing robust,
developer-friendly error reporting and safe effect management.

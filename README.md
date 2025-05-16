# MinnieML

A statically typed, functional language, with an ML inspired syntax, a rich type system,
declarative low level capabilities and first class native interface.

## Why

This is an experiment to find out if I can build a high-level functional language that compiles quickly into fast executables 
with strong machine empathy, and good, modern tooling.

It's all about exploring ideas and having fun while learning.

## Fair warning

    "El que avisa no traiciona"

      -- Spanish for "Forewarned is forearmed"

    Or literally "He who warns you, does not betray you".

This is not something you want to use, I'm making this public to share with friends, so if you continue, hello friend!

## Legalese

This is not at all connected to my employer's IP, work, interests, opinions or anything else.

## Synopsis

Here's a small syntax showcase

```rust
    # Value Binding
    let a = 1;
    let b = 2;
    # Abstraction
    fn times (a b) = a * b;
    # Application
    let t1 = times 1 2;
    let t2 = times a b;
```

## Person/Pet example

A more complex example:

- types start with an uppercase letter, mandatory (Maybe, String, Int)
- type variables start with a ' and an uppercase letter ('T, 'R, 'In, 'Out)
- `#` is a line comment
- `()` reads as `unit`; it's like void ... (ish)
- note the infered types in the comments

Note that this examples might not be possible to compile currently.
See the STATUS entry below.

```rust
enum Maybe 'T =
  | One 'T
  | None
;

# empty :: Maybe 'T -> Boolean
fn empty (x) =
  x match
    | None  -> true
    | _     -> false
;

# nonEmpty :: Maybe 'T -> Boolean
fn nonEmpty (maybe) =
  not empty maybe
;

enum Species =
  | Cat
  | Dog
  | Bird
  | Fish
  | Reptile
;

data Pet {
  name:     String
  species:  Species
};

data Person {
  name: String
  pet:  Maybe Pet
};

# hasPet :: 'T : { pet: Maybe Pet } -> Boolean
fn hasPet (p) =
  nonEmpty p.pet
;

# nameOf :: 'T : { name: String } -> String
fn nameOf (p): String =
  p.name
;

let zur     = Pet     "Zur"     Species.Cat;
let fede    = Person  "Fede"    One zur;
let victor  = Person  "Victor"  None;

let pv = hasPet victor; # false
let pf = hasPet fede;   # true

# does not compile
# let pz = hasPet zur
```


## Status

At this time, MinnieML's parser handles a subset of the planned syntax, including function definitions, operators (symbolic and alphabetic like 'and'), and basic expressions. The semantic analysis phase includes a unified expression rewriting system that handles precedence and associativity for both custom operators and ML-style curried function application (e.g., `f a b` is rewriten as `(f a) b`). This phase also performs reference resolution, contextual disambiguation, and basic error checking.

The compiler integrates with native C code via `@native` annotations, linking against a small runtime library.

The focus right now is on getting the minimal set of features working so that we can compile simple but useful programs, 
even without features like pattern matching or type inference.

* Basic type checking (requires explicit types, resolves type definitions)
* Convert operator expressions to curried function calls.
* Some form of basic memory management.
* Module support: ability to compile separate files and reference them.
    * Only the Prelude for now.
* Add a basic standard library with some useful functions.
* Introduce tail call detection, have the codegen annotate the ir so llvm optimizes them.
* Improve codegen to work with the curried function calls for operators.
* Remove hardcoded types and assumptions from the codegen, using the @native keyword and type annotations. 

## Documentation

No.

There are some articles about how I deal with custom operators, expressions and function application.

- [Custom Operators](docs/articles/2025-02/2025-02-24-custom-operators.md) is a bit older, read it first.
- [Expression Rewriting](docs/articles/2025-04/2025-04-12-expression-rewriting.md) is a bit newer, read it for an update on the first one.

There [are some notes and draft code](docs) in an approximation of the syntax
that might give you an idea of where this is going.

Finally, you can look at the [tests](modules/mmlc-lib/src/test/scala/mml/mmlclib) and [current ast](modules/mmlc-lib/src/main/scala/mml/mmlclib/ast) and [parser](modules/mmlc-lib/src/main/scala/mml/mmlclib/parser) source code.

:shrugs:

## Setup

I have only tested things work on `mac os Sequoia`.

I have tested building the native compiler image on `Sequoia` `x86` and `aarch64` and on
whatever version is used on the docker image currently used - please look at the `docker-compose` file and the packaging `docker` folder.

Use sdkman to install

- `sbt` version `1.10.11` or above.
- `java` version `21.0.6-graal`. I guess higher works, but have not tried.

Clone the repo, et voila.

## Running

Indeed!

In mac os you will need `llvm` and `clang`.

I get `llvm` with `homebrew` so install that if you don't have it.
The compiler will guide you and ask you to install llvm when it needs it.

I think mac os will ask you to install the `xcode` command line tools
if I try to use them. You can google how to if that does not work.

`sbt "run --help"` if you want to run from sources.

Or build the native image:

`sbt "mmlcDistro"`

This will build the native image using graal's `native-image` command, and copy it and the shell script to `$HOME/bin`. You need to add that directory to the path or just write the full path to inveke it if you're just trying it.

You can then run the following to get some information about the tools the compiler found.

`mmlc info -d`

or run

`mmlc -h` for a synopsis of the commands available.

There are some samples under `mml/samples`.


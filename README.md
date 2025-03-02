# MinnieML

A statically typed, functional language, with an ML inspired syntax, managed effects in delimited contexts,
declarative low level capabilities and first class native interface.

## Fair warning

    "El que avisa no traiciona"

      -- Spanish for "Forewarned is forearmed"

    Or literally "He who warns you, does not betray you". Alternatively, "I told you!"

This is not something you want to use, I'm making this public to share with friends, so if you continue, hello friend!

## Legalese

This is not at all connected to my employer's IP, work, interests, opinions or anything else.

## Status

At this time, I am only parsing a subset of the language, there is no typechecker, and expressions are a flat
sequence of literals and identifiers.
There is only a very rudimentary interpreter and a simplistic llvm ir emitter, plus a shell script to **try** and compile that ir, after a bit of copy pasting.

I am using Scala 3 and Fast Parse 2, until I reach self hosting ;) .

While I don't even have half of the grammar nailed, I have built a [full vertical slice](https://gist.github.com/fedesilva/d8ba764114553a6736a5b22c154bb067).

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

Note the semi colons. For now they are mandatory - they keep the parser fast and simple
because they avoid some backtracking. I am thinking if I should add significant indentation in some cases.

## Person/Pet example

A more complex example:

- types start with an uppercase letter, mandatory (Maybe, String, Int)
- type variables start with a ' and an uppercase letter ('T, 'R, 'In, 'Out)
- `#` is a line comment
- `()` reads as `unit`; it's like void ... (ish)
- note the infered types in the comments

```rust
enum Maybe 'T =
  | One 'T
  | None

# empty :: Maybe 'T -> Boolean
fn empty (x) =
  x match
    | None  -> true
    | _     -> false

# nonEmpty :: Maybe 'T -> Boolean
fn nonEmpty (maybe) =
  not empty maybe

enum Species =
  | Cat
  | Dog
  | Bird
  | Fish
  | Reptile

type Pet = {
  name:     String
  species:  Species
}

type Person = {
  name: String
  pet:  Maybe Pet
}

# hasPet :: 'T : { pet: Maybe Pet } -> Boolean
fn hasPet (p) =
  nonEmpty p.pet

# nameOf :: 'T : { name: String } -> String
fn nameOf (p): String =
  p.name

let zur     = Pet     "Zur"     Species.Cat
let fede    = Person  "Fede"    One zur
let victor  = Person  "Victor"  None

let pv = hasPet victor # false
let pf = hasPet fede   # true

# does not compile
# let pz = hasPet zur
```

## Documentation

There is, of course, no documentation.

For a while I was using antlr4, and while I am moving away from it, [that grammar](modules/mmlc-lib/src/main/antlr4/MinnieML.g4) remains the reference for now.

There [are also some notes and draft code](docs) in an approximation of the syntax
that might give you an idea of where this is going.

Finally, you can look at the [tests](modules/mmlc-lib/src/test/scala/mml/mmlclib/grammar) and [current ast](modules/mmlc-lib/src/main/scala/mml/mmlclib/ast) and [parser](modules/mmlc-lib/src/main/scala/mml/mmlclib/parser) source code.

:shrugs:

## Running

No, not, yet.

But soon, now. Really.

¬¬

## Why

I loathe the notion of domain specific languages, I'd like to learn a lang, its stdlib and use
it to build webservers, websites, microservices, monoliths, kernel modules, wikis, embedded systems, mobile apps, desktop apps, shell scripts, etc.

A list is a list, a string is a string, some platforms might not understand what a file or a thread is - the language and build tool need to know about this and help me.

From edge to cloud, from mobile to homes, and when you are sitting with your laptop - one lang to rule them all.

I want a very high level language that gives me declarative(ish?) access to low level stuff IF I need it, but does not force it on every little program.

It's all about the fun and learning and exploring ideas.

## Typechecking the Person/Pet example.

```
# Let's begin type-checking `fn empty x = x match | () -> true | _ -> false`.
# We see that `x match` has two cases:
#   1) If x is the constructor `()`, return `true`.
#   2) Otherwise `_`, return `false`.
# Recall that `type Maybe 'T = One 'T | None;`
# So `()` here aligns with the "nothing" variant of Maybe.
# This means `x` must be a value of type `Maybe 'T` for some type variable `'T`.
# Both branches return a Bool, so the result of `empty` is Bool.
#
# Hence: empty :: Maybe 'T -> Bool
fn empty (x) =
  x match
    | None -> true
    | _  -> false


# Next, `fn nonEmpty maybe = ! empty maybe`.
# `empty maybe` must return a Bool, so `maybe` must be the same type that `empty` expects:
# namely `Maybe 'T` for some type variable `'T`.
# Then we apply '!' (logical not) to that Bool, so the result is also a Bool.
#
# Hence: nonEmpty :: Maybe 'T -> Bool
fn nonEmpty (maybe) =
  ! empty maybe


enum Species =
  | Cat
  | Dog
  | Bird
  | Fish
  | Reptile

type Pet = {
  name:     String
  species:  Species
}

type Person = {
  name: String
  pet:  Maybe Pet
}


# Now let's look at `fn hasPet p = nonEmpty p.pet`.
# We see p.pet must be a `Maybe Pet`, so `p` must be of type `Person` (since Person defines a `pet: Maybe Pet`).
# Then we feed `p.pet` to `nonEmpty`, returning Bool.
#
# Hence: hasPet :: Person -> Bool
fn hasPet (p) =
  nonEmpty p.pet


# Now `fn nameOf (p): String = p.name`.
# We have an explicit annotation for the return type: `: String`.
# The only requirement for `p` is that it has a field `.name` of type String.
# In many HM + row-polymorphism systems, we could say
#    nameOf :: { name: String | r } -> String
# for some row `r`.
# But if we assume the simplest usage (and possibly no direct row-polymorphic syntax),
# we can say `p` is at least a record with a `name: String`.
# If you want it strictly for `Person`, you can say `Person -> String`.
# In a more general row-polymorphic sense: `{ name: String, ... } -> String`.
#
# We'll show the row-polymorphic style in the comment which is the way we are going.
# In practice this will be shown as `{ name: String }`, the row will be implicit.
#
# nameOf :: { name: String | 'R } -> String
fn nameOf (p): String =
  p.name


# Let us examine the rest:
let zur     = Pet     "Zur"     Species.Cat      # zur :: Pet
let fede    = Person  "Fede"    One zur          # fede :: Person
let victor  = Person  "Victor"  None             # victor :: Person

let pv = hasPet victor  # pv :: Bool  (evaluates to false)
let pf = hasPet fede    # pf :: Bool  (evaluates to true)

# Finally, `let pz = hasPet zur` would fail to compile
# because `hasPet` expects a Person, but `zur` is a Pet.
#
# Summary of inferred types:
# empty    :: Maybe 'T -> Bool
# nonEmpty :: Maybe 'T -> Bool
# hasPet   :: Person -> Bool
# nameOf   :: { name: String | 'R } -> String   # row-polymorphic variant
#              (or simply Person -> String if you want to fix it)

```


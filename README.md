# MinnieML

A statically typed, functional language, with an ML inspired syntax, 
a rich type system, great ergonomics, 
modern tooling and strong mechanical sympathy.

## Why

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
    // Value Binding
    let a = 1;
    let b = 2;
    // Abstraction
    fn times (a b) = a * b;
    // Application
    let t1 = times 1 2;
    let t2 = times a b;
```

## Person/Pet example

A more complex example:

- types start with an uppercase letter, mandatory (`Maybe`, `String`, `Int`)
- type variables start with a ' and an uppercase letter (`'T`, `'R`, `'In`, `'Out`)
- `//` is a line comment 
- `()` reads as `unit`; it's like void ... (ish)
- the `Maybe` type denotes we can have one value of type `'T` or None.
- note the infered types in the comments

This examples might not be possible to compile currently, depending on when you read this.

```rust
enum Maybe 'T =
  | One 'T
  | None
;

// empty :: Maybe 'T -> Boolean
fn empty (maybe) =
  x match
    | None  -> true
    | _     -> false
;

// nonEmpty :: Maybe 'T -> Boolean
fn notEmpty (maybe) =
  not empty maybe
;

enum Species =
  | Cat
  | Dog
  | Bird
  | Fish
  | Reptile
;

struct Pet {
  name:     String,
  species:  Species
};

struct Person {
  name: String,
  pet:  Maybe Pet
};

// hasPet :: 'T: { pet: Maybe 'P } -> Boolean
fn hasPet (p) =
  nonEmpty p.pet
;

// nameOf :: 'T : { name: String } -> String
fn nameOf (p): String =
  p.name
;

let zur     = Pet     "Zur"     Species.Cat;
let fede    = Person  "Fede"    One zur;
let victor  = Person  "Victor"  None;

let pv = hasPet victor; // false
let pf = hasPet fede;   // true
<<<<<<< HEAD
=======

>>>>>>> 3fb3d01 (Fix comment syntax in README.md)

// does not compile
// let pz = hasPet zur
```



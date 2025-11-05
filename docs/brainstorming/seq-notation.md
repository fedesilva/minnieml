# Sequence Literals in MinnieML

## Definition and Default Behavior

Sequence literals in MinnieML (seq notation) provide a concise way to define fixed-length collections. By default, they create Arrays with contiguous memory storage:

```
let a1 = [1];  # Creates an Array Int with one element
```

## Empty Arrays and Type Inference

Empty arrays require type information since the compiler cannot infer the element type:

```
let x0 = [];  # Warning: Type becomes Array Nothing (bottom type)
              # This is usually a mistake and can trigger a fatal error if `--fatal-warnings1 is on.
```

MinnieML offers two solutions:

1. **Explicit type annotation:**
   ```
   let x0 : Array Int = [];
   ```

2. **Type notation (idiomatic):**
   ```
   let x1 = Int [];
   ```

You read this as `Int Array`, compiles to `Array Int`


## Alternative Container Types

While Arrays are the default, other container types can be specified:

```
let x2 = List [1];         # Creates a List Int
let ns: List Int = [1, 2, 3]; # Type annotation makes this a List
let ns = List [1, 2, 3];     # Explicitly creates a List Int
```

## Pattern Matching

MinnieML enables pattern matching against sequences:

```
fn matchList l =
  l match
    | []                -> "empty"
    | [a]               -> a
    | [a b]             -> a + b
    | [ head :: tail ]  -> a
```

The cons operator `[::]` is a pattern matching tool that extracts the first element (head) and the remaining elements (tail) from a sequence. 
This enables recursive processing of collections and is fundamental to many functional programming algorithms.

## Desugaring Process

MinnieML transforms sequence literals through a two-step process:

1. Create an Array from the sequence literal
2. If needed, convert the Array to another container type

Container types must implement the `SeqDesugar` protocol:

```
protocol SeqDesugar 'C : Monad =
  fn desugar 'T: (Array 'T) -> ('C 'T)
;
```

## Rules of Desugaring

MinnieML follows these specific rules when processing sequence literals:

### Trigger Clause:
A sequence literal is used in an expression.

### Simple Sequence Case:
Simple sequence with literal elements: The inferred type is `Array 'T`, where `'T` is the type of the elements.

Example: `let xs = [1 2 3];` (Desugars to Array Int).

### Inference Case:
If the type of the elements can be inferred (ascription, flow, or unification): The type is `Array 'I`, where `'I` is the inferred type.

Example: `let xs: Array Int = []` (Ascription: Desugars to Array Int).
Example: `let ys = [] ++ [1, 2, 3]` (Inference: Desugars to Array Int for both)

### Empty Sequence with Type Annotation Case:
The desugared type is Array 'T, where 'T is the type of the annotation.

Example: `let xs = [] Int` (Desugars to Array Int).

### Type Constructor Reference with Sequence Case:
If the type constructor refers to a type with an instance of SeqDesugar:
- The container type is the type with a SeqDesugar instance.
- Rules 1, 2 and 3 apply within the context of the Sequence type.

Example: `let xs = List [1 2 3]` (Desugars to List Int).
Example: `let ys = Vector [1 2 3]` (Desugars to Vector Int).
Example: `let zs = Vector Int []` (Desugars to Vector Int with an empty list).          

### Untypable Empty Sequence Case:
If an empty sequence is encountered and the type of the element can't be inferred:
- The desugared type is Array Nothing.
- This raises a warning indicating an empty sequence with no type hint.
- The warning can be configured to result in a fatal compile error.


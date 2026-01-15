# Row Polymorphism Extensions: Unions and Intersections

## Relation to Core Design

This document extends the two-phase type inference design described in the main document. It shows how unions and intersections emerge naturally from the row polymorphism machinery already designed for records.

**Key insight**: Records and unions are dual. The same row variable mechanism that handles "at least these fields" for records handles "at most these variants" for unions. Intersections are just record field merging—no additional mechanism needed.

## The Duality

Records (Product):   { x: Int, y: String | ρ }  "at least these fields"
Unions (Sum):        < `A(Int) | `B(String) | ρ >  "at most these variants"

Both use row variables (`ρ`) to capture "the rest." The constraint system treats them symmetrically.

## Type Representations

### TypeRecord (Already Designed)

    case class TypeRecord(
      span: SrcSpan,
      fields: List[(String, TypeSpec)],
      rowVarId: Int  // reference to UnificationVar
    ) extends TypeSpec

**Closed record** (nominal): `rowVarId = EmptyRow` (sentinel value, e.g., -1)
**Open record** (structural): `rowVarId = ?ρ` (fresh unification var)

### TypeUnion (New, Mirrors TypeRecord)

    case class TypeUnion(
      span: SrcSpan,
      variants: List[(String, TypeSpec)],
      rowVarId: Int  // reference to UnificationVar
    ) extends TypeSpec

**Closed union** (nominal ADT): `rowVarId = EmptyRow`
**Open union** (polymorphic variant): `rowVarId = ?ρ`

Examples:

    // Nominal enum (closed):
    enum Result[T, E] {
      Ok(T),
      Err(E)
    }
    // Result[Int, String] = TypeUnion([("Ok", Int), ("Err", String)], EmptyRow)

    // Structural union (open):
    let handle = fn x: Int | String => match x { ... }
    // Int | String = TypeUnion([("Int", Int), ("String", String)], ?ρ)

## Constraint Extensions

### HasField (Already Designed)

    case HasField(
      recordId: Int,       // the record type
      fieldName: String,
      fieldTypeId: Int,    // type of the field
      restRowId: Int,      // row variable for remaining fields
      span: SrcSpan
    )

### HasVariant (New, Mirrors HasField)

    case HasVariant(
      unionId: Int,        // the union type
      variantName: String,
      variantTypeId: Int,  // type of the variant payload
      restRowId: Int,      // row variable for remaining variants
      span: SrcSpan
    )

## When Constraints Are Emitted

### Records (Field Access)

    let f = fn x => x.name

SimpTyper:
1. `x` has unknown type → create `?0`
2. Accessing `.name` → create `?1` (field type), `?ρ₁` (rest row)
3. Emit: `HasField(?0, "name", ?1, ?ρ₁, span)`
4. Result type: `?1`

### Unions (Pattern Match)

    let g = fn x => match x {
      `Int(n) => n
      `String(s) => 0
    }

SimpTyper:
1. `x` has unknown type → create `?0`
2. Matching `` `Int`` → create `?1` (variant type), `?ρ₁` (rest row)
3. Emit: `HasVariant(?0, "`Int", ?1, ?ρ₁, span1)`
4. Matching `` `String`` → create `?2` (variant type), `?ρ₂` (rest row)
5. Emit: `HasVariant(?0, "`String", ?2, ?ρ₂, span2)`
6. SeqTyper unifies `?ρ₁` and `?ρ₂` (both point to same `?0`)

### Multiple Accesses (Same Record)

    let h = fn x => (x.name, x.age)

SimpTyper:
1. Create `?0` for `x`
2. Emit: `HasField(?0, "name", ?1, ?ρ₁, span1)`
3. Emit: `HasField(?0, "age", ?2, ?ρ₂, span2)`
4. SeqTyper sees both constraints on `?0`, unifies `?ρ₁` and `?ρ₂`

## Row Unification Algorithms

### Record Unification (Already Designed)

    unifyRecords(r1: TypeRecord, r2: TypeRecord):
      commonFields = r1.fields ∩ r2.fields (by name)
      for (name, t1, t2) in commonFields:
        unify(t1, t2)  // field types must match

      extraInR1 = r1.fields - commonFields
      extraInR2 = r2.fields - commonFields

      if extraInR1.isEmpty && extraInR2.isEmpty:
        unify(r1.rowVarId, r2.rowVarId)
      elif extraInR1.isEmpty:
        unify(r1.rowVarId, TypeRecord(extraInR2, r2.rowVarId))
      elif extraInR2.isEmpty:
        unify(r2.rowVarId, TypeRecord(extraInR1, r1.rowVarId))
      else:
        ?fresh = freshRowVar()
        unify(r1.rowVarId, TypeRecord(extraInR2, ?fresh))
        unify(r2.rowVarId, TypeRecord(extraInR1, ?fresh))

Example:

    { x: Int | ?ρ₁ } ~ { x: Int, y: String | ?ρ₂ }
    → unify(?ρ₁, { y: String | ?ρ₂ })

### Union Unification (New, Mirrors Record)

    unifyUnions(u1: TypeUnion, u2: TypeUnion):
      commonVariants = u1.variants ∩ u2.variants (by name)
      for (name, t1, t2) in commonVariants:
        unify(t1, t2)  // variant payload types must match

      extraInU1 = u1.variants - commonVariants
      extraInU2 = u2.variants - commonVariants

      if extraInU1.isEmpty && extraInU2.isEmpty:
        unify(u1.rowVarId, u2.rowVarId)
      elif extraInU1.isEmpty:
        unify(u1.rowVarId, TypeUnion(extraInU2, u2.rowVarId))
      elif extraInU2.isEmpty:
        unify(u2.rowVarId, TypeUnion(extraInU1, u1.rowVarId))
      else:
        ?fresh = freshRowVar()
        unify(u1.rowVarId, TypeUnion(extraInU2, ?fresh))
        unify(u2.rowVarId, TypeUnion(extraInU1, ?fresh))

Example:

    < `Int(Int) | ?ρ₁ > ~ < `Int(Int) | `String(String) | ?ρ₂ >
    → unify(?ρ₁, < `String(String) | ?ρ₂ >)

**Key observation**: The algorithm is **identical**, just swap "fields" for "variants". The unification engine can be parameterized.

## Intersection Types (No Extension Needed!)

Intersections are just multiple record fields merged:

    trait Readable { read: () -> String }
    trait Writable { write: String -> () }

    let process = fn x: Readable & Writable => 
      x.read() ++ x.write("hello")

Desugars to:

    let process = fn x: { read: () -> String, write: String -> () | ?ρ } =>
      x.read() ++ x.write("hello")

**No new types or constraints needed.** Intersections are record type merging, which row polymorphism already supports.

### Intersection Syntax Desugaring

    // User writes:
    fn x: A & B => ...

    // Parser desugars to:
    fn x: { ...fieldsOfA, ...fieldsOfB | ?ρ } => ...

If `A` and `B` have conflicting fields, that's a type error (fields with same name must have same type).

## Syntax Examples

### Records

    // Nominal (closed):
    data Person { name: String, age: Int }
    let p: Person = Person { name: "Alice", age: 30 }

    // Structural (open):
    let greet = fn x: { name: String } => "Hello, " ++ x.name
    greet(p)  // ✓ Person has name field

### Unions

    // Nominal (closed):
    enum Option[T] {
      Some(T),
      None
    }
    let x: Option[Int] = Some(42)

    // Structural (open):
    let handle = fn x: Int | String => match x {
      `Int(n) => n
      `String(s) => 0
    }

**Note**: Structural union syntax `Int | String` is sugar for polymorphic variants:

    < `Int(Int) | `String(String) | ?ρ >

### Intersections

    // Using traits:
    trait Drawable { draw: () -> () }
    trait Clickable { onClick: () -> () }

    let widget = fn x: Drawable & Clickable => {
      x.draw();
      x.onClick()
    }

Desugars to:

    let widget = fn x: { draw: () -> (), onClick: () -> () | ?ρ } => ...

## Implementation Timeline Addition

After the core constraint system is working (main document, steps 1-7):

**8. Add TypeUnion and HasVariant constraint**
   - Mirror TypeRecord/HasField design
   - Extend row unification to handle unions
   - Test with hand-written constraints

**9. Add union syntax to parser**
   - `Int | String` desugars to `TypeUnion`
   - Polymorphic variant syntax: `` `Tag(payload) ``
   - Pattern match on unions

**10. Add intersection syntax (optional)**
   - `A & B` desugars to merged TypeRecord
   - Verify field name conflicts produce errors
   - Test with trait-like usage

## Why No Subtyping Is Still True

Even with unions and intersections, **no subtype relations exist**. Everything is unification + row polymorphism:

    let f: { x: Int } -> Int = fn r => r.x
    let g: Int | String -> Int = fn u => match u { ... }

    // These are NOT subtype checks:
    f(Person { x: 1, y: 2 })  // ✓ unifies via row variable
    g(`Int(42))               // ✓ unifies via row variable

There's no "`Person <: { x: Int }`" relation. Instead:
- `Person` = `{ x: Int, y: Int | EmptyRow }`
- `{ x: Int }` = `{ x: Int | ?ρ }`
- Unification solves: `?ρ = { y: Int | EmptyRow }`

Same mechanism for unions—no "`` `Int <: Int | String``", just row unification.

## Error Messages

With unions and intersections, error messages follow the same three-part pattern:

    Type mismatch: union missing variant
    Phase: mml.mmlclib.semantic.SeqTyper
    Variable ?0 introduced at [5:5]-[5:6]:
       5 | let x = ...
    Constrained to < `Int(Int) | `String(String) | ?ρ > at [7:15]-[7:30]:
       7 | match x { `Int(n) => ... | `String(s) => ... }
    Required to have variant `Bool at [9:11]-[9:16]:
       9 | let y = x as `Bool(true)
                 ^^^^^

## Generalization With Rows

Level-based generalization works identically for record rows and union rows:

    let makeAccessor = fn field => fn record => record.{field}
    // Inferred: ∀α ρ. String -> { field: α | ρ } -> α
    //                           ^^^^^^^^^^^^^^^^^^^
    //                           row-polymorphic record type

Row variables generalize like type variables—if their level is younger than the binding, they become `∀`-quantified.

## Summary

| Feature | Type Representation | Constraint | New Machinery? |
|---------|-------------------|------------|----------------|
| Records | `TypeRecord` | `HasField` | ✗ (core) |
| Unions | `TypeUnion` | `HasVariant` | ✓ (mirrors records) |
| Intersections | `TypeRecord` (merged) | `HasField` | ✗ (already works) |

**Key principle**: One row mechanism, three applications. No subtyping, no variance, no coercion. Just unification with row variables.

The constraint system designed in the main document is **already flexible enough** for all three. Unions require mirroring the record machinery; intersections require nothing at all.
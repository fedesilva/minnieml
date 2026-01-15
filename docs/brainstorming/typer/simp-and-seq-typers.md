# Two-Phase Type Inference: SimpTyper and SeqTyper

## Motivation

The current type checker is monomorphic and bidirectional. We want to:

1. **Keep monomorphic inference for parallelism**: Each module can be typed independently
2. **Support polymorphism globally**: Let-generalization, instantiation at call sites
3. **Improve error messages**: Simpler local errors from SimpTyper,
   complex cross-module errors from SeqTyper
4. **Scale to large programs**: Parallel first pass means most "anchor" types
   are solved before the sequential global pass

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  Phase 1: Parallel (per module)                             │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐                      │
│  │Module A │  │Module B │  │Module C │  ...                 │
│  │SimpTyper│  │SimpTyper│  │SimpTyper│                      │
│  └────┬────┘  └────┬────┘  └────┬────┘                      │
│       │            │            │                           │
│       ▼            ▼            ▼                           │
│   AST with     AST with     AST with                        │
│   UnificationVars + ConstraintSet per module                │
└─────────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 2: Sequential (global)                               │
│  ┌─────────────────────────────────────────┐                │
│  │              SeqTyper                   │                │
│  │  - merges all module ConstraintSets     │                │
│  │  - solves remaining constraints         │                │
│  │  - infers polymorphic types             │                │
│  │  - unifies cross-module references      │                │
│  │  - rewrites UnificationVars → types     │                │
│  └─────────────────────────────────────────┘                │
└─────────────────────────────────────────────────────────────┘
```

### Data Flow (Refined)

```
[Source A] ──► SimpTyper ──► [AST_A + Constraints_A] ──┐
[Source B] ──► SimpTyper ──► [AST_B + Constraints_B] ──┼──► SeqTyper ──► [Solution Map]
[Source C] ──► SimpTyper ──► [AST_C + Constraints_C] ──┘         │
                                                                 ▼
                                                          (Apply Solution)
                                                                 │
                        ┌────────────────────────────────────────┼────────────────┐
                        ▼                                        ▼                ▼
                   [Codegen A]                              [Codegen B]      [Codegen C]
                   (parallel)                               (parallel)       (parallel)
```

After SeqTyper solves, codegen can run in parallel again because each module's
AST has concrete types (UnificationVars replaced by Solution Map).

## Decisions

### 1. New AST Types

#### UnificationVar

A placeholder for types that SimpTyper cannot solve locally.

```scala
case class UnificationVar(
  span:   SrcSpan,
  id:     Int,          // unique within module, e.g., ?0, ?1, ?2
  level:  Int           // scope depth at creation (for generalization)
) extends TypeSpec
```

- Created by SimpTyper when local inference fails
- Carries source span for error attribution
- `id` is module-local; SeqTyper remaps when merging
- `level` tracks scope depth: increments entering `let`, decrements exiting

**Level-based generalization**: When unifying `?A` (level 5) with `?B` (level 3),
result gets level 3 (outermost/lowest). When finishing a let-binding at level L,
variables with level > L can be generalized to `∀` vars. This avoids needing
AST structure in SeqTyper.

**Global ID encoding**: During merge, `GlobalID = (ModuleID << 32) | LocalID`
avoids hash lookups for variable remapping.

#### Constrained (Required, for provenance)

After SeqTyper solves, preserve where the type came from:

```scala
case class Constrained(
  span: SrcSpan,
  solved: TypeSpec,           // the actual resolved type
  originalVar: UnificationVar // for debugging/error messages
) extends TypeSpec
```

This is not optional - SeqTyper **could** directly replace `UnificationVar` with concrete types but
excellent error messages are a goal.

`Constrained` wrapper is useful for "this type was inferred from X, Y, Z" messages.

### 2. Constraint Representation

```scala
enum Constraint:
  // Equality constraints
  case Equals(uvarId: Int, other: TypeSpec, span: SrcSpan)
  case Unify(uvarId1: Int, uvarId2: Int, span: SrcSpan)  // ?A = ?B, both unknown

  // Structural constraints
  case HasField(
    recordId: Int,       // the record type (UnificationVar)
    fieldName: String,
    fieldTypeId: Int,    // type of the field (UnificationVar)
    restRowId: Int,      // the row variable for remaining fields
    span: SrcSpan
  )
  case Call(funcId: Int, argIds: List[Int], retId: Int, span: SrcSpan)

  // Cross-module linking
  case Import(uvarId: Int, moduleName: String, memberName: String, span: SrcSpan)

  // Instantiation (for polymorphic references)
  case Instantiate(resultId: Int, scheme: TypeScheme, span: SrcSpan)

case class ConstraintSet(
  constraints: Vector[Constraint],
  nextVarId: Int,     // for generating fresh unification vars
  currentLevel: Int   // current scope depth
)

object ConstraintSet:
  val empty = ConstraintSet(Vector.empty, 0, 0)
```

**Constraint spans**: Every constraint carries the source location where it was
emitted. This enables three-part error messages:
1. "Type variable introduced here" (UnificationVar.span)
2. "Constrained to X here" (Constraint.span)
3. "Conflicts with Y here" (another Constraint.span)

**Why `Call` is separate from `Equals`**: If we just emitted `Equals(funcId, TypeFn(args, ret))`
and `funcId` turned out to be `Int`, the unifier would say "Type Mismatch". With `Call`,
the solver can produce a specific error: "Type Int is not callable".

### 3. Where Constraints Live

| Phase | Location |
|-------|----------|
| SimpTyper | New field: `Module.constraints: Option[ConstraintSet]` |
| SeqTyper | Global state (design deferred until multi-module support) |

The `Module` AST node gets a new optional field to carry constraints from SimpTyper.

### 4. Rigid vs Unification Variables

Two distinct concepts:

- **TypeVariable** (`'T`, `'R`): Rigid, bound by a `∀`, cannot unify with concrete types
- **UnificationVar** (`?0`, `?1`): Flexible, placeholder to be solved

They are separate types in the AST. TypeVariable already exists; UnificationVar is new.

### 5. Cross-Module References

During SimpTyper on module A, when encountering `B.foo`:

- **If `B.foo` has type annotation**: Use the annotation (bidi works)
- **If no annotation**: Create `UnificationVar`, defer to SeqTyper

SeqTyper later unifies A's `?X` (representing B.foo) with B's actual type for `foo`.

### 6. Polymorphism Target

- **Let-generalization**: Functions get type schemes (∀ at binding site)
- **Instantiation at call sites**: Fresh unification vars replace quantified vars
- **Target**: Rank-2 (polymorphic types in function arguments)
- **Not prenex-only**: Eventually support higher-rank via bidirectional approach
  (Peyton Jones et al.), but out of scope for initial implementation

## Phase Behaviors

### SimpTyper (per-module, parallel)

**Input**: Parsed, ref-resolved, expression-rewritten Module

**Output**: Module with:
- `typeSpec` fields populated (concrete types or `UnificationVar`)
- `constraints: Some(ConstraintSet)` attached to Module

**Algorithm** (draft):

```
simpType(module):
  state = ConstraintSet.empty  # starts at level 0

  for each member in module.members:
    match member:
      case Bnd with Lambda (function/operator):
        # Enter new scope
        state = state.enterLevel()
        # Lower param ascriptions to specs (like current TypeChecker)
        # Check body with param context
        # If return type unknown and can't infer: create UnificationVar at current level
        state = state.exitLevel()

      case Bnd without meta (value binding):
        # Enter new scope for let-binding
        state = state.enterLevel()
        # Infer type from value expression
        # If can't infer: create UnificationVar at current level
        state = state.exitLevel()

      case TypeDef, TypeAlias:
        # Handled by TypeResolver, skip

  return module.copy(constraints = Some(state))

freshVar(state, span):
  var = UnificationVar(span, state.nextVarId, state.currentLevel)
  state = state.copy(nextVarId = state.nextVarId + 1)
  return (var, state)

inferExpr(expr, expectedType, state):
  match expr:
    case Ref(name):
      # Look up in param context or module
      # If found with type: return type
      # If found without type: create UnificationVar at current level
      # If cross-module ref without annotation:
      #   var = freshVar()
      #   emit Import(var.id, moduleName, name, span)
      #   return var

    case App(fn, arg):
      fnType = inferExpr(fn, None, state)
      match fnType:
        case TypeFn(paramTypes, returnType):
          argType = inferExpr(arg, paramTypes.head, state)
          # Check compatibility or emit constraint
          return remaining function type or returnType
        case UnificationVar(id, level):
          # fn is unknown, create vars for param and return at current level
          (paramVar, state) = freshVar(state, arg.span)
          (returnVar, state) = freshVar(state, expr.span)
          emit Call(id, [paramVar.id], returnVar.id, span)
          inferExpr(arg, paramVar, state)
          return returnVar
        case concrete type that's not a function:
          # Error: applying non-function

    case Cond(cond, ifTrue, ifFalse):
      # Check cond is Bool (or emit constraint)
      # Infer both branches
      # Unify branch types (or emit constraint)

    case LiteralInt, LiteralString, ...:
      # Return known type (anchors)

    case Hole:
      # If expectedType: use it
      # Else: create UnificationVar at current level
```

**Key difference from current TypeChecker**:
- Current: fails with error when type unknown
- SimpTyper: creates `UnificationVar`, emits constraint, continues

### SeqTyper (global, sequential)

**Input**: All modules with their `ConstraintSet`s

**Output**: All modules with `UnificationVar`s replaced by solved types

**Algorithm** (draft):

```
seqType(modules):
  # 1. Merge all constraint sets (remap var IDs to global namespace)
  #    GlobalID = (ModuleID << 32) | LocalID
  ctx = SolverContext.empty
  for (moduleId, module) in modules.zipWithIndex:
    for constraint in module.constraints:
      ctx = ctx.addConstraint(remapIds(moduleId, constraint))

  # 2. Resolve Import constraints
  #    Note: target module may also have UnificationVars if still being inferred
  for Import(uvarId, moduleName, memberName, span) in ctx.constraints:
    targetModule = modules.find(_.name == moduleName)
    targetType = targetModule.exports.get(memberName)
    match targetType:
      case TypeScheme(vars, body):
        # Polymorphic: emit Instantiate constraint
        ctx = ctx.addConstraint(Instantiate(uvarId, targetType, span))
      case UnificationVar(targetId, _):
        # Target not yet solved: link the variables
        ctx = ctx.addConstraint(Unify(uvarId, remap(targetId), span))
      case concrete:
        # Monomorphic concrete: direct equality
        ctx = ctx.addConstraint(Equals(uvarId, concrete, span))

  # 3. Solve constraints (unification with level tracking)
  substitution = solve(ctx)

  # Unification rule: when unifying ?A (level 5) with ?B (level 3),
  # result variable gets level 3 (promote to outermost scope)

  # 4. Generalize (let-polymorphism using levels)
  #    Important: iterate over AST bindings, not constraints
  #    Constraints are flattened; AST structure knows binding levels
  for module in modules:
    for binding in module.bindings:
      bindingType = substitution.apply(binding.typeSpec)
      freeVars = bindingType.freeUnificationVars.filter(_.level > binding.level)
      if freeVars.nonEmpty:
        # These vars are "younger" than the binding, safe to generalize
        scheme = TypeScheme(freeVars.map(toTypeVar), bindingType)
        binding.typeSpec = scheme

  # 5. Rewrite AST
  for each module:
    replace UnificationVars with Constrained(solved, originalVar)
    # Constrained wrapper is required for good error messages

  return updated modules

solve(ctx):
  # Union-find based unification
  for constraint in ctx.constraints:
    match constraint:
      case Equals(uvarId, typeSpec, span):
        unify(ctx.find(uvarId), typeSpec)
      case Unify(id1, id2, span):
        union(ctx.find(id1), ctx.find(id2))
        # Result level = min(level1, level2)
      case Call(funcId, argIds, retId, span):
        unify(ctx.find(funcId), TypeFn(argIds.map(ctx.find), ctx.find(retId)))
      case HasField(uvarId, field, fieldType, span):
        # Structural constraint - depends on type system design
      case Instantiate(resultId, scheme, span):
        # Create fresh vars for scheme's quantified vars
        freshened = instantiate(scheme)
        unify(ctx.find(resultId), freshened)
  return ctx.buildSubstitution()
```

**Unification algorithm**: Union-find based for efficiency. Level promotion on union.

## Open Questions

### Q1: Constraint Kinds [RESOLVED]

Current set: `Equals`, `Unify`, `HasField`, `Call`, `Import`, `Instantiate`.

**Design choice**: Row polymorphism, NOT subtyping.

**NO SUBTYPING.** This is a firm design decision. Subtyping introduces complexity
(variance, coercion, subsumption) that conflicts with MML's goals. Do not add
`SubtypeOf` constraints or subtyping rules. If you're reading this in the future
and thinking "maybe just a little subtyping..." - no. Use row polymorphism instead.

- `HasField` enables row polymorphism (structural typing via field constraints)
- Row polymorphic records first
- Row polymorphic effects later

**Structural constraints are open**: Structural type annotations like `{ name: String }`
are constraints, not record definitions. They require at least the specified fields but
allow additional fields — this is what "open" means. The row variable persists through
inference, representing the potential for extra fields.

**Records are nominal and closed**: Types defined with `data` (e.g., `data Person { ... }`)
are nominal record types with a fixed set of fields (EmptyRow). They are not open.
Records compile to C-like structs with fixed memory layout. The programmer sees exactly
what fields exist — no hidden extras. The compiler knows exact sizing and field offsets.
This is mechanical sympathy: the type definition reflects the machine representation.

```scala
case class TypeRecord(
  span: SrcSpan,
  fields: List[(String, TypeSpec)],
  rowVarId: Int  // reference to UnificationVar, resolved via substitution
) extends TypeSpec
```

Note: `rowVarId` is an Int reference, not the var itself. After SeqTyper solves,
the var might point to another var, a concrete extension, or empty row. Indirection
through substitution handles this.

**Nominal vs Structural types**:
- `data Person { name: String, profession: String }` → closed (EmptyRow sentinel)
- `{ name: String }` in type annotations → open (fresh row var)

```mml
data Person { name: String, profession: String }
# Person = TypeRecord([name, profession], EmptyRow)

let f = fn x: { name: String } => x.name
# { name: String } = TypeRecord([name], ?ρ)
```

When unifying Person with `{ name: String | ?ρ }`:
- Match common fields: name ✓
- Solve: `?ρ = { profession: String | EmptyRow }`

**Multiple field accesses**: SimpTyper emits separate HasField constraints with
separate row vars. SeqTyper unifies them when it discovers they're the same record.
```mml
let g = fn x => (x.name, x.age)
# Emits:
#   HasField(?0, "name", ?1, ?row1, span1)
#   HasField(?0, "age", ?2, ?row2, span2)
# SeqTyper unifies ?row1 and ?row2
```

Future:
- `Instance(uvar, typeClass, ...)` - for type classes (if added)
- `HasEffect(uvar, effectRow)` - same pattern for effects

### Q2: Error Attribution [RESOLVED]

Three-part errors:
1. UnificationVar.span → "Type variable introduced here"
2. Constraint.span → "Constrained to X here"
3. Conflicting Constraint.span → "Conflicts with Y here"

Both vars and constraints carry spans.

### Q3: Module.constraints Field [RESOLVED]

**`Option[ConstraintSet]`** is correct.

- Keeps parser clean (parsing shouldn't allocate constraint buffers)
- `None` before SimpTyper, `Some(...)` after
- Allows nullifying/GC after SeqTyper runs, freeing memory for backend
- Lightweight AST preserved, heavy constraints discarded

### Q4: Interaction with Existing Phases [RESOLVED]

**SimpTyper replaces TypeChecker.**

New pipeline:
```
Parser → RefResolver → ExpressionRewriter → Simplifier → SimpTyper (parallel)
                                                              ↓
                                                         SeqTyper (global barrier)
                                                              ↓
                                                         Codegen (parallel again)
```

No value in a "pre-check" TypeChecker. SimpTyper *is* the checker. If it encounters
a definite error (e.g., `1 + "foo"` where `+` is monomorphic on Int), it can error
immediately or emit a failing constraint.

### Q5: Incremental Compilation

**Reality**: Global type inference is inherently at odds with incremental compilation.
If you change a type in Module A, and Module B infers types from A, Module B must be
re-checked.

**MVP approach**: SimpTyper is incremental (per-module), SeqTyper always does full build.

**Future mitigation**: "Interface Hashing" - after SimpTyper on A, if the exported
type signatures/constraints haven't changed, skip re-running SeqTyper on dependents.

### Q6: Global State for SeqTyper [PARTIALLY RESOLVED]

Create a **transient** `SolverContext` that lives only during Phase 2:

```scala
class SolverContext(
  varMapping: Map[(ModuleId, Int), Int],  // local → global var IDs
  unionFind: UnionFind,                    // the unification structure
  constraints: List[Constraint]            // merged from all modules
)
```

**Key principle**: Do not pollute permanent `CompilerState` with unification variables.
They are transient solution artifacts.

Full design depends on multi-module compilation (in progress).

### Q7: Polymorphism Scope [RESOLVED]

Solved by **level-based generalization** (see UnificationVar.level above).

- Variables track scope depth at creation
- Unification promotes to outermost (lowest) level
- After solving let-binding at level L, vars with level > L can generalize
- No AST structure needed in SeqTyper

### Q8: Native Functions and Polymorphism [RESOLVED]

`@native` functions are **anchors**:

- They must have explicit type annotations (no body to infer from)
- Signatures are trusted, act as rigid boundaries
- When SimpTyper sees `native_fn(x)` where `native_fn` expects `Int`,
  it emits `Equals(x, Int)` - excellent source of truth for solver
- No inference through native bodies (they're external)

## Dependencies

1. **Multi-module compilation** - needed for SeqTyper global state design
2. **SeqTyper global state design** - after multi-module support
3. **SeqTyper implementation** - after global state design

## Polymorphism Rank

**Rank-1 for MVP**. The constraint system as designed supports Rank-1 (prenex) well.

**Rank-N is future work**. Arbitrary-rank types (Peyton Jones et al.) require:
- Bidirectional type checking (we have this)
- Instantiation constraints (we have this)
- **Subsumption checking** (not in current constraints)
- **Skolemization** for checking quantified types

When checking `e : ∀α. τ` against expected type `σ`, you need to skolemize `α`
(make it rigid during the check). This isn't just unification anymore.

Do not attempt Rank-N until Rank-1 is solid and tested.

## Row Unification Algorithm

Row unification is more complex than regular unification. Example:
```
{ x: Int | ?ρ₁ } ~ { x: Int, y: String | ?ρ₂ }
```

Algorithm:
1. Match common fields (`x: Int ~ Int` ✓)
2. Collect unmatched fields (`{ y: String }` from second record)
3. Unify `?ρ₁` with `{ y: String | ?ρ₂ }`

```
unifyRows(r1: TypeRecord, r2: TypeRecord):
  commonFields = r1.fields ∩ r2.fields (by name)
  for (name, t1, t2) in commonFields:
    unify(t1, t2)

  extraInR1 = r1.fields - commonFields
  extraInR2 = r2.fields - commonFields

  if extraInR1.isEmpty && extraInR2.isEmpty:
    unify(r1.rowVarId, r2.rowVarId)
  elif extraInR1.isEmpty:
    # r1 is smaller, its row must contain r2's extra fields
    unify(r1.rowVarId, TypeRecord(extraInR2, r2.rowVarId))
  elif extraInR2.isEmpty:
    # r2 is smaller, its row must contain r1's extra fields
    unify(r2.rowVarId, TypeRecord(extraInR1, r1.rowVarId))
  else:
    # Both have extra fields - need fresh row var
    ?fresh = freshRowVar()
    unify(r1.rowVarId, TypeRecord(extraInR2, ?fresh))
    unify(r2.rowVarId, TypeRecord(extraInR1, ?fresh))
```

**EmptyRow**: Sentinel value (e.g., `rowVarId = -1` or dedicated type). When a row
var unifies with EmptyRow, the record is closed - no more fields can be added.

## Module Exports

SimpTyper needs to know which bindings are exported. SeqTyper uses this to resolve
`Import` constraints.

**To be designed with module system**:
- How exports are tracked (explicit export list? public-by-default?)
- What information is stored per export (type, scheme, constraints?)
- When this metadata is computed (during SimpTyper? separate pass?)

For now, assume `Module.exports: Map[String, TypeSpec]` populated by SimpTyper,
containing the (possibly unsolved) types of all public members.

## References

- Hindley-Milner type inference (Damas-Milner Algorithm W)
- "Practical type inference for arbitrary-rank types" - Peyton Jones et al. (future)
- Union-find for efficient unification
- Row polymorphism literature (Rémy, Wand)

## Implementation Pitfalls

### Pitfall 1: Recursive Let Timing (SimpTyper)

When inferring `let f = ...`:

```
# WRONG order:
state = state.enterLevel()
rhsType = inferExpr(rhs, ...)    # ← if rhs references f, it's not in scope yet!

# CORRECT order:
state = state.enterLevel()
(fVar, state) = freshVar(state, binding.span)
env = env + (f -> fVar)          # ← add f to environment FIRST
rhsType = inferExpr(rhs, ...)    # ← now recursive refs to f resolve to fVar
emit Unify(fVar.id, rhsType)
```

This matters for recursive functions and recursive data. If MML distinguishes
`let` from `let rec`, this only applies to `let rec`.

### Pitfall 2: Remapping Two-Pass (SeqTyper)

Step 1 (merge constraints) must be **two passes**:

```
# Pass 1: Build complete lookup table
globalIdTable = Map.empty
for (moduleId, module) in modules.zipWithIndex:
  for localId in 0 until module.constraints.nextVarId:
    globalId = (moduleId << 32) | localId
    globalIdTable += (moduleId, localId) -> globalId

# Pass 2: Merge and remap constraints
for (moduleId, module) in modules.zipWithIndex:
  for constraint in module.constraints.constraints:
    ctx = ctx.addConstraint(remapIds(globalIdTable, moduleId, constraint))
```

If done in one pass, Module A referencing Module Z might fail because Z's
variables haven't been assigned Global IDs yet.

### Pitfall 3: Let-Lambda Correspondence and Generalization

MML parses `let` as immediately-applied lambda:
```mml
let name = "fede";
println (hola name)

# Becomes:
((fn name -> println (hola name)) "fede")
```

This is the "Let-Lambda Correspondence" - simpler core, scoping is free.

**The trap**: In strict Hindley-Milner, lambda parameters don't generalize like
let bindings. `let x = e in ...` allows `x` to be polymorphic, but `(λx.body) e`
treats `x` as monomorphic (runtime function call).

**Why we're safe**: Level-based generalization handles this correctly:
- Parser creates Lambda, SimpTyper enters new scope (level + 1)
- Arg is inferred at outer level
- Param unifies with arg
- Level logic correctly determines what can generalize

**Future consideration**: If let-bound variables refuse to generalize, we may need
to flag lambdas as "synthetic" (from let-desugaring) so the type checker knows
aggressive generalization is safe. For now, level-based approach should suffice.

### Pitfall 4: Mutual Recursion Across Modules

If Module A defines `f` calling `B.g`, and Module B defines `g` calling `A.f`:

- SimpTyper on A emits: `Import(?A_f_ret, "B", "g", span)`
- SimpTyper on B emits: `Import(?B_g_ret, "A", "f", span)`

SeqTyper sees circular `Import` constraints. The levels might be incompatible,
or unification might fail to terminate without careful handling.

**MVP restriction**: Require acyclic module dependencies.
- Mutual recursion within a module: allowed (handled by Pitfall 1)
- Mutual recursion across modules: disallowed for MVP
- Future: detect cycles, handle with special unification pass

## Draft Timeline

1. Add `UnificationVar` to AST
2. Add `Constraint`, `ConstraintSet` types
3. Add `constraints` field to `Module`
4. **Implement and unit test UnionFind with level logic in isolation**
   - Test: "unify level 5 and level 3 → result is level 3"
   - Test: "unify level 3 with concrete → level preserved"
   - Test: basic occurs check, path compression
   - Do not hook up to AST yet
5. **Implement minimal SeqTyper solver core** (`Equals`, `Unify`, `HasField`)
   - Include `HasField` early to test row unification before SimpTyper exists
   - Defer `Call`, `Import`, `Instantiate` for later
   - Test end-to-end with hand-written constraints:
     ```scala
     val constraintsA = ConstraintSet(
       Vector(
         Equals(0, TypeInt, span),
         Unify(0, 1, span),
         HasField(2, "name", 3, 4, span)  // test row unification
       ),
       nextVarId = 5,
       currentLevel = 0
     )
     // Verify remapping, unification, row solving, substitution
     ```
6. Implement SimpTyper (parallel-ready, single module first)
7. Test SimpTyper in isolation
8. (Wait for multi-module support)
9. Design SeqTyper global state
10. Implement full SeqTyper (add `Call`, `Import`, `Instantiate`)
11. Integrate into pipeline

# Remove `FnDef` / `OpDef` Nodes

## Why
- The AST currently carries three top-level callable forms (`FnDef`, `UnaryOpDef`, `BinOpDef`) plus `Bnd`.
- Lambdify exists purely to normalise those constructs into `Bnd(Lambda)` ahead of the type checker.
- Removing the extra node types shrinks the surface area of the AST, lets the parser produce the canonical representation directly, and eliminates an entire semantic phase.
- Fewer variants simplifies downstream reasoning: type resolution, name resolution, expression rewriting, and error reporting all operate on the same structure.

## Target End State
- Parser and standard-library injections emit callables as `Bnd` nodes whose value is an `Expr` containing a `Lambda`.
- Each binding carries a mandatory `BindingMeta` payload with all information the downstream phases need (arity, precedence, associativity, original name, native marker, etc.).
- Lambdify phase is deleted from the semantic pipeline (`SemanticApi.rewriteModule`) and from `memory-bank/activeContext.md`.
- Type checker and codegen continue to operate on `Bnd(Lambda)` without any transitional rewrites.

## Binding Meta Shape (Proposal)
```scala
enum CallableArity:
  case Nullary, Unary, Binary, Nary(paramCount: Int)

final case class BindingMeta(
  origin:       BindingOrigin,        // Function or Operator
  arity:        CallableArity,
  precedence:   Int,
  associativity: Option[Associativity] = None,
  originalName: String,               // surface name, e.g. "+"
  mangledName:  String                // internal name used in Bnd.name
)

enum BindingOrigin:
  case Function
  case Operator
```

Notes:
- All callable bindings carry a precedence number. Operators keep the user-authored value (or parser default). Functions use a sentinel `FunctionPrecedence` defined as `(MaxUserPrecedence + 1)` so application outranks any operator.
- Prefix/postfix behaviour is inferred the same way the current expression rewriter does: unary + right-associative → prefix, unary + left-associative → postfix.
- `mangledName` allows unary `-` and binary `-` (and future overloads) to coexist without collisions, mirroring the planned operator-arities mangling. `Bnd.name` stores the mangled version; `meta.originalName` preserves the surface spelling for diagnostics.
- `CallableArity.Nullary` covers both native nullary functions and future zero-param sugar. `Nary` handles >2 parameters for regular functions.

## Pipeline Changes
### Parser (`modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/members.scala`)
- `fnDefP`, `binOpDefP`, and `unaryOpP` should construct `Bnd` nodes directly with:
  - `value = Expr(span, List(Lambda(...)))`
  - `typeAsc` / `typeSpec` copied from declarations
  - `meta = Some(metaFrom(fnDef | opDef))`, where functions always receive `precedence = FunctionPrecedence`.
- Remove `FnDef`, `UnaryOpDef`, `BinOpDef` constructors once downstream call sites are migrated.
- Preserve doc-comments and visibility flags in the new `Bnd`.

### Standard Injections (`semantic/package.scala:166`, `:270`)
- Replace emitted `FnDef` / `OpDef` with `Bnd` + meta to keep the builtin library consistent with parsed modules.
- Reuse helper(s) shared with the parser to avoid drifting meta construction logic.

### Duplicate Name Checks (`semantic/DuplicateNameChecker.scala`)
- Switch grouping logic from class-based pattern matches to the new meta:
  - Functions: `meta.origin == Function`
  - Operators: `meta.origin == Operator`; associativity/arity on meta are available if duplicate diagnostics need to separate unary/binary forms.
- Duplicate parameter detection moves to the lambda params inside each `Bnd`.

### Type Resolver (`semantic/TypeResolver.scala`)
- Currently resolves `FnDef.params`, `UnaryOpDef.param`, `BinOpDef.param1/param2`. Needs to walk the `Lambda` inside `Bnd.value`.
- Ensure `BindingMeta` survives resolution (typeresolver should not drop meta when copying nodes).

### Ref Resolver (`semantic/RefResolver.scala`)
- `lookupRefs` today collects `FnDef` / `OpDef` explicitly (`RefResolver.scala:97`). Update it to return the new `Bnd` bindings with meta (plus lambda params for local scopes).
- Continue to handle parameter shadowing semantics by checking `FnParam` first, then module-level bindings.

### Expression Rewriter
- Existing extractor objects (`IsOpRef`, `IsPrefixOpRef`, `IsFnRef`, etc.) need to pivot to reading meta:
  - `IsOpRef` becomes something like: pull `BindingMeta` from `Bnd` (through the `Ref.candidates` list) and check `meta.origin == Operator`.
  - Precedence comes straight from `meta.precedence`. `FunctionPrecedence` must be greater than any user-assignable value so the precedence climber keeps treating application as the tightest binding (mirroring the current `buildAppChain` behaviour that consumes arguments before `rewriteOps` sees lower-precedence operators).
  - Prefix vs postfix stays aligned with the current convention: unary + right-associative → prefix, unary + left-associative → postfix. No extra field required.
- `IsFnRef` emits any binding whose meta is `Function` (or operator when in function position).
- Ensure `buildAppChain` still auto-wraps nullary functions; meta.arity == Nullary becomes the new trigger.
- Update `isOperator(term)` to inspect `BindingMeta` rather than testing for `BinOpDef` / `UnaryOpDef` instances.

### Type Checker
- Remove all Lambdify-specific checks (`checkMember` currently branches on `Lambda` in `Bnd.value`). The checks stay relevant but will be the only path.
- `checkRef` should rely on `BindingMeta` for operator arity information (oversaturation/undersaturation errors) instead of trying to infer from `Decl` subclasses.
- Update tests (`AppRewritingTests`, `LambdifyTests`, etc.) to cover the new representation.

### Codegen
- Expression compiler already works on `Bnd` + `Lambda`, so minimal change: the helper methods that detect binary/unary operators (`ExpressionCompiler.scala:252`) must consult meta rather than `isInstanceOf[BinOpDef]`.

## Removal Tasks
1. Extend `Bnd` with `meta: Option[BindingMeta]`.
2. Define new meta-related enums/objects in `ast/AstNode.scala` (or dedicated file).
3. Parser + injections: emit `Bnd` with meta.
4. Update semantic phases to consume meta, delete unreachable branches that depended on `FnDef`/`OpDef`.
5. Remove `FnDef`, `UnaryOpDef`, `BinOpDef`, `OpDef` definitions once nothing references them.
6. Drop `Lambdify` from codebase and pipeline (delete phase, remove from `SemanticApi.rewriteModule`, clean up tests/docs).
7. Update docs: `docs/brainstorming/lambdify.md` becomes historical record or is superseded by this spec, `memory-bank/activeContext.md` gets revised steps, TypeChecker notes updated.

## Name Mangling Strategy
- Operators currently collide on name (e.g. unary `-` and binary `-`). The ongoing arity mangling plan applies cleanly here:
  - Mangled name stored in `Bnd.name` (`_name` for unary, `_name_` for binary).
  - `BindingMeta.originalName` holds the surface spelling for diagnostics and expression rewriting.
  - Because meta travels with the binding, ref resolver can keep `Ref.name` equal to the user-written symbol while `Ref.resolvedAs` points at the mangled binding.
- Functions keep their original names (no mangling) unless overloading is introduced later.

## Edge Cases to Validate
- `-1` vs `1 - 1`: ensure parser produces two bindings with distinct mangled names but matching `originalName`.
- Nullary native functions: meta arity must be `Nullary` so expression rewriter keeps auto-applying `()`.
- Doc comments and source spans: confirm they still appear on the binding for IDE/tooling.
- Error messages: any printer or diagnostic that currently formats `FnDef` / `OpDef` must grab the pretty name from `meta.originalName`.
- Pretty printer (`util/prettyprint/ast/Member.scala`): update to render meta-aware callables.
- Tests relying on class names (e.g. pattern matching in existing specs) need rewrites to use meta-based assertions.

## Follow-Up Questions
- **`Decl` trait:** KEEP IT. After removal, `Decl` will still be extended by `Bnd`, `TypeDef`, and `TypeAlias`. The trait captures "things that declare names in the module namespace" and is used throughout the codebase for pattern matching on any declaration type.
- Do we want meta to be required (`Option` vs plain field)? The design assumes it is mandatory for callable bindings but optional for other `Bnd`. Consider encoding with a sum type (`BindingKind`) or enforcing via constructor helpers.
- Should we store `arity` both as numeric and lambda parameter list length? (Probably yes: meta provides quick checks; Lambda remains the source of truth.)
- Define `MaxUserPrecedence` and `FunctionPrecedence` explicitly (e.g., if operators clamp to 0-100 today, set `FunctionPrecedence` to 101) so the precedence comparison logic stays predictable.

## References
- `docs/brainstorming/lambdify.md` (existing rationale for the normalisation phase we are replacing).
- `memory-bank/specs/operator-arity-mangling.md` (current name-mangling plan; integrate here).
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ExpressionRewriter.scala` for precedence logic.
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala` for the lambda/ binding assumptions.

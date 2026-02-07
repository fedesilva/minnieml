# Plan: add name node to AST

## Context

Named declarations (`Bnd`, `TypeDef`, `TypeAlias`, `TypeStruct`, `FnParam`, `Field`) store
their name as a bare `String` with no source position. This forces `SemanticTokens.scala` to
**guess** name positions using `declarationToken()` and `keywordLengthFor()` — a fragile approach
that has already caused miscoloring bugs (e.g., the struct keyword-length fix on 2026-02-06).

The fix: introduce a `Name` case class that pairs the identifier string with its `SrcSpan`, then
use it on all named declarations so `decl.nameNode.span` gives the precise name position.

## Design: unified access via `name` function

To avoid a massive blast radius (~305 usages across ~37 files), use Scala's uniform access
principle:

- `nameNode: Name` — the actual stored field on case classes
- `def name: String = nameNode.value` — derived accessor on the traits, fully
  backward-compatible

Existing code that does `.name` keeps returning `String` with zero changes. Only code that
needs the span accesses `.nameNode.span`.

Blast radius is limited to:
- AST construction sites (parser + synthetic code) — change `name = "foo"` → `nameNode = Name(...)`
- Semantic tokens — simplified to use `.nameNode.span`

## Plan

### Step 1: define `name` and a synthetic helper

File: `ast/common.scala`

```scala
case class Name(span: SrcSpan, value: String) extends AstNode, FromSource

object Name:
  val emptySpan: SrcSpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))
  def synth(value: String): Name = Name(emptySpan, value)
```

### Step 2: update traits

File: `ast/common.scala`

```scala
trait Resolvable extends AstNode:
  def nameNode: Name
  def name: String = nameNode.value   // backward-compatible default
  def id:   Option[String]
```

File: `ast/types.scala`

```scala
sealed trait ResolvableType extends AstNode:
  def nameNode: Name
  def name: String = nameNode.value   // backward-compatible default
  def id:   Option[String]
```

### Step 3: update AST case classes

Replace `name: String` with `nameNode: Name` on:

| Node | File |
|------|------|
| `Bnd` | `ast/members.scala` |
| `FnParam` | `ast/common.scala` |
| `TypeDef` | `ast/types.scala` |
| `TypeAlias` | `ast/types.scala` |
| `TypeStruct` | `ast/types.scala` |
| `Field` | `ast/types.scala` |

Each case class inherits `def name: String = nameNode.value` from the trait.

### Step 4: update parser to capture name spans

File: `parser/members.scala`

- `letBindingP`: Add `spNoWsP(info)` after `bindingIdOrError` to capture name end point.
  Create `Name(span(startPoint, nameEnd), name)`.

- `fnDefP`: Already captures `nameStart`/`nameEnd`. Create
  `Name(span(nameStart, nameEnd), fnName)`. Pass to `Bnd(..., nameNode = ...)`.

- `binOpDefP`: Same as fnDefP — already captures `nameStart`/`nameEnd`.
  Create `Name(span(nameStart, nameEnd), mangledName)`.

- `unaryOpP`: Same pattern.

- `fnParamP`: Add `spP(info)` before `bindingIdP` and `spNoWsP(info)` after to capture
  name span. Create `Name(nameSpan, paramName)`.

File: `parser/types.scala`

- `nativeTypeDefP`: Add `spP(info)` before `typeIdP` and `spNoWsP(info)` after.
  Create `Name(nameSpan, id)`.

- `typeAliasP`: Same pattern.

- `structDefP`: Same pattern.

- `fieldP`: Add `spNoWsP(info)` after `bindingIdP`.
  Create `Name(nameSpan, id)`.

### Step 5: update AST construction sites

Only sites that **construct** these AST nodes need changes — switching from
`name = "foo"` to `nameNode = Name.synth("foo")` (or `Name(span, value)` when a
span is available).

Key files:
- `semantic/MemoryFunctionGenerator.scala` — generates `__free_T` / `__clone_T` functions
- `semantic/OwnershipAnalyzer.scala` — generates temp bindings, free calls
- `semantic/ExpressionRewriter.scala` — rewrites expressions
- `semantic/package.scala` — stdlib definitions
- `codegen/` — any node construction
- Test files that construct AST nodes

### Step 6: simplify semantictokens

File: `lsp/SemanticTokens.scala`

- Delete `declarationToken()` and `keywordLengthFor()` functions.
- `collectFromTypeDef`: Use `td.nameNode.span` directly for the name token position.
- `collectFromTypeAlias`: Use `ta.nameNode.span` directly.
- `collectFromTypeStruct`: Use `ts.nameNode.span` directly (replaces hardcoded offset 7).
- `collectFromBnd`: Use `bnd.nameNode.span` for name token (replaces backward guessing).
- `collectFromLambda`: Use `param.nameNode.span` for parameter tokens.

## Files modified (summary)

- AST: `ast/common.scala`, `ast/types.scala`, `ast/members.scala`
- Parser: `parser/members.scala`, `parser/types.scala`
- LSP: `lsp/SemanticTokens.scala`
- Synthetic construction: `MemoryFunctionGenerator.scala`, `OwnershipAnalyzer.scala`,
  `ExpressionRewriter.scala`, `package.scala`, codegen files that construct AST nodes
- Tests: any test that constructs `Bnd`, `FnParam`, `Field`, `TypeDef`, `TypeAlias`,
  `TypeStruct` directly

## Verification

1. `sbtn "test"` — all tests pass (compiler catches every construction site)
2. `sbtn "scalafmtAll; scalafixAll"`
3. `sbtn "mmlcPublishLocal"` then `make -C benchmark clean && make -C benchmark mml`
4. Open a `.mml` file in VSCode/Neovim — verify semantic token colors are correct
   (the primary motivation for this change)

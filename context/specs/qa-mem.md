# Code Quality Report: Memory Management Phases

**Date:** 2026-02-04
**Files reviewed:**
- `semantic/OwnershipAnalyzer.scala` (975 lines)
- `semantic/MemoryFunctionGenerator.scala` (294 lines)
- `ast/TypeUtils.scala` (54 lines)
- `semantic/package.scala` (stdlib injection)

---

## 1. Hardcoded Logic

### 1.1 Hardcoded Stdlib IDs

Multiple locations construct stdlib IDs as string literals rather than deriving them from a central source.

| File | Line | Code | Issue |
|------|------|------|-------|
| `OwnershipAnalyzer.scala` | 299 | `Some("stdlib::typedef::Unit")` | Repeated in 6+ places |
| `OwnershipAnalyzer.scala` | 329 | `Some("stdlib::typedef::Bool")` | Repeated in 3+ places |
| `MemoryFunctionGenerator.scala` | 57 | `Some("stdlib::typedef::Unit")` | Same pattern |

**Note on free/clone function lookup:** The `stdlib::bnd::` prefix in `lookupFreeFnId` (line 280) and similar is correct:
- C runtime types (`String`, `Buffer`, `IntArray`, `StringArray`) have `__free_*`/`__clone_*` in stdlib
- User-defined structs have their free/clone functions generated into the user module by `MemoryFunctionGenerator`
- The lookup correctly tries stdlib first, then falls back to searching the module's resolvables

**Note on Unit/Bool:** These are fundamental types that the ownership analyzer legitimately needs:
- `Unit` - return type of `__free_*` functions, type of `let _ = ...` discard bindings
- `Bool` - type of witness booleans for mixed ownership tracking (`__owns_x`)

The dependency is unavoidable; the issue is only the repeated string literals.

**Recommendation:** Extract to a shared constants object (location TBD):

```scala
object StdlibIds:
  def typedef(name: String): String = s"stdlib::typedef::$name"
  val UnitType: String = typedef("Unit")
  val BoolType: String = typedef("Bool")
```

### 1.2 Hardcoded Fallback

```scala
// OwnershipAnalyzer.scala:296
val freeFn = typeName.flatMap(freeFnFor(_, resolvables)).getOrElse("__free_String")
```

If type lookup fails, it silently falls back to `__free_String`. This could mask bugs.

**Recommendation:** Return an error or log a warning instead of silent fallback.

### 1.3 Hardcoded Naming Convention

```scala
// OwnershipAnalyzer.scala:769
val isStructConstructor = baseFn match
  case ref: Ref => ref.name.startsWith("__mk_")
  case _ => false
```

**Recommendation:** Use binding metadata instead:

```scala
val isStructConstructor = baseFn match
  case ref: Ref =>
    ref.resolvedId.flatMap(resolvables.lookup).exists {
      case bnd: Bnd => bnd.meta.exists(_.origin == BindingOrigin.DataConstructor)
      case _ => false
    }
  case _ => false
```

---

## 2. Cross-Dependencies

**Status: Clean**

All semantic phases import only:
- `mml.mmlclib.ast.*` - shared AST definitions
- `mml.mmlclib.compiler.CompilerState` - pipeline state

No phase imports another phase directly. Type queries are centralized in `ast.TypeUtils`, which both `OwnershipAnalyzer` and `MemoryFunctionGenerator` use correctly.

```
semantic/OwnershipAnalyzer.scala     ─┬─> ast/*
semantic/MemoryFunctionGenerator.scala ─┤
                                        └─> ast/TypeUtils (shared)
```

This is the correct architecture pattern.

---

## 3. Code Complexity

### 3.1 `OwnershipAnalyzer.analyzeTerm` (lines 549-926, ~380 lines)

This method is too long and handles too many concerns. The `App` case alone is ~290 lines with deeply nested pattern matching.

**Current structure:**

```
analyzeTerm(term, scope) =
  term match
    case Ref => 20 lines (use-after-move check)
    case App => 290 lines
      fn match
        case Lambda => 125 lines (let-binding)
        case _ => 165 lines (regular app)
          if allocatingArgs.isEmpty => simple path
          else => 100+ lines temp wrapping
    case Cond => 15 lines
    case Lambda => 20 lines
    case other cases => 50 lines
```

**Recommended refactoring:**

| Extract to | Current lines | Responsibility |
|------------|---------------|----------------|
| `analyzeLetBinding()` | 573-694 | App(Lambda, arg) - let bindings |
| `analyzeRegularApp()` | 697-858 | Non-lambda function application |
| `collectCurriedArgs()` | 699-716 | Collect args from curried chain |
| `wrapAllocatingArgs()` | 736-858 | Temp bindings + free insertion |
| `cloneArgsForConstructor()` | 768-785 | Clone heap args for struct ctors |

### 3.2 Mutable State in Map

```scala
// Lines 741-756 - mutation inside .map()
var currentScope = scope
var argErrors    = List.empty[SemanticError]
val tempsAndArgs = argsWithAlloc.map { case (argExpr, s, tAsc, tSpec, allocOpt) =>
  allocOpt match
    case Some(allocType) =>
      val argResult = analyzeExpr(argExpr, currentScope)
      argErrors = argErrors ++ argResult.errors  // mutation!
      val (tmpName, newScope) = currentScope.nextTemp
      currentScope = newScope                     // mutation!
      ...
```

**Recommendation:** Use `foldLeft` for clarity:

```scala
val (finalScope, argErrors, tempsAndArgs) = argsWithAlloc.foldLeft(
  (scope, List.empty[SemanticError], Vector.empty[...])
) { case ((s, errs, acc), (argExpr, span, tAsc, tSpec, allocOpt)) =>
  allocOpt match
    case Some(allocType) =>
      val argResult = analyzeExpr(argExpr, s)
      val (tmpName, newScope) = s.nextTemp
      (newScope, errs ++ argResult.errors, acc :+ ...)
    case None =>
      (s, errs, acc :+ ...)
}
```

### 3.3 `ReturnOwnershipAnalysis` Inner Object (lines 109-200)

This 90-line fixed-point analysis is conceptually independent. It discovers which functions return owned values by iterating until convergence.

**Recommendation:** Either:
1. Extract to separate file `semantic/ReturnOwnershipAnalysis.scala`
2. Or add clear documentation header explaining the algorithm

### 3.4 Deep Nesting in Witness Logic

```scala
// Lines 658-674 - 4 levels of nesting
val newBody = witnessOpt match
  case Some((witnessName, _, _)) =>
    val bindingName = params.headOption.map(_.name).getOrElse("")
    val bindingType = params.headOption.flatMap(p => p.typeSpec.orElse(p.typeAsc))
    val bindingId   = params.headOption.flatMap(_.id)
    bindingType match
      case Some(tpe) if getTypeName(tpe).exists(isHeapType(_, scope.resolvables)) =>
        val toFree = List((bindingName, Some(tpe), bindingId, Some(witnessName)))
        wrapWithFrees(bodyWithTerminalFrees, toFree, body.span, scope.resolvables)
      case _ =>
        bodyWithTerminalFrees
  case None =>
    bodyWithTerminalFrees
```

**Recommendation:** Extract to helper:

```scala
private def wrapWithWitnessFreeIfNeeded(
  body: Expr,
  witnessOpt: Option[(String, Expr, Option[Type])],
  params: List[FnParam],
  scope: OwnershipScope
): Expr = ...
```

---

## 4. Summary

| Category | Status | Priority | Action |
|----------|--------|----------|--------|
| Hardcoded stdlib IDs | Warning | Medium | Extract to `StdlibIds` object |
| Hardcoded fallback | Warning | Low | Log warning or return error |
| Hardcoded naming | Warning | Low | Use binding metadata |
| Cross-dependencies | OK | - | None needed |
| `analyzeTerm` size | Warning | High | Split into 5-6 helpers |
| Mutable state in map | Warning | Medium | Refactor to `foldLeft` |
| `ReturnOwnershipAnalysis` | Info | Low | Extract or document |

---

## 5. Positive Notes

- `TypeUtils` correctly centralizes type queries - both phases use it
- Phase isolation is clean - no imports between semantic phases
- CPS-style free insertion is elegant and doesn't require codegen changes
- Witness boolean approach for mixed ownership is a clever compile-time solution

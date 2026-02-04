# Memory Management Prototype Implementation Plan

Affine ownership with borrow-by-default. Insert `__free_T` calls as standard `App` nodes.
No codegen changes needed.

## Implementation Phases

### Phase 0: AST & Infrastructure (No Syntax Changes)

Hardcode memory effects in compiler. Get infrastructure working before parser changes.

#### 0.1 Add MemEffect Enum

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala`

Add after imports:
```scala
enum MemEffect derives CanEqual:
  case Alloc    // returns newly allocated memory, caller owns
  case Static   // returns pointer to static/existing memory
```

#### 0.2 Extend NativeImpl

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala` (line 182)

Add `memEffect` field:
```scala
case class NativeImpl(
  span:      SrcSpan,
  typeSpec:  Option[Type]      = None,
  typeAsc:   Option[Type]      = None,
  nativeTpl: Option[String]    = None,
  memEffect: Option[MemEffect] = None  // NEW
) extends Native, Term
```

#### 0.3 Add Consuming Flag to FnParam

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala` (FnParam case class)

Add `consuming` field:
```scala
case class FnParam(
  span:       SrcSpan,
  name:       String,
  typeSpec:   Option[Type]       = None,
  typeAsc:    Option[Type]       = None,
  docComment: Option[DocComment] = None,
  id:         Option[String]     = None,
  consuming:  Boolean            = false  // NEW: for ~param syntax
)
```

#### 0.4 Add DataDestructor Term

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala` (after DataConstructor, ~line 180)

```scala
case class DataDestructor(
  span:     SrcSpan,
  typeSpec: Option[Type] = None
) extends Term:
  val typeAsc: Option[Type] = None
```

#### 0.5 Hardcode Memory Effects in Stdlib

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala`

Modify `mkFn` helper to accept optional `MemEffect`:
```scala
def mkFn(name: String, params: List[FnParam], returnType: Type,
         memEffect: Option[MemEffect] = None): Bnd =
  // ... existing code ...
  val body = Expr(dummySpan, List(NativeImpl(dummySpan, memEffect = memEffect)))
  // ... rest unchanged ...
```

Tag allocating functions (in `injectCommonFunctions`):
- `readline` -> `Some(MemEffect.Alloc)`
- `concat` -> `Some(MemEffect.Alloc)`
- `to_string` -> `Some(MemEffect.Alloc)`
- `mkBuffer`, `mkBufferWithFd`, `mkBufferWithSize` -> `Some(MemEffect.Alloc)`
- `ar_int_new`, `ar_str_new` -> `Some(MemEffect.Alloc)`
- `read_line_fd` -> `Some(MemEffect.Alloc)`

#### 0.6 Add __free_* Functions to Stdlib

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala`

Add to `injectCommonFunctions`:
```scala
mkFn("__free_String", List(FnParam(dummySpan, "s", typeAsc = Some(stringType))), unitType),
mkFn("__free_Buffer", List(FnParam(dummySpan, "b", typeAsc = Some(bufferType))), unitType),
mkFn("__free_IntArray", List(FnParam(dummySpan, "a", typeAsc = Some(intArrayType))), unitType),
mkFn("__free_StringArray", List(FnParam(dummySpan, "a", typeAsc = Some(stringArrayType))), unitType),
```

#### 0.7 Add Runtime Free Functions

**File:** `modules/mmlc-lib/src/main/resources/mml_runtime.c`

```c
void __free_String(String s) {
    if (s.data) free(s.data);
}

void __free_Buffer(Buffer b) {
    if (b) {
        if (b->data) free(b->data);
        free(b);
    }
}

void __free_IntArray(IntArray arr) {
    if (arr.data) free(arr.data);
}

void __free_StringArray(StringArray arr) {
    if (arr.data) {
        for (int64_t i = 0; i < arr.length; i++) {
            __free_String(arr.data[i]);
        }
        free(arr.data);
    }
}
```

#### 0.8 Update Pretty Printers

**Files:**
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Term.scala`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Common.scala`

Add cases for `DataDestructor`, `MemEffect`, and `consuming` flag.

---

### Phase 1: Parser Support

#### 1.1 Extend nativeImplP for [mem=alloc/static]

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala` (line 314)

Replace `nativeImplP`:
```scala
private[parser] def nativeImplP(info: SourceInfo)(using P[Any]): P[NativeImpl] =
  def memEffectP: P[MemEffect] =
    P("alloc").map(_ => MemEffect.Alloc) | P("static").map(_ => MemEffect.Static)

  def tplAttrP: P[String] = P("tpl" ~ "=" ~ "\"" ~ CharsWhile(_ != '"', 0).! ~ "\"")
  def memAttrP: P[MemEffect] = P("mem" ~ "=" ~ memEffectP)

  // Parse [tpl="...", mem=alloc] or [mem=alloc, tpl="..."] or just one
  def attrsP: P[(Option[String], Option[MemEffect])] =
    P("[" ~ (
      (tplAttrP ~ ("," ~ memAttrP).?).map { case (t, m) => (Some(t), m) } |
      (memAttrP ~ ("," ~ tplAttrP).?).map { case (m, t) => (t, Some(m)) }
    ) ~ "]").?.map(_.getOrElse((None, None)))

  P(spP(info) ~ nativeKw ~ attrsP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, (tpl, mem), end, _) =>
      NativeImpl(span(start, end), nativeTpl = tpl, memEffect = mem)
    }
```

#### 1.2 Add ~ Prefix for Consuming Parameters

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/members.scala` (fnParamP, ~line 56)

```scala
private[parser] def fnParamP(info: SourceInfo)(using P[Any]): P[FnParam] =
  P(
    spP(info) ~ docCommentP(info) ~ "~".!.? ~ bindingIdP ~ typeAscP(info) ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, doc, tilde, name, t, end, _) =>
    FnParam(
      span       = span(start, end),
      name       = name,
      typeAsc    = t,
      docComment = doc,
      consuming  = tilde.isDefined
    )
  }
```

---

### Phase 2: OwnershipAnalyzer

#### 2.1 Create OwnershipAnalyzer Phase

**New File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`

Core structure:
```scala
package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

enum OwnershipState:
  case Owned, Moved, Borrowed, Literal

object OwnershipAnalyzer:
  def rewriteModule(state: CompilerState): CompilerState =
    // Walk members, analyze functions, insert __free_T calls
    ???
```

Key responsibilities:
1. Track ownership state for each binding
2. At `App` nodes: check callee's `memEffect` and `consuming` params
3. Validate last-use constraints for `~` parameters
4. Insert `App(Ref("__free_T"), Ref(binding))` at scope end
5. Handle conditional branches (both must have same ownership)

#### 2.2 Add Ownership Errors

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala`

Add to `SemanticError`:
```scala
case UseAfterMove(ref: Ref, movedAt: SrcSpan, phase: String)
case ConsumingParamNotLastUse(param: FnParam, ref: Ref, phase: String)
case PartialApplicationWithConsuming(app: App, param: FnParam, phase: String)
case ConditionalOwnershipMismatch(cond: Cond, phase: String)
```

#### 2.3 Integrate into Pipeline

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala`

Add after `tailrec-detector`:
```scala
|> CompilerState.timePhase("semantic", "ownership-analyzer")(
  OwnershipAnalyzer.rewriteModule
)
```

---

### Phase 3: Struct Destructors (Future)

Generate `__free_StructName` for user structs alongside `__mk_StructName`.
Handle `DataDestructor` in codegen to free heap-allocated fields.

---

## Critical Files Summary

| File | Changes |
|------|---------|
| `ast/terms.scala` | Add `MemEffect`, extend `NativeImpl`, add `DataDestructor` |
| `ast/common.scala` | Add `consuming` to `FnParam` |
| `semantic/package.scala` | Tag allocating functions, add `__free_*`, add errors |
| `parser/expressions.scala` | Extend `nativeImplP` for `[mem=...]` |
| `parser/members.scala` | Add `~` parsing in `fnParamP` |
| `compiler/SemanticStage.scala` | Add `OwnershipAnalyzer` to pipeline |
| `semantic/OwnershipAnalyzer.scala` | NEW: ownership tracking phase |
| `mml_runtime.c` | Add `__free_*` functions |

---

## Verification

After each phase:
```bash
sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"
make -C benchmark clean && make -C benchmark mml
```

Memory leak testing:
```bash
# Create test file
echo -e "hello\nworld" > test.txt

# Build and test
sbtn "run samples/leak_test.mml"
leaks --atExit -- ./build/target/leak_test-*
```

Expected: After OwnershipAnalyzer is complete, leak count should be 0.

# 133-type-checker-current-issues

## Overview

Analysis of semantic errors in MML compiler reveals two distinct bugs in different compiler phases:

1. **TypeResolver Phase**: Parameter reference resolution failure within operator definition scopes
2. **TypeChecker Phase**: Inconsistent arity checking that ignores symbol resolution results

## Source Code Under Analysis

```mml
       op ++ (a: Int b: Int): Int = a + b;
       op ++ (a: Int): Int = a + 1;
       let a = 1 ++ 2;
       let b = ++1;
```

Specifically this is code from the `OpPrecedenceTests` suite, named:
`Test operators with the same symbol but different arity`

which is flagged to be the only test to run in that suite.

run `sbt "mmlclib/testOnly mml.mmlclib.semantic.OpPrecedenceTests"`

See `memory-bank/bugs/133-typechecker-current-issues-dump.txt`
for a full dump of the semantic state at the end of the semantic phases run
and an ast pretty print.

---

## Issue 1: TypeResolver Phase - Parameter Reference Resolution

### Problem Description

The TypeResolver correctly processes parameter declarations but fails to resolve parameter references within operator definition bodies.

### Evidence from AST

**Parameter Declaration (✅ Works Correctly):**
```scala
FnParam(
  SrcSpan(SrcPoint(2,15,15), SrcPoint(2,22,22)),
  a,
  None,
  Some(TypeRef(
    SrcSpan(SrcPoint(2,18,18), SrcPoint(2,22,22)), 
    Int, 
    Some(TypeAlias(...))
  )),
  None
)
```

**Parameter Reference in Body (❌ Fails):**
```scala
Ref(
  SrcSpan(SrcPoint(2,37,37), SrcPoint(2,39,39)),
  a,
  None,           // typeSpec: None
  None,           // typeAsc: None  
  None,           // resolvedAs: None ← UNRESOLVED!
  List(FnParam(...))  // candidates found but not resolved
)
```

### Root Cause

The TypeResolver phase has a **scope management bug** in operator definitions:

1. **Scope Creation**: Parameter scopes may not be properly established for operator definition bodies
2. **Scope Search**: Symbol lookup may not be searching the correct scope chain when resolving parameter references
3. **Binding Logic**: The binding mechanism may not be correctly linking parameter names to their declarations

### Error Manifestation

```
TypeCheckingError(
  UnresolvableType(
    TypeRef(SrcSpan(SrcPoint(2,37,37),SrcPoint(2,39,39)), a, None),
    Ref(..., a, ..., resolvedAs: None, ...),
    mml.mmlclib.semantic.TypeChecker
  )
)
```

### Required Fix

**Location**: `mml.mmlclib.semantic.TypeResolver`

**Action**: Fix scope management for operator definitions to ensure parameter references can resolve to their declarations.

---

## Issue 2: TypeChecker Phase - Inconsistent Arity Checking

### Problem Description

The TypeChecker performs correct symbol resolution but then ignores its own results during type checking, applying incorrect arity rules.

### Evidence from AST

**Symbol Resolution (✅ Works Correctly):**
```scala
Ref(
  SrcSpan(SrcPoint(5,16,118), SrcPoint(5,18,120)),
  ++,
  None,
  None,
  Some(UnaryOpDef(      // ← Correctly resolved to unary operator
    Protected,
    SrcSpan(SrcPoint(3,11,54), SrcPoint(4,8,87)),
    ++,
    FnParam(...),
    50,
    Right,
    Expr(...)
  )),
  List(BinOpDef(...), UnaryOpDef(...))  // Both candidates available
)
```

**Type Checking (❌ Fails):**
```
UndersaturatedApplication(
  App(...),
  2,    // ← Expects 2 arguments (binary operator logic)
  1,    // ← Got 1 argument  
  mml.mmlclib.semantic.TypeChecker
)
```

### Root Cause

The TypeChecker phase has a **resolution-checking disconnect**:

1. **Symbol Resolution Success**: The resolver correctly identifies this as a unary operator application
2. **Arity Check Failure**: The type checking logic ignores the `resolvedAs` field and applies binary operator arity rules
3. **Logic Inconsistency**: Different parts of the TypeChecker are using different information sources

### Error Manifestation

Despite successful resolution to `UnaryOpDef`, the TypeChecker reports:
- Expected arguments: 2 (binary operator)
- Actual arguments: 1
- Error: `UndersaturatedApplication`

### Required Fix

**Location**: `mml.mmlclib.semantic.TypeChecker`

**Action**: Modify arity checking logic to consult the `resolvedAs` field when determining expected argument count:

```scala
// Pseudo-code fix
def checkArity(app: App): Unit = {
  val expectedArity = app.fn.resolvedAs match {
    case Some(BinOpDef(_, _, _, _, _, _, _, _)) => 2
    case Some(UnaryOpDef(_, _, _, _, _, _, _)) => 1
    case Some(FnDef(_, _, _, params, _, _)) => params.length
    case _ => throw new Error("Unresolved function reference")
  }
  
  if (app.args.length != expectedArity) {
    reportArityError(expectedArity, app.args.length)
  }
}
```

---

## Summary

| Issue | Phase | Component | Problem | Fix Required |
|-------|-------|-----------|---------|--------------|
| 1 | TypeResolver | Scope Management | Parameter references unresolved in operator bodies | Fix symbol scope creation/lookup |
| 2 | TypeChecker | Arity Checking | Ignoring symbol resolution results | Use `resolvedAs` field for arity determination |

Both issues represent fundamental compiler infrastructure problems rather than language design issues. The AST structure and parsing are correct - the bugs are in the semantic analysis pipeline.
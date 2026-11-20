# Navigate types on local bindings

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-11-16
- **Target Branch:** `dev/protocol-constraints`

## Execution Checklist

1. [x] **complete** — Repair annotation lookup and verify editor navigation.

## Problem

Go to Definition and Find References cannot select types written on local `let` bindings.
The parser represents a local binding as a scoped lambda parameter whose source span covers
the binding name. Navigation incorrectly requires the annotation's cursor position to be
inside that name span before searching the annotation. Reference lookup and collection also
omit written local-function result annotations.

For example, the first `Number` on line 4 compiles but yields no definition destination:

```text
type Number = Int;
fn main(): Int =
  fn local(value: Number): Number = value;;
  let alias: Number -> Number = local;
  alias 1;
;
```

## Outcome

Definition and reference navigation select written local-binding types independently of the
binding name, preserving navigation on ordinary parameters and local values.

## Plan

Implementation approval: granted.

- Search each annotation using its own source location, and each binding using its name location.
- Cover function and scalar annotations, reference identity, and local-value navigation.
- Verify editor navigation, publish the compiler, and complete independent review.

## Verification

The regression in
[SemanticTokensTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/SemanticTokensTests.scala)
covers both function-type components, a scalar type, shared alias references, and uses of both
local bindings. All 18 focused tests, 2,945 compiler-library tests, and 9 CLI tests pass.
The published compiler passes nine exact local-binding navigation responses through
`python3 tooling/test-protocol-navigation.py`, including definition and reference requests on
written local-function return types. Smoke checks pass 8/8, and all 37 benchmark targets build.
Independent review completed all four dedicated checks. The span-contract documentation
correction also passes fresh narrow review; no findings are open.

## Signoff

- Workstream signoff: granted.
- Commit authorization: granted with S5. Push is not authorized.

## Task Working Memory

Definition and reference lookup corrections are implemented in
[AstLookup](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/AstLookup.scala).
Compiler verification, installed editor checks, independent review, and the source/test tour
are complete. The repair is signed off; no work remains.

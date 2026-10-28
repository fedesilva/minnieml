# Bug notes

## Metadata

- **Owner:** The Author
- **Status:** in_progress
- **Kind:** BUG
- **Priority:** unassigned
- **Created:** 2026-10-26
- **Target Branch:** unassigned
- **External Reference:** None.

## Execution Checklist

1. [ ] **in_progress** — Collect confirmed bugs and update their fix status.
2. [ ] **planned** — Triage open notes and split them into focused tasks.

## Problem / Outcome

Keep bugs found during development visible without interrupting the active implementation.
These are short notes for later triage, not approved repair plans.

## Notes

- **B1 — Open, existing: moved-capture errors disappear in the editor.**
  `CapturedMovedHeapBinding` has a compiler diagnostic, but
  [LSP diagnostics](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/Diagnostics.scala)
  lacks its source-span mapping and returns no editor diagnostic. The CLI reports it.
  This is a diagnostic-delivery bug; no ownership-checking or memory-safety defect is established.
  Follow-up: [Show moved-capture and visibility errors in the editor](lsp-missing-semantic-diagnostics.md).
- **B2 — Open, existing: visibility errors disappear in the editor.**
  `VisibilityViolation` is omitted by the same LSP source-span mapping. The CLI reports it.
  Follow-up: [Show moved-capture and visibility errors in the editor](lsp-missing-semantic-diagnostics.md).
- **B3 — Corrected and verified in protocols P1: reserved function names accepted.**
  Protocol signatures and instance functions accepted `fn if`; ordinary functions rejected it.
  They share ordinary binding-name validation. Rejection and keyword-prefix controls pass.
- **B4 — Corrected in protocols P2: instance bodies bypassed lexical duplicate checks.**
  Duplicate parameter names and duplicate local bindings escaped `DuplicateNameChecker` inside
  instances. Its ordinary Lambda traversal covers instance bodies; focused regressions pass.
- **B5 — Corrected in protocols P2: lowering lost editor-facing source information.**
  Instance header spans masked separately lowered bodies, internal function names leaked into
  lookup, and inherited types overwrote written annotations. Written names/annotations are
  retained; inherited annotations are marked derived. Editor and source-preservation tests pass.
- **B6 — Corrected and verified in protocols P3: stale selection after repeated resolution.**
  [RefResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/RefResolver.scala)
  retained `resolvedId` when an already-resolved reference gained multiple candidates. Shared
  lookup replaces both fields, clearing stale selections. The explicit rerun regression passes.
- **B7 — Corrected and verified in protocols P3: binary syntax masks postfix syntax.**
  A symbol declared as both binary and postfix is always treated as binary after an operand,
  even in `1 !`, where no right operand exists. `ProtocolGroupingTests` reproduces the failure.
  Grouping checks whether a right operand can begin before choosing binary syntax over postfix.
- **B8 — Corrected and verified in protocols P3: prefix operators fail in value position.**
  A bare or parenthesized unary operator is treated as an invocation without an operand.
  Two `ProtocolGroupingTests` reproduce the failure. Standalone operator references retain their
  candidates for ordinary function-value selection.

- **B9 — Corrected and verified in protocols P3: constructor internal names fail lookup.**
  Shared candidate lookup omitted constructor binding names (`__mk_Inner`) while accepting their
  source names (`Inner`). Generated clone bodies failed with undefined constructor references.
  The shared lookup accepts both names; lowered instance bindings remain outside source lookup.
  An explicit source/internal-name regression and the corrected full suite pass.

- **B10 — Corrected and verified in protocols P4: ordinary type compatibility compares nominal names.**
  `TypeChecker.areTypesCompatible` accepts nominal references/structs by name, while protocol
  identity uses resolved declaration IDs. Distinct indexed declarations sharing a name can
  compare equal through the ordinary checker. P4 introduces one shared comparison using IDs and
  alias expansion, preserving native primitive comparisons. All four focused identity tests pass;
  the full P4 suite passes.

- **B11 — Corrected and verified in protocols P4: grouped result annotations disappear.**
  `ExpressionRewriter` and `Simplifier` discarded the annotation on `(empty ()): Bool` when the
  grouped term was an application. Protocol selection consequently lost the expected result type.
  Expression groups preserve their written result annotations. Application boundaries retain
  callee annotations without changing
  argument evaluation order. Full verification also exposed the parser mirroring a term's annotation
  onto its expression wrapper, duplicating checks and treating a lambda return annotation as a
  callable annotation. Each written annotation remains on its actual source term only. Parser,
  semantic, editor, and evaluation-order regressions pass in the full 2,199-test suite.

- **B12 — Corrected and verified in protocols P4: outer errors invalidate a valid partial call.**
  Propagating an outer argument's expected type into an already inferable inner call replaced
  that valid inner tree with a failure. The existing partial-application recovery test reproduced
  this with an outer Bool argument where Int was required. Independently inferred callees retain
  their typed trees; contextual inference is retried only when independent inference fails.

- **B13 — Corrected and verified in protocols P4: bare functions acquire PAP ownership.**
  Shared callable lowering marked a forwarding lambda with no supplied arguments as a stored
  partial application. A reusable closure capturing that resource-free function was falsely
  consumed by its first invocation; callers also scheduled unnecessary closure cleanup. Existing
  ownership tests fail in the first full P4 run. The stored-PAP marker applies only when arguments
  were supplied; the existing ownership regressions and added sanitizer fixture checking capture,
  local rebinding, and two invocations pass in the 2,192-test run.

- **B14 — Corrected; focused regressions pass: nested selection repeats operand checking.**
  `selectCallable` checks nested operands during probing and again during candidate trials.
  Even with one applicable instance, a left-nested chain has the recurrence
  `S(n) = 1 + 2*S(n-1)`. Reuse is limited to successful, diagnostic-free operands whose
  selection is independent of further context and whose type matches the expected argument.
  Failed operands also repeated that work: for `1.0 + 2 + 3.0 + 4.0`, each outer addition checked
  the failed prefix twice. Independent operand errors rule out enclosing candidates without another
  trial; contextual errors remain eligible for expected-type checking. Literal 24-term left/right
  regressions retain the original diagnostic, check valid following code, and each satisfy a
  five-second type-checker-only bound. Full verification and independent review pass in
  [protocol review repairs](protocols-review-repairs.md#2-failed-nested-expressions).

- **B15 — Corrected and verified in protocols P4: a known candidate hides an unresolved signature.**
  With `choose(seed: 'T): Int` in one protocol and `choose(seed: Int): 'T` in another,
  an unannotated `choose 1` selects the first while the second still needs result-type evidence.
  An enclosing expected type can select the second or expose ambiguity. A selection must remain
  insufficient while a visible signature is unresolved; otherwise operand reuse preserves a
  premature choice. Five explicit regressions pass, covering independent selection, contextual
  selection, direct and nested ambiguity, and nested operand reuse.

- **B16 — Open, existing: 1,000 nested lets overflow the compiler stack.**
  The frontend corpus scan reaches `mml/samples/stack-depth-1000-lets.mml` and raises
  `StackOverflowError` in recursive parsing with the default JVM stack. This reproduces
  [Compiler stack safety](compiler-stack-safety.md), which already contains the repair scope
  and phase-level evidence. The corpus comparison uses an explicitly increased JVM stack;
  that setting does not establish stack safety.

- **B17 — Corrected and verified in protocols P5: lambda inference misses protocol operands.**
  The lambda-parameter inference pass reads ordinary callable types but does not derive a
  protocol member's concrete signature from known operands. `{ x -> x + 1; }`, a local alias
  of `x`, and addition to an Int capture fail with an uninferred parameter after Num injection.
  Three existing `TypeCheckerTests` expose the regression. Inference uses the same protocol
  evidence and candidate rules as ordinary selection, without defaulting to Int. The existing
  inference/conflict/recovery tests and added Float literal, alias, capture, and missing-evidence
  regressions pass in the full 2,227-library-test suite.

- **B18 — Corrected and verified in protocols P6: candidate-isolation tests skip the losing trial.**
  Three `ProtocolSelectionTests` fixtures filter out `IntTrial Bool` using an Int tag before
  concrete checking. They prove independent-probe cleanup, but cannot detect state leaking
  between concrete trials. Both candidates have Int tags; the losing callable expects
  `Int -> Bool` and rejects the identity lambda during concrete checking. Success, reversed-order,
  and negative controls pass. No compiler defect is established.

- **B19 — Corrected and verified in protocols P6: `where` is incorrectly documented as reserved.**
  The parser recognizes deferred constraint headers contextually and accepts `let where = 1;`.
  The reserved-word appendix matches that behavior, with a focused parser regression.

- **B20 — Corrected and verified in protocols P6: test plumbing is duplicated.**
  Three semantic suites repeat AST navigation; two execution suites repeat sanitizer compile/run
  orchestration. The shared test helpers own this plumbing; literal fixtures and assertions stay
  visible. The full suite passes all 2,230 library and 9 CLI tests.
  `FunctionValueCodegenTests` also uses the shared runner; verification of that final consolidation
  is included in [protocol review repairs](protocols-review-repairs.md).

- **B21 — Corrected; focused regressions pass: one invalid instance breaks valid protocol uses.**
  `ProtocolChecker` registers an instance key before validating members. Failed validation leaves
  the key without member bindings; candidate lookup tries those absent bindings even for unrelated
  concrete types. Rejected instances retain uniqueness keys and failure causes but contribute no
  candidates. Valid neighbors, aliases, duplicates, and failed-use causes pass focused tests.
  Full verification and independent review pass in [protocol review repairs](protocols-review-repairs.md).

- **B22 — Corrected; focused regressions pass: rejected signatures cause unrelated registry errors.**
  A duplicate member name rejects protocol registration, but `ProtocolLookupScope.module` still
  exposes its signature IDs. Grouping reports a module-spanning registry error even without a use;
  references add further errors. Lookup carries the original declaration failure; valid neighboring
  signatures remain usable. Used/unused, shared-name, and injected Num regressions pass.
  Full verification and independent review pass in [protocol review repairs](protocols-review-repairs.md).

- **B23 — Reported, existing: nested parentheses cause rapidly growing parse time.**
  Review measurements of valid right-nested arithmetic report roughly doubled time per nesting
  level on both the base revision and protocol implementation. This is separate from B14's
  type-checker failure path. Bounded reproduction and repair planning belong to
  [Bound parsing work for nested parenthesized expressions](parser-parenthesized-expression-performance.md).

- **B24 — Corrected; focused regressions pass: an instance member can masquerade as main.**
  Entry-point validation and emission selected a lowered member whose source name was `main`.
  Their shared finder excludes instance member IDs; tests cover missing source main, reordered
  members, and the real entry point's native exit status.

- **B25 — Corrected; focused regressions pass: signature reference search loses selected calls.**
  Calls hold concrete implementation IDs after selection. Signature searches expand through the
  registry's associations to include calls across instances; concrete member searches retain their
  narrower scope. Exact reference-range regressions pass.

- **B26 — Corrected; focused regressions pass: recovery swallows following declarations.**
  Copied parser boundary lists omitted different declaration keywords and visibility modifiers.
  Shared boundaries preserve declarations after malformed protocol/instance headers, constraints,
  and link directives. Literal parser regressions pass.

- **B27 — Corrected; focused regressions pass: grouping conflicts produce unrelated-looking errors.**
  Grouping correctly refuses inconsistent operator attributes. `ExpressionRewriter` treats that
  refused operator as unexplained leftover terms and emits a second primary diagnostic.
  Failed uses carry the conflicting declaration identities and original diagnostic causes.
  Full-pipeline tests retain the primary conflict, unaffected unary uses, and independent errors;
  see [protocol review repairs](protocols-review-repairs.md).

- **B28 — Open, P1: oversized buffered writes exceed the allocation.**
  [buffer_write](../../modules/mmlc-lib/src/main/resources/mml_runtime.c) flushes when a write
  exceeds the remaining capacity, then copies the entire payload into the same allocation.
  Flushing cannot accommodate a payload larger than the buffer itself. A capacity of four and
  the five-byte string `abcde` produce an ASan heap-buffer-overflow in `memcpy`.
  Numeric and newline writes also fail with capacity one; exact-fit string writes and repeated
  single-byte writes pass. Size-aware writes must handle payloads larger than the total capacity.

- **B29 — Open, P1: dropping an unfilled StringArray frees uninitialized pointers.**
  [ar_str_new](../../modules/mmlc-lib/src/main/resources/mml_runtime.c) allocates slots with
  `malloc` without initializing them. `__free_StringArray` destroys every slot, including those
  never assigned. Creating two slots and immediately destroying the array produces an ASan
  invalid free from `__free_String`; filling only the first slot also fails. Empty arrays and
  fully initialized arrays pass. Each slot needs a valid empty or initialized ownership state.

- **B30 — Open, P2: dropping a local Buffer discards pending output.**
  [__free_Buffer](../../modules/mmlc-lib/src/main/resources/mml_runtime.c) frees its data without
  flushing, contrary to the [buffer I/O contract](../../docs/language-reference.md#buffered-io).
  Writing `local-tail\n`, destroying the buffer, then calling `mml_sys_flush` exits successfully
  with empty output at O0 and O3. Explicitly flushing the local buffer emits the text; the global
  stdout buffer also works. System flushing only reaches the global buffer. A correction must
  reconcile [StorageOnlyDestruction](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/StorageOnlyDestruction.scala),
  which treats this destructor as having no observable effect beyond releasing storage.

- **B31 — Open, P2: build-directory paths containing spaces fail LLVM assembly.**
  [LlvmToolchain](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
  interpolates paths into the string command `llvm-as $sourceFile -o $outputFile` and invokes
  `Process(cmd, dir)`, splitting spaced paths into separate arguments.
  `sbtn 'run -v --build-dir "build/review space" tests/smoke/hola.mml'` reproduces
  `llvm-as: Too many positional arguments`. The same source with a path without spaces passes;
  quoted direct assembly of the generated IR passes. A spaced final `--output` with a build
  directory without spaces also passes. Use the existing argument-vector execution API at
  the remaining string-command sites; this finding does not establish a final-output-path bug.

- **B32 — Open, P2: non-ASCII string literals produce wrong bytes or invalid LLVM IR.**
  [escapeString](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/package.scala)
  escapes UTF-16 characters as if each were one byte. Literal array lengths in
  [Literals](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Literals.scala)
  and [Module](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/Module.scala)
  likewise use character counts. A literal `λ` emits `[1 x i8] c"\3BB"`, which LLVM parses as
  two bytes and rejects; `😀` declares two bytes but emits six. Both global and expression
  literals are affected. `é` builds but prints byte `e9` instead of UTF-8 `c3 a9`; an ASCII
  control prints correctly. Escape encoded bytes and derive lengths from that same byte sequence.

- **B33 — Open, P2: editor compile commands do not decode file URIs.**
  [LspHandler.uriToPath](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala)
  strips `file://` and passes the percent-encoded remainder to path-based compilation.
  The VS Code extension sends `uri.toString()` to binary, AST, and IR commands. Files named
  `demo#file.mml`, `demo file.mml`, and `demo%file.mml` are therefore opened using literal
  `%23`, `%20`, and `%25` names. JDK filesystem reproductions fail with `NoSuchFileException`,
  while URI-decoded paths and an ordinary filename succeed. Use shared URI decoding rather
  than prefix stripping. This is source tracing plus a filesystem reproduction; editor execution
  was not run.

### Reproduction evidence for B28–B33

Independent checks confirm these findings at commit
`4cd89e56cbb47e8a52b3103235492b6b7eca66f0` on macOS arm64 with Clang/LLVM 23.1.1.
The unchanged compiler passes `sbtn testFull` (2,277 library and 9 CLI tests) and all eight
smoke fixtures. These passes do not cover the failing controls above.

For B28–B30, create a C fixture containing `#include "mml_runtime.c"` and one of these
`main` definitions, then compile from the repository root with
`clang -std=c17 -g -O0 -fsanitize=address -fno-omit-frame-pointer -I modules/mmlc-lib/src/main/resources fixture.c -o fixture`.
The observed failures come from direct runtime execution; compiler allocation and cleanup
paths were traced but these three cases were not run as newly compiled MML programs.
B28 was verified at O2 with AddressSanitizer and UndefinedBehaviorSanitizer; B29 at O0 with
AddressSanitizer; B30 at O0/O3 with AddressSanitizer and leak detection disabled.

```c
// B28: writing five bytes into a four-byte buffer triggers ASan.
int main(void) {
    Buffer b = mkBufferWithSize(4);
    buffer_write(b, (String){5, "abcde"});
    __free_Buffer(b);
    return 0;
}

// B29: destruction visits both uninitialized elements.
int main(void) {
    StringArray a = ar_str_new(2);
    __free_StringArray(a);
    return 0;
}

// B30: no output; inserting flush(b) before destruction prints the text.
int main(void) {
    Buffer b = mkBuffer();
    buffer_write(b, (String){11, "local-tail\n"});
    __free_Buffer(b);
    mml_sys_flush();
    return 0;
}
```

B32's compiler fixture is UTF-8 source. Compile it with `sbtn 'run fixture.mml'`;
replace both occurrences of `λ` with `A`, `é`, or `😀` for the controls described above.
Use `sbtn 'run run fixture.mml'` to inspect the successful controls' output bytes.

```mml
let greeting = "λ";
pub fn main(): Unit =
  println greeting;
  println "λ";
;
```

B33's filesystem check uses a real existing file `original`, obtains
`u = original.toUri().toASCIIString()`, and compares
`Files.readString(Path.of(u.substring("file://".length())))` with
`Files.readString(Path.of(new URI(u).getPath()))` for each filename listed above.

## Scope / Approval

Collect and update notes as development proceeds. Follow-up task creation and immediately relevant
protocol repairs are approved; unrelated repairs require their own plans. Review-checkpoint commits
are authorized. Broader protocol choices have
[discussion subtasks](protocols-design-discussions.md).

## Verification

B1/B2 are confirmed by source tracing through the LSP span mapping and CLI error printers.
B6 is confirmed by tracing the multiple-candidate branch in `RefResolver.resolveRef`;
its P3 rerun regression passes. B7/B8 failed in the first focused P3 run: 119 passed, three failed.
B7/B8 and all 21 grouping cases pass in the full run; that run failed 115 of 2,107 library tests
on B9, with 9 CLI tests passing. The corrected full run passed all 2,108 library and 9 CLI tests.
B3 passed P1 full-suite and independent review. B4/B5 are covered by the 31 passing
`ProtocolCheckerTests` and P2's full suite of 2,074 library and 9 CLI tests. See [Protocols](protocols.md).

## Signoff

- Workstream signoff: Ongoing note collection approved.
- Tracked item completion: Pending triage.
- Commit authorization: granted for the existing notes and follow-up definitions.

## Task Working Memory

Append short findings with impact, evidence, and fix status. B1/B2/B16 have separate existing-bug
tasks; B23 needs bounded parser reproduction. B14/B20/B21/B22/B24–B27 pass focused and full
verification: 366 focused cases, 2,277 library/9 CLI tests, eight smoke fixtures, benchmark compilation,
and all 52 memory fixtures. The [repair task](protocols-review-repairs.md) is complete and signed off.
Keep broad design choices in their discussion subtasks.
B28–B33 are independently confirmed and open for runtime/tooling triage. Reproductions and
controls are embedded above.

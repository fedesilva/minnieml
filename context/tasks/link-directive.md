# Module link directive

```mml
@link["one", "two", "z"];
```

One optional directive before all members. One or more library names; semicolon required.
Executable builds pass each name as `-l<name>` and let the target linker select the library.
Library builds discard the inputs and warn once per entry.

## Metadata

- **Owner:** Author
- **Status:** complete
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-10-04
- **Target Branch:** `dev-link-directive`

## Execution Checklist

1. [x] **complete** — Research parsing, compiler state, native declarations, and linking.
2. [x] **complete** — Implement directive parsing, diagnostics, and state collection.
3. [x] **complete** — Add executable link inputs and library-mode warnings.
4. [x] **complete** — Verify linking, add system-library and raylib samples, and document behavior.
5. [x] **complete** — [Generic aggregate C ABI lowering](#bugfix-small-struct-c-abi-lowering): shared target layout, complete-signature plans, and C interoperability verification.
6. [x] **complete** — Review link parsing and aggregate ABI lowering; save the verified checkpoint before integer migration.
7. [x] **complete** — Migrate default Int, runtime lengths/indexes, callers, samples, and docs to Int32.
8. [x] **complete** — Complete handoff checks, independent review, and signoff.

## Problem

MML can declare external functions with `@native`, but source modules need a way to specify
which libraries supply those functions at the executable link step.

## Outcome

A module can begin with `@link["one", "two"];`. The compiler collects ordered library names
in compiler state and supplies `-lone -ltwo` when linking an executable. Library discovery
and selection belong to the target linker.

## Rules

- A module accepts at most one optional `@link` directive, before every member.
- Its brackets contain one or more comma-separated nonempty string library names.
  The directive ends with a semicolon. Whitespace and ordinary line comments are allowed.
- A second directive, a directive after a member, or one inside an expression is an error.
- Entries are library names, not paths or arbitrary linker arguments. Names start with an
  ASCII letter, digit, or underscore, followed by letters, digits, underscores, dots,
  plus signs, or hyphens. `static` and `shared` qualifiers are unsupported.
- Each executable input becomes one `-l<name>` argument after the generated program input.
  Preserve order and repeated names; do not sort or deduplicate.
- Use the target linker's normal discovery and static/shared selection. Do not resolve
  library files in the compiler or add platform-specific lookup rules.
- Library builds still produce a relocatable `.o` and a separate runtime object. Discard
  every link input and emit one warning per entry, including repeats, with the exact message
  `linking directives are discarded in library mode`. The consumer supplies dependencies
  at its final link. No libraries are incorporated into the object.
- AST and IR output retain directive information without library lookup or linking.
- `@native` retains its signature and symbol-name behavior. The directive does not generate
  declarations or bindings.

## Scope

In scope: syntax, source diagnostics, AST representation, compiler-state collection,
one native compilation entry point, executable `-l` inputs, library-mode warnings, tests,
system-library and raylib samples in `mml/samples`, generic aggregate C ABI lowering,
architecture-specific Linux builders, the default Int32 migration, injected owned C-string
conversion, and language/compiler documentation.

Out of scope: explicit static/shared modes, partial linking, platform-specific library
lookup, MML imports or multi-module builds, shared-library output, dependency metadata
sidecars, package installation, binding generation, raw linker flags, library paths in the
directive, and a new library-search-path configuration interface.

## Implementation Plan

1. Parse a source-located header and entries. Recover malformed, duplicate, and misplaced
   directives without swallowing following declarations. Preserve member documentation.
2. Collect names and source spans in immutable compiler state and preserve the header through
   semantic rewrites. Show it in AST output and an IR comment.
3. Consolidate timed and untimed toolchain calls into one `compile` entry point returning
   the result and timings. Use `config.showTimings` internally; return no timing records
   when disabled. Pass ordered library inputs once from `CodegenStage`.
4. Append `-l<name>` arguments only at executable linking. Keep them out of runtime compilation,
   target probing, optimization, assembly, and object emission. Warn once per entry in library
   mode, preserving source locations and the existing object/runtime artifact behavior.
5. Add parser, state, linking, timing, and warning coverage. Add a small sample calling a common
   system-library function outside `mml_runtime.c`. Document syntax and output-mode behavior.
   Run applicable compiler checks and local post-chores before handoff.

Link-directive implementation approval: approved, including samples, library-mode warnings, and injected CString ownership.
Aggregate ABI and Int32 implementation: approved in two stages, with a review checkpoint between stages. Work remains on `dev-link-directive`.

## Bugfix: Small-struct C ABI lowering

**Status:** complete, including the Int32 migration and verification.
Native calls use target C ABI plans derived from field types, size, alignment, and the complete
function signature.

### Approved delivery stages

1. Calculate recursive LLVM target layouts once for ABI classification, allocation, and metadata.
   Classify complete signatures for x86-64 System V and Linux/Apple AArch64. Use immutable
   plans for native declarations, calls, wrappers, destructors, and generated runtime calls.
   Include register exhaustion, indirect arguments, returns, padding, and alignment. Reject
   unsupported ABIs. Verify generated C mixtures and nested aggregates against Clang and
   native field values; distinguish cross compilation from execution. Obtain stage signoff.
2. Make `Int = Int32`, including public lengths and indexes and IntArray storage. Preserve
   pointer widths, SizeT, explicit Int64, allocation sizes, and RNG state. Check narrowing,
   parsing, minimum signed values, formatting, float conversions, cloning, entry status,
   and runtime cache identity. Add int_to_int64 and int64_to_str for explicitly wide callers.
   Use bounded Int32 arithmetic in samples and benchmarks, with matching inputs and integer
   widths in the other languages. Replace raylib packed Color with four fields, retain
   automatic CString cleanup and the draw loop in main, and preserve zlib's actual C width.
   Verify fixtures and installed raylib, formatting, lint, full tests, smoke, publication,
   benchmark builds, memory checks, and independent review. The runtime ABI change requires
   rebuilding generated artifacts.

### Reproducer and evidence

Raylib's C declaration takes a four-byte value:

```c
typedef struct Color { unsigned char r, g, b, a; } Color;
void ClearBackground(Color color);
```

The corresponding MML binding is:

```mml
struct Color { r: Int8, g: Int8, b: Int8, a: Int8 };
fn clear_background(color: Color): Unit = @native[name="ClearBackground"];;
fn byte(n: Int): Int8 = @native[tpl="trunc i64 %operand to i8"];;
pub fn main(): Unit =
  clear_background (Color (byte 245) (byte 245) (byte 245) (byte 255));
;
```

The baseline reproducer on macOS arm64 with Clang/LLVM 23.1.1 emitted
`call void @ClearBackground(%struct.Color ...)`. The generated assembly places the
four bytes separately in `w0`, `w1`, `w2`, and `w3`. The C callee expects all four
bytes packed into one argument register. Linking succeeded despite the calling-convention
mismatch. `DrawText`, whose Color follows a pointer and three integer arguments, shared
the failure. The repair belongs in ABI classification and lowering, not library discovery
or a raylib-specific emitter rule.

[TargetLayout](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/TargetLayout.scala)
calculates recursive target storage layouts.
[NativeAbiPlan](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/abis/NativeAbi.scala)
classifies full signatures and supplies shared declaration and call lowering.

### Repair scope and acceptance

- Establish small-aggregate argument and return rules for supported AArch64 and x86-64
  targets against their C ABIs and Clang-generated reference calls.
- Implement the missing packing/unpacking through the existing ABI boundaries. Keep
  external declarations, call arguments, and affected return handling consistent; preserve
  field layout and ordinary MML struct behavior.
- Add C interoperability fixtures with sources in the test suite. Check four-byte Color
  arguments alone and after other arguments, small-struct return round trips, and relevant
  size/alignment boundaries. Assert field values in native execution, not merely valid IR.
- Exercise available targets and record execution gaps separately from cross-target IR or
  assembly checks. Preserve existing large-struct and other native-call coverage.
- Replace the packed-integer Color workaround in `mml/samples/raylib-hello.mml` with a
  four-field struct. Verify RAYWHITE `(245, 245, 245, 255)` and BLACK `(0, 0, 0, 255)`,
  text coordinates, and the draw/close sequence through C fixtures and actual raylib where
  available.

C-string ownership uses injected `CString`, allocating `to_cstr`, and consuming `free_cstr`
bound to C's `free`. The raylib sample relies on automatic scope cleanup. The `free=` type
attribute resolves an MML function binding; a native C symbol alone does not register a
destructor. No ownership-analyzer repair is required for this declaration pattern.

## Code Boundaries

- [Module parser](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/modules.scala)
  owns the header; [member parsing](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/members.scala)
  recovers misplaced directives.
- [Module](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/module.scala),
  [CompilerState](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CompilerState.scala),
  and [IngestStage](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/IngestStage.scala)
  preserve and collect the source-derived inputs before standard-library injection.
- [Native emission](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/Module.scala)
  supplies external declarations with the target ABI.
- [CodegenStage](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CodegenStage.scala)
  passes inputs to [LlvmToolchain](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala).
  Clang links executables through its existing `-fuse-ld=lld` invocation. Pass argument sequences,
  not a constructed shell command string.

## Verification

Required coverage:

- Absent directive, one/many names, repeated names, whitespace, comments, member doc comments.
- Empty list/name, non-string entry, qualifiers, malformed brackets/separators, missing semicolon,
  invalid names, duplicates, misplaced directives, and expression use. Preserve later declarations.
- Names, order, repeats, and source spans survive ingestion and semantic rewrites.
- Fixture-library native calls succeed with `-l`; missing libraries and symbols fail.
  Verify argument order and normal linker selection without depending on installed raylib.
- Timed and untimed calls use identical link inputs and return the same result; timings exist
  only when enabled.
- Library output succeeds without installed directive libraries, warns per entry with the exact
  message, and retains separate module and runtime objects. AST and IR do not warn or link.
- Keep fixture sources and instructions versioned; generate binary fixtures during tests.
- Run formatting, linting, full tests, smoke checks, sample execution, and applicable gates from
  [coding-rules.md](../coding-rules.md). Record unavailable target checks accurately.

Link-directive verification before the aggregate ABI stage, on macOS arm64 with Clang/LLVM 23.1.1:

- `sbtn ";scalafmtAll;scalafixAll;test"`: passed; 805 compiler-library tests and 9 CLI tests,
  with 39 existing ignored tests. The 29 new tests include parser recovery and native linking.
- `sbtn "run run mml/samples/link-zlib.mml"`: passed, printing `zlib compile flags: 681`.
- CLI library-mode source `@link["mml_missing_one", "mml_missing_two", "mml_missing_one"];
  let answer = 42;` compiled successfully and emitted exactly three warnings with the required
  text at the entry locations. No directive libraries were installed for this check.
- Native fixtures in
  [LinkingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/LinkingTests.scala)
  execute archive and shared-library calls, verify argument ordering and timing behavior,
  reject missing libraries/symbols, and emit objects without resolving discarded inputs.
  Fixture C and IR sources are embedded in that versioned test suite.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn mmlcPublishLocal`: passed; the tested compiler is installed locally.
- `make -C benchmark clean` and `make -C benchmark mml`: passed (build verification, not performance measurements).
- Parser recovery preserves same-line declarations after unterminated names, including
  string values containing `];`. A lexical quoted-name guard checks header punctuation and
  member boundaries without interpreting name contents as declarations. Closed invalid
  names remain one diagnostic, including declaration-like text with oversized numbers.
  `LinkDirectiveTests` passes 27/27: the malformed-name matrix covers 36 combinations, and
  12 closed-invalid-name cases check declaration-like text and following quoted values.
  Independent narrow re-review reports no actionable findings and independently confirms
  both closed-name regressions preserve `after` and `use` with one header diagnostic.
- Injected CString ownership is covered by the 45/45 memory-harness result below.

Aggregate ABI verification on macOS arm64 (Clang/LLVM 23.1.1) and the Linux builders
(Clang/LLVM 20.1.8):

- `sbtn ";scalafmtAll;scalafixAll;test"`: passed; 818 compiler-library tests and 9 CLI tests,
  with 39 existing ignores.
- `docker compose exec -T mml-linux-arm64 sbtn 'testOnly mml.mmlclib.codegen.AggregateAbiTests mml.mmlclib.codegen.LinkingTests'`:
  13/13 passed through Linux ARM64 execution in the Docker VM.
- `docker compose exec -T mml-linux-amd64 sbtn 'testOnly mml.mmlclib.codegen.AggregateAbiTests mml.mmlclib.codegen.LinkingTests'`:
  13/13 passed through emulated Linux x86-64 execution on the ARM host.
- [AggregateAbiTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/AggregateAbiTests.scala)
  generates 324 deterministic aggregate shapes. C fixtures assert actual fields through
  argument and return round trips, six scalar-prefix patterns, trailing arguments, nesting,
  padding, empty values, and homogeneous floating aggregates. Native executions run at O0
  and O2 with ASan/UBSan; the LLVM caller and generated adapters enable ASan instrumentation.
  Cross-target C and LLVM object compilation covers Linux/macOS on both architectures.
  Intel macOS has cross-compilation coverage only.
- The full-pipeline MML fixture passes on all three execution environments. It verifies
  native aggregate calls, function values, a large return with preceding scalar arguments,
  captured literal cloning, and exactly one native destructor call for an owned capture.
- Linux library interoperability exposed non-PIC assembly and assembly-only Clang flags.
  The toolchain emits PIC assembly and excludes optimization/sanitizer flags from assembly
  input compilation; native fixture linking uses the existing LLD pipeline.
- The memory regression in
  [closure-heap-capture.mml](../../tests/mem/closure-heap-capture.mml) exposed packing storage
  remaining live across recursion. A stack-save/restore implementation also failed host
  sanitizer checks. Native adapters own the final implementation's temporary storage;
  `sbtn 'run run -s -O0 -b build/abi-stack-check tests/mem/closure-heap-capture.mml'` passes
  all 100,000 calls. Tail-recursion lowering is unchanged; this fixture has cleanup after
  its recursive call and therefore uses ordinary recursion.
- `./tests/smoke/run.sh all`: 8/8 passed before publication.
- `sbtn mmlcPublishLocal`: passed; the verified compiler is installed locally.
- `make -C benchmark clean` followed by `make -C benchmark mml`: all 12 binaries built.
  This verifies compilation, not performance.
- `./tests/mem/run.sh all`: 45/45 passed with ASan and leak detection, including CString
  ownership and the 100,000-call closure-capture regression.
- Independent ABI review reports no actionable findings. The final parser-only revision
  passes `sbtn ";scalafmtAll;scalafixAll;test"`: 819 library tests, 9 CLI tests, and 39 existing
  ignores. Independent narrow re-review reports no actionable findings. The ABI
  implementation is unchanged from the container and memory runs above. Final smoke passes
  8/8, and `sbtn mmlcPublishLocal` installs the final tested compiler successfully.
- C fixture commands reuse `BaseEffFunSuite.commandNotFailed` and
  `LlvmToolchain.executeCommand`; the tests have no separate Clang process runner.
- [Linux builder instructions](../../packaging/docker/Readme.md) describe the explicitly
  named arm64 and amd64 services, platforms, and isolated build outputs.

The Author measured matmul after removing forced vector-interleave hints from
[benchmark/Makefile](../../benchmark/Makefile). Optimized nested MML tied with optimized
flat MML (1.00 ± 0.03) and optimized Rust (1.01 ± 0.03), and ran 1.53 ± 0.47 times faster
than optimized C. These are Author-reported measurements, separate from the compiler's
benchmark-build checks. The Makefile edit is included unchanged; a forced dry run confirms
all three optimized MML recipes omit the hint.

## Int32 migration verification

Default `Int` aliases `Int32`. String and array lengths, indexes, IntArray storage, and
runtime Int parameters/results use signed 32-bit values. SizeT, allocation byte counts,
explicit Int64, and internal RNG state retain their 64-bit widths. Minimum signed literals,
checked parsing and length narrowing, Int64 formatting, variable float conversions, entry
statuses, and runtime source cache identity have regression coverage in
[Int32Tests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/Int32Tests.scala).
Raylib uses a four-field Color and direct Int coordinates. Samples and benchmarks use
ordinary Int arithmetic with bounded inputs; zlib retains its actual C return width.

Verification on macOS arm64 with Clang/LLVM 23.1.1:

- `sbtn ';scalafmtAll;scalafixAll;test'`: 830 library tests and 9 CLI tests pass, with
  39 existing ignored tests.
- `./tests/smoke/run.sh all`: 8/8 pass.
- `clang -std=c17 -Wall -Wextra -Werror -fsyntax-only modules/mmlc-lib/src/main/resources/mml_runtime.c`:
  passes without warnings after the runtime's signed-index cleanup.
- Publication and the 45/45 ASan/LSan memory checks pass. Both Linux ABI builders pass
  all 24 tests in AggregateAbiTests, LinkingTests, and Int32Tests.

### Final review and handoff verification

- Independent review covers checkpoint `beae801` and the full Int32 migration, including
  runtime, tests, samples, tooling, benchmark implementations, and logged results.
  Its two confirmed findings are corrected: the documented zlib binding uses Int64,
  and the ignored PAP tests use i64 TBAA offsets with the Int32 field layout `0, 8, 12`.
  Narrow independent re-reviews report no remaining actionable findings.
- A fresh `sbtn test` passes 830 library tests and 9 CLI tests, with 39 existing ignores.
  After the assertion corrections, `sbtn 'testOnly mml.mmlclib.codegen.TailRecursionLoopificationTest mml.mmlclib.codegen.TbaaEmissionTest'`
  passes 22 tests, with 10 existing ignores. The corrected ignored assertions have static
  layout verification; they are not executed by that run.
- `sbtn ';scalafmtAll;scalafixAll'`, strict runtime C syntax checking, shell-script syntax
  checking, and `git diff --check` pass. The corrected zlib documentation example compiles
  and runs through the source compiler, printing `681`.
- The smoke, publication, benchmark-build, memory-harness, and Linux ABI results above
  cover the compiler implementation. Subsequent fixes affect documentation and dormant
  test assertions only. Linux checks were not repeated during the final review because
  the Docker socket was unavailable; the earlier arm64 and emulated amd64 passes remain
  the target-execution evidence. Intel macOS has cross-compilation coverage only.
- Both logged benchmark datasets contain nine groups, 64 configurations, and 8,960 timed
  executions, all with zero exit status. CSV means agree with JSON and raw timing samples.
  The [benchmark report](../../benchmark/results/report-2026-10-04-aarch.md) records the
  ABI-only and Int32 comparisons, checked-access costs, and historical architecture results.

### Int32 benchmark inputs and verification

Quicksort and matrix initialization use `(seed * 25173 + 13849) % 65536`, starting
from 42 (and 1337 for the second matrix). The largest intermediate is 1,649,726,404.
Quicksort stores the generated state directly. Matrix entries use `(seed % 100) - 50`;
for 500x500 matrices, the absolute trace is bounded by 625,000,000, including every
partial sum. Euclidean uses prime modulus 10007 and inputs 2 through 9999: products
are at most 100,120,036 and the checksum is at most 100,039,988. All fit signed Int32.
The input-seeded Big sample normalizes any Int seed before multiplication.

MML uses ordinary Int operators without native arithmetic helpers. C uses int32_t,
Go int32, Rust i32, and Java int for benchmark data and arithmetic; native indexing
types remain where their APIs require them. Python uses the same RNG and inputs.
Sieve, Ackermann, and N-Queens also use 32-bit data in their matching implementations.

Build and execution verification on macOS arm64:

- `make -C benchmark mml`: all 12 default MML executables build and run.
- Matching C, Go, Rust, Java JVM, and Java native executables build and run; Python
  quicksort and sieve run. All variants match the values below. Quicksort's oracle uses
  Python's built-in sort; Euclidean's uses modular `pow` and inverse; matrix trace uses
  arbitrary-precision dot products.
- All 10 matching C variants pass `-O2 -fsanitize=undefined -fno-sanitize-recover=all`;
  all five Rust variants pass `-O -C overflow-checks=yes`.
- Quicksort, matmul, Euclidean, bubblesort, and both random Big MML samples compile and
  run. The input-seeded sample also runs with 2147483647.
- Java native builds emit deprecation warnings for the existing `--no-fallback` option.
  These are build-option warnings, not arithmetic failures.

| Benchmark          | Verified result |
| ------------------ | --------------: |
| Quicksort median   |           32767 |
| Matrix trace       |         -286176 |
| Euclidean checksum |        50024579 |
| Sieve prime count  |           78498 |
| Ackermann(3, 10)   |            8189 |
| N-Queens, n = 12   |           14200 |

Historical performance reports describe different inputs and integer widths. These
checks establish output agreement and overflow safety, not new performance results.

## Risks / Notes

Native libraries must match the target architecture and ABI. This feature does not install
libraries or configure runtime search paths. The target toolchain environment must provide
libraries for executable linking; object consumers must supply their own final-link inputs.

## Signoff

- ABI checkpoint: verified and accepted; commit `beae801`.
- Workstream signoff: accepted for the module link directive, aggregate ABI lowering,
  CString ownership, Int32 migration, and matching benchmark changes.
- Tracked item completion: complete.
- Commit authorization: local completion commit, including verification records,
  benchmark datasets, and report. No push authorized.

## Task Working Memory

Implementation, verification, and independent review are complete. The benchmark report
records the remaining optimization costs; further LLVM optimization work is outside this
completed scope. Runtime ABI changes require rebuilding generated objects and libraries.

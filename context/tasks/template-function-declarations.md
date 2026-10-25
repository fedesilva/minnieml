# Emit public template functions and omit redundant declarations

## Metadata

- **Owner:** Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-23
- **Target Branch:** dev-2026-03-21-lambdas

## Execution Checklist

1. [x] **complete** — Omit template declarations from module and generated cleanup emission; retain public definitions.
2. [x] **complete** — Cover direct calls, function values, generated cleanup, public exports, and foreign declarations.
3. [x] **complete** — Complete compiler verification and independent review.

## Problem

Template bodies provide local implementations. Direct calls need inline expansion, function
values need closure entries, and public native-library exports need real definitions. An
external declaration alone supplies no implementation for a template function.

For example, `fn byte(n: Int): Int8 = @metal:tpl["trunc i32 %operand to i8"];;` needs
no external `byte` declaration: direct calls emit the truncation instruction, and a function
value renders it in a closure entry. With `pub`, the function also needs an exported definition
with its declared arguments and return type.

## Outcome

- Omit the redundant external declaration for a locally defined `@metal:tpl` function.
- Every `pub` function implemented by `@metal:tpl` emits a real function definition with
  its declared arguments and return type, and the rendered template as its body.
- Use the same public symbol naming and exported-function ABI as ordinary `pub` functions.
  The public definition has no closure environment parameter.
- Keep the public definition exposed in native library output after LLVM optimization,
  including when no code inside the library calls it.
- Direct calls, including generated cleanup calls, emit the template inline.
- Function values retain their closure entry, including the environment parameter;
  the entry emits the template rather than calling an external template-function symbol.
- `@metal:ffi` retains external declarations and its existing call behavior.
- External symbols referenced inside a template retain their required declarations.

## Scope

- In scope: module declaration and definition emission, template-body emission, and
  generated cleanup calls, with regression coverage for direct calls, function values, and
  public native-library exports.
- Out of scope: annotation syntax, closure ABI redesign, changes to foreign-call lowering
  or ownership, and automatic discovery of external declarations from template text.
- Out of scope: module access-control implementation and `.mlp` package serialization or loading.

## Plan (Approval Gate)

1. Distinguish template-bearing `NativeImpl` bodies before foreign declaration emission.
   Omit their redundant declarations and emit a real function definition for each public
   template function, using its declared signature, public symbol, and rendered template.
2. Add regression coverage for direct expansion, function-value entries, public definitions,
   and preserved foreign declarations. Link a separate caller against a native library
   containing an otherwise-unused public template function.
3. Regenerate IR for the animated A* sample and verify that `byte` has no external declaration.
4. Route generated destructor calls through the shared callable emitter, preserving public
   definitions and foreign ABI handling. Cover public and private template cleanup in IR and
   separate native callers.
5. Run applicable compiler checks and independent review.

Approval: implementation, verification, review, and task bookkeeping approved.

## Verification

Required checks:

- Non-public template functions have no redundant external declaration or unused standalone
  definition. Direct calls render the template at their call sites.
- Public template functions have a real definition with the expected public name, declared
  arguments and return type, and rendered template body. Their signatures have no environment
  parameter, and direct calls to them still expand the template.
- A public template function with no internal callers remains defined and externally visible
  in optimized native library output. A separately compiled caller links and returns the
  expected result at O0 and O3.
- Function values retain working closure entries with their environment parameter.
- Foreign declarations and external symbols referenced from template bodies remain available.
- Generated cleanup expands public and private template destructors without external declarations,
  before releasing the closure environment. Separate C callers observe the cleanup effects and
  invoke the public destructor definition directly at O0/O3.
- Animated A* IR contains no external declaration for `byte`.

### Implementation

- `emitBndLambda` in `codegen/emitter/Module.scala` emits foreign declarations only for
  `NativeImpl` bodies without a template. Public templates follow ordinary function emission;
  non-public templates need no standalone declaration or definition.
- `compileRegularLambda` in `codegen/emitter/FunctionEmitter.scala` renders template bodies
  through the same instruction emitter as direct calls and closure entries.
- Function template substitution matches complete operand and type placeholders in one pass.
  The ten-argument fixture checks `%operand10` and `%type10` in exports and direct calls;
  native callers verify its result at O0/O3.
- `ParameterLowering.arguments` supplies runtime operands to public bodies and closure entries.
  Unit parameters are omitted from runtime signatures; closure entries retain their environment.
- `callDestructor` in `codegen/emitter/DestructionEmitter.scala` passes its resolved binding and
  loaded operand to `compileCallableCall`, sharing template expansion, ordinary symbol naming,
  and native ABI handling with direct calls.
- These source files are under `modules/mmlc-lib/src/main/scala/mml/mmlclib/`.
- `TemplateFunctionCodegenTests.scala` under
  `modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/` checks emission, LLVM assembly, and
  separate C callers at O0/O3. `FunctionValueCodegenTests.scala` supplies additional closure
  execution coverage.

### Verification evidence

Host: macOS ARM64, Homebrew Clang/LLVM 23.1.1, with
`/opt/homebrew/opt/llvm/bin` first in `PATH`.

- Regression baseline at `ccdcf9e`: all five new tests fail; declaration assertions failed and
  separate C callers could not link the public template symbols at O0/O3.
- Cleanup regression baseline: both public/private IR tests fail on raw template symbols;
  both O0/O3 C callers fail to link `release_pub` and `release_priv` (four of eight tests fail).
- `sbtn "testOnly mml.mmlclib.codegen.TemplateFunctionCodegenTests mml.mmlclib.codegen.FunctionValueCodegenTests mml.mmlclib.codegen.DestructionCodegenTests"`:
  22 passed. IR fixtures assemble with `llvm-as`; native callers execute successfully.
- `sbtn "scalafmtAll; scalafixAll"`: passed without warnings.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn testFull`: 1,530 library tests and nine CLI tests passed; no failures or ignored tests.
- `sbtn mmlcPublishLocal`: passed.
- `make -C benchmark clean` and `make -C benchmark mml`: passed. These are compilation
  checks, not performance measurements.
- `./tests/mem/run.sh all`: 52/52 passed with ASan and leak detection enabled.
- `mmlc ir mml/samples/astar3_animated.mml`: passed. The generated IR has 48 inline
  `trunc i32 ... to i8` instructions, no external `byte` declaration or standalone definition,
  and the `ClearBackground` foreign declaration. `llvm-as` accepts the generated module.

Linux verification uses Clang/LLVM 20.1.8 and executes fixtures inside each container.
The selected suites are `TemplateFunctionCodegenTests`, `FunctionValueCodegenTests`,
`DestructionCodegenTests`, `AggregateAbiTests`, and `FunctionSignatureTest`, all under
`mml.mmlclib.codegen`:

```sh
docker compose exec -T <service> sbtn 'testOnly mml.mmlclib.codegen.TemplateFunctionCodegenTests mml.mmlclib.codegen.FunctionValueCodegenTests mml.mmlclib.codegen.DestructionCodegenTests mml.mmlclib.codegen.AggregateAbiTests mml.mmlclib.codegen.FunctionSignatureTest'
```

- `mml-linux-arm64`: 70 passed; `aarch64-unknown-linux-gnu`, native ARM64 execution
  in the Linux VM.
- `mml-linux-amd64`: 70 passed; `x86_64-pc-linux-gnu`, with Docker emulation on ARM64.

### Independent review

Primary review and narrow re-reviews completed with fresh, separate code-style/QA,
test-quality, and readability reviewers. Confirmed findings covered complete placeholder
substitution, explicit private fixtures, documentation of runtime argument numbering, and
reuse of inherited LLVM assertion helpers. Each finding received separate fresh verification,
and each correction passed narrow independent re-review. No actionable findings remain.

Cleanup source review confirms shared call emission, preserved field ordering, and environment
release after captured values. Its final test-only correction selects the stored environment
destructor through shared helpers. Formatting, lint, and all eight template tests pass for this
correction. Production code and native fixtures are unchanged by the helper edit; the broader
host and Linux gates above cover that production code. Reviewers inspected source and successful
logs; the cleanup reviews did not independently rerun the compiler tests.

Native token checks establish observable cleanup effects. They do not alone prove exactly-once
execution. LLVM assembly checks IR validity; sanitizer results apply to executed harness paths.

## Future MML packages

The [`.mlp` package design](../../docs/brainstorming/compiler/reactive-data-oriented-compiler/multi-module-1.md#external-packages-mlp)
contains serialized, typed ASTs, including function bodies. Consumers can load template
bodies and expand direct calls across package boundaries. A package boundary alone does
not require a native exported definition; native library exports are the concern here.
Package support is outside this task.

## Dependencies

The required [metal annotation syntax](ffi-and-llvm-annotations.md) is complete.

## Signoff

- Workstream signoff: approved.
- Tracked item completion: complete.
- Commit authorization: local task commit approved.

## Task Working Memory

Public template definitions use ordinary function emission. Direct calls, closure entries, and
generated cleanup render templates through shared emission. Public and private cleanup tests
check inline instructions, absence of external declarations, cleanup order, and native effects.
A separate C caller also invokes the public destructor definition at O0/O3.

Formatting, lint, 22 focused tests, eight smoke checks, 1,530 library tests, nine CLI tests,
publishing, and benchmark compilation pass. All 52 memory tests pass ASan and leak detection.
Both Linux containers pass 70 selected tests each. Animated A* IR assembles with 48 truncations
and no `byte` declaration or definition. The final assertion-helper correction passes all eight
template tests and narrow independent re-review. Required review checks are complete with no
open findings. Workstream signoff is approved; no task work remains.

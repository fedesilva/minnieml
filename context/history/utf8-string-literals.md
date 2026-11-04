# UTF-8 string literals

Verification date: 2026-11-04. Bug note: B32.

## Outcome

[Literal registration](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala)
encodes source content as UTF-8 once. Global and expression literals share the resulting bytes
for LLVM constants, escaping, and String lengths. String operations retain byte semantics;
Unicode character operations and normalization remain outside this repair.

The [language reference](../../docs/language-reference.md) documents UTF-8 literal encoding and
byte-based lengths and offsets. The
[compiler design](../../docs/design/compiler-design.md) documents shared constant registration.

Repair signoff: granted. Local commit and squash delivery to `main`: authorized.

## Historical reproduction

At `4cd89e56cbb47e8a52b3103235492b6b7eca66f0`, UTF-8 source containing the following
global and expression literals exposes character-based LLVM emission:

```mml
let greeting = "λ";
pub fn main(): Unit =
  println greeting;
  println "λ";
;
```

Compile with `sbtn 'run fixture.mml'`. Replacing both occurrences of `λ` with `A`, `é`,
or `😀` supplies ASCII, two-byte UTF-8, and supplementary-character controls. Use
`sbtn 'run run fixture.mml'` to inspect output bytes for controls that compile.

The ASCII control prints correctly. `é` prints byte `e9` instead of UTF-8 `c3 a9`.
`λ` emits `[1 x i8] c"\3BB"`, which LLVM parses as two bytes and rejects;
`😀` declares two bytes but emits six. Both global and expression literals are affected.

Against production code from `9f4d22201071b4b17fc4eea6a45b9068972fb7c7`, the added
[regression suite](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/StringLiteralEncodingTests.scala)
run with `sbtn 'testOnly mml.mmlclib.codegen.StringLiteralEncodingTests'` passes the
ASCII/empty control and fails both Unicode cases at O0/O3 during LLVM assembly. The mixed
literal declares six bytes while its escapes produce eleven, yielding a constant type mismatch.

## Verification

Host: macOS arm64, Clang/LLVM 23.1.1, target `arm64-apple-darwin25.6.0`,
GraalVM Java 25.0.4.1, sbt 2.0.10 through `sbtn`.

- `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.codegen.StringLiteralEncodingTests'`
  passes all three cases after the repair. Tests check global and expression byte lengths,
  exact output bytes, raw backslashes, multiline content, empty strings, clones, and concatenation.
  Unicode executions use ASan/LSan at O0/O3. Expected bytes and lengths are literal test data.
- `./tests/smoke/run.sh all` passes all eight fixtures.
- `sbtn 'scalafmtAll; scalafixAll; testFull'` passes formatting, lint, all 2,523 library tests,
  and all 9 CLI tests. No Scala compiler warnings are reported; the launcher reports multiple
  main classes.
- `sbtn mmlcPublishLocal` succeeds after smoke execution.
- `make -C benchmark clean` and `make -C benchmark mml` succeed, producing all 37 binaries.
  This establishes benchmark compilation, not execution-time performance.
- `./tests/mem/run.sh all` passes all 52 ASan/LSan fixtures in 73 seconds.
- Source/test QA enforcement, tracking consistency, local links, and `git diff --check` pass.
- Fresh independent primary review and all four dedicated checks (code style/QA, test quality,
  readability, and code documentation) complete with no actionable findings. No candidates
  require claim verification. Review is static and uses the execution evidence above.

No ABI layout or calling-convention changes require Linux container verification. Native execution
evidence covers macOS arm64; no cloud run is dispatched. Sanitizers cover executed paths.

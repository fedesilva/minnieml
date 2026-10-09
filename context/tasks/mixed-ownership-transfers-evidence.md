# Mixed-ownership investigation evidence

Supporting evidence for [Mixed-ownership transfers, cleanup, and returns](mixed-ownership-transfers.md).
Task scope, implementation approval, and current progress belong to that task.

## Current baseline

- **Date:** 2026-10-08.
- **Repository commit:** `4194c17` on `dev-2026-03-21-lambdas`.
- **Execution:** native macOS ARM64, Homebrew LLVM/Clang 23.1.1.
- **Compiler:** current repository through sequential `sbtn` invocations, `-s -O0`.
- **Runtime:** `ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1:symbolize=0`.
- **Limits:** no `-O3`, Linux, full-suite, or repair verification in this investigation.
  Forked CLI runs emitted the existing JDK `sun.misc.Unsafe` deprecation warning.
  The original sample's ASan run had symbolizer warnings; subsequent probes disabled
  symbolization and retained the sanitizer error categories.

The existing [mixed-test-double-free.mml](../../mml/samples/mixed-test-double-free.mml)
compiled with `-s -O0 -a -I` and failed with ASan `attempting double-free` (exit 134).
The runtime-selected probes below confirm both ownership outcomes without rebuilding
between executions.

| Probe | Allocated outcome | Static outcome |
| --- | --- | --- |
| `named` | ASan double-free; SIGABRT | ASan BUS during deallocation; SIGABRT |
| `scoped` | ASan double-free; SIGABRT | ASan BUS during deallocation; SIGABRT |
| `return` | ASan heap-use-after-free; SIGABRT | ASan BUS during deallocation; SIGABRT |
| `escape` | Exit 0, no sanitizer diagnostic | LSan leak; exit 1 |
| `direct` | Exit 0, no sanitizer diagnostic | Exit 0, no sanitizer diagnostic |
| `field` | ASan double-free; SIGABRT | ASan BUS during deallocation; SIGABRT |
| `capture` | ASan double-free; SIGABRT | ASan BUS during deallocation; SIGABRT |
| `pap` | ASan double-free; SIGABRT | ASan BUS during deallocation; SIGABRT |

`alias` is rejected during semantic analysis: the ownership sink reports borrowed ownership,
followed by `Cannot pass borrowed value 'alias' to consuming parameter 'text'`.
This is a valid mixed owned/static transfer that loses its ownership information on rebinding.
`borrowed` is correctly rejected at `take value` because its alternatives include an actual
borrowed parameter. That rejection is a control to preserve.

`branch` uses two independent choices encoded by argument count:

| Arguments | Storage | Operation | Result |
| ---: | --- | --- | --- |
| 0 | Allocated | Consume | ASan double-free; SIGABRT |
| 1 | Static | Consume | ASan BUS during deallocation; SIGABRT |
| 2 | Allocated | Borrow | ASan double-free; SIGABRT |
| 3 | Static | Borrow | ASan BUS during deallocation; SIGABRT |

### Reproduction procedure

Every complete source is embedded below. Copy a selected code block to a source file before
compilation; no external scratch files or saved binaries are required. For example, create
`build/ownership-probes/named.mml` from the `named` block, then run from the repository root:

```sh
sbtn 'run -s -O0 -a -b build/ownership-probes/named build/ownership-probes/named.mml'
env ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1:symbolize=0 build/ownership-probes/named/target/named
env ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1:symbolize=0 build/ownership-probes/named/target/named static
```

Use each probe's name for its source and build directory. Positive probes are intended to
exit zero without sanitizer errors after repair; `borrowed` must remain rejected. PAP capture
acceptance follows the task's separate capture policy gate, not the positive-call expectation.
For `branch`, execute with zero through three arbitrary arguments. Other runtime probes use
zero arguments for the allocated/true outcome and one for the static/false outcome.

## Embedded probes

### named

```mml
fn take(~text: String): Int = text.length;;
fn check(flag: Bool): Int =
  let value = if flag then int_to_str 123; else "abc";;
  take value;
;
pub fn main(args: StringArray): Int = check (ar_str_len args == 0) - 3;;
```

### scoped

```mml
fn take(~text: String): Int = text.length;;
fn check(flag: Bool): Int =
  take (if true then let value = if flag then int_to_str 123; else "abc";; value; else "def";);
;
pub fn main(args: StringArray): Int = check (ar_str_len args == 0) - 3;;
```

### alias

```mml
fn take(~text: String): Int = text.length;;
fn check(flag: Bool): Int =
  let value = if flag then int_to_str 123; else "abc";;
  let alias = value;
  take alias;
;
pub fn main(args: StringArray): Int = check (ar_str_len args == 0) - 3;;
```

### return

```mml
fn pick(flag: Bool): String =
  let value = if flag then int_to_str 123; else "abc";;
  value;
;
fn check(flag: Bool): Int =
  let result = pick flag;
  if str_eq result (if flag then "123"; else "abc";) then 3; else 0;;
;
pub fn main(args: StringArray): Int = check (ar_str_len args == 0) - 3;;
```

### escape

```mml
fn pick(flag: Bool): String =
  let text = int_to_str 123;
  let alias = text;
  if flag then alias; else "abc";;
;
fn check(flag: Bool): Int =
  let result = pick flag;
  if str_eq result (if flag then "123"; else "abc";) then 3; else 0;;
;
pub fn main(args: StringArray): Int = check (ar_str_len args == 0) - 3;;
```

### branch

```mml
fn take(~text: String): Unit = println text;;
pub fn main(args: StringArray): Int =
  let argc = ar_str_len args;
  let value = if argc % 2 == 0 then int_to_str 123; else "abc";;
  if argc < 2 then take value; else println value;;
  0;
;
```

### direct

```mml
fn take(~text: String): Int = text.length;;
fn check(flag: Bool): Int =
  take (if flag then int_to_str 123; else "abc";);
;
pub fn main(args: StringArray): Int = check (ar_str_len args == 0) - 3;;
```

### borrowed

```mml
fn take(~text: String): Int = text.length;;
fn check_borrowed(flag: Bool, text: String): Int =
  let value = if flag then int_to_str 123; else text;;
  take value;
;
fn check(flag: Bool): Int = check_borrowed flag "abc";;
pub fn main(args: StringArray): Int = check (ar_str_len args == 0) - 3;;
```

### field

```mml
struct Box { text: String };
pub fn main(args: StringArray): Int =
  let value = if ar_str_len args == 0 then int_to_str 123; else "abc";;
  let box = Box value;
  box.text.length - 3;
;
```

### capture

```mml
pub fn main(args: StringArray): Int =
  let value = if ar_str_len args == 0 then int_to_str 123; else "abc";;
  let read = ~{ value.length; };
  read () - 3;
;
```

### pap

```mml
fn take(~text: String, n: Int): Int = text.length + n;;
pub fn main(args: StringArray): Int =
  let value = if ar_str_len args == 0 then int_to_str 123; else "abc";;
  let partial = take value;
  partial 0 - 3;
;
```

## Code trace at the baseline

The following observations concern
[OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala)
at `4194c17`. Symbol names identify the relevant code independently of later line shifts.

- `BindingInfo` and `withMixedOwnership` combine `Owned` with an optional witness.
  `argNeedsClone` and `handleConsumingParam` accept `Owned` without using that witness.
- `isMoveOnRebind` excludes witnessed bindings. A local mixed alias therefore falls into
  borrowed binding handling rather than transferring the complete cleanup obligation.
- `analyzeLambdaApplication` emits a separate witness cleanup after calculating ordinary
  frees. That path does not consult the final move/escape state.
- `analyzeCond` creates complementary-branch cleanup with `witness = None`, losing the
  original ownership condition when only one branch consumes.
- `ReturnOwnershipAnalysis` merges conditional results into `Option[Type]`.
  `promoteStaticBranchesInReturn` then treats named mixed results as already owned.
- `returnedOwnedNames` takes origins from both result branches; using that union to suppress
  cleanup cannot release a local on the branch that returns a different result.

Emitted LLVM corroborates the failures: `named_check` calls `named_take` and then emits a
witness-guarded free of the same String. `return_pick` emits a witness-guarded free before
returning the selected String. `escape_pick` allocates before the conditional and returns
an owned local or a cloned literal without releasing the unused local on the literal path.

Owning constructor fields use the consuming-parameter path. Move captures are different:
`analyzeLambda` accepts an `Owned` capture without consulting its witness, while
[ClosureDestructorBodyGenerator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ClosureDestructorBodyGenerator.scala)
releases ordinary non-borrowed capture fields unconditionally.
[PartialApplicationElaborator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/PartialApplicationElaborator.scala)
turns supplied consuming arguments into transferred captures before ownership analysis.
These paths explain why a call-only repair cannot claim capture correctness.

## Scoped-transfer historical evidence

Evidence collected on 2026-09-11, macOS ARM64, uses this complete source:

```mml
fn take(~text: String): Int = text.length;;
fn check(flag: Bool): Int =
  take (if true then let value = if flag then int_to_str 123; else "abc"; ; value; else "def";);
;
pub fn main(): Int = check true - 3;;
```

Changing only `check true` to `check false` selects the literal branch. The recorded builds
used sanitizer instrumentation and optimization level zero, with
`ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1`.

- Allocated branch: exit 134 and ASan `attempting double-free`. The recorded first free
  occurs in `check`; the second is `take -> __free_String -> free`. LLVM shows witness-guarded
  destruction before the result reaches `take`.
- Literal branch: exit 134 and ASan BUS during `take -> __free_String -> free`; LLVM passes
  literal-backed storage without a clone.
- Independent verification reproduced both failures. Source comparison found the witness
  cleanup block already present at `234b6e1`; the exact reproducer was not executed against
  a rebuilt compiler at that older commit. This establishes a code-history observation,
  not a runtime result for that commit. The associated 41-fixture harness omitted this case.

## Conditional return-lifetime evidence from A*

The completed caption repair has a separate probe that allocates before a conditional and
returns that allocation only on one path:

```mml
fn pick(flag: Bool): String =
  let text = int_to_str 123;
  let alias = text;
  if flag then alias;
  else "abc";
  ;
;
pub fn main(): Unit = println (pick false);;
```

The A* string-return repair verification records a four-byte leak for `pick false` under
ASan/LSan at both `-O0` and `-O3`. Its escape check exempts the local from cleanup when any
return path references it, without releasing that local on the other path. The caption
repair's bounded alias regression allocates inside its returning branch and verifies that
its returned owned value is neither cloned nor freed in the callee. That completed coverage
does not resolve the allocation-before-conditional obligation.

The `escape` probe above independently confirms the non-returning-path leak at `4194c17`
with `-O0`, while its returning path exits zero. The historical optimized result is preserved
as evidence; no fresh optimized run is claimed.

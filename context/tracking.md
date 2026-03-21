# MML Task Tracking

## CRITICAL Rules

------------------------------------------------------------------------
/!\ /!\ Do not edit without explicit approval or direct command. /!\ /!\
/!\ /!\ Follow rules below strictly                              /!\ /!\
/!\ /!\ COMPLETING A TASK? -> ADD [COMPLETE] TAG. NEVER DELETE.  /!\ /!\
------------------------------------------------------------------------

* *Always read* `context/task-tracking-rules.md` 
  *before* working with this file - even if you read it before, 
  it might have changed in the meantime.

* Follow the rules stated above.
* These rules are mandatory unless the Author explicitly overrides them.

## Active Tasks

### #243 Bug: isMoveOnRebind does not move native heap types [COMPLETE]

- GitHub: https://github.com/fedesilva/minnieml/issues/243
- [x] Fix `isMoveOnRebind` to handle native heap types (String, Buffer, arrays)
- [x] Add tests for String/Buffer/array move-on-rebind
- [x] Verify samples still pass

### #188 Literal lambdas and captures

- GitHub: https://github.com/fedesilva/minnieml/issues/188
- [ ] Implement parsing support for literal lambdas
- Reference: `docs/brainstorming/language/lambda-syntax-design.md`
- [ ] integrate captures into the memory system


## Recent Changes

- 2026-03-21: Fix #243: `isMoveOnRebind` now moves native heap types [COMPLETE].
  - Changed `isMoveOnRebind` to use `TypeUtils.isHeapType` instead of `isStructWithHeapFields`,
    so rebinding native heap types (String, Buffer, arrays) transfers ownership.
  - Updated `"string rebinding still borrows"` test to `"string rebinding moves ownership"` —
    now asserts `UseAfterMove` when original is used after rebind.
  - Added `"string rebinding without use-after-move is valid"` and
    `"string rebinding target gets freed"` tests.
  - All 307 tests pass, 17/17 memory tests pass (ASan+LSan), benchmarks compile.


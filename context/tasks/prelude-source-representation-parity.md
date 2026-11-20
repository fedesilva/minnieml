# Make synthetic prelude definitions expressible in MML

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Created:** 2026-11-16
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Complete the source-form survey and approve a bounded implementation plan.
2. [ ] **planned** — Implement native void and pointers to resolved MML types.
3. [ ] **planned** — Reconcile other transcription gaps and verify the complete prelude parses.
4. [ ] **planned** — Complete compiler verification and obtain signoff.

## Tags

- parser
- native-types
- prelude

## Problem

The [prelude transcription](../../modules/mmlc-lib/src/main/resources/prelude.mml)
contains declarations the source parser cannot accept. Synthetic injection constructs AST
nodes directly, bypassing source grammar and identifier restrictions. A comment acknowledging
unsupported syntax does not make the file a faithful, usable MML transcription.

## Outcome

Express the synthetic prelude's native representations in valid MML, preserving their meaning.
Use MML type names for named pointees and derive LLVM names in the compiler:

```mml
type Unit = @metal:repr[t=void];
type StringPtr = @metal:repr[t=*String];
```

The full prelude must parse without syntax diagnostics. Any remaining difference from the
synthetic definitions must be identified explicitly and resolved within an approved scope.

## Scope

- Support `void` as a native representation for Unit.
- Support pointers to resolved MML types, including `String`, rather than requiring source
  code to spell the backend name `%struct.String`.
- Preserve existing numeric pointee forms, native metadata, and generated representations.
- Survey all prelude declarations for source/AST mismatches, including identifier rules,
  native bindings, ownership markers, protocols, operators, and templates.
- Correct the transcription and its unsupported-syntax comment when support is verified.
- Loading the prelude during compilation and replacing synthetic injection remain in
  [Compile the MML prelude](compile-prelude.md).

## Survey evidence

Source inspection establishes the following cases; this is not a successful parser run.

| Case | Evidence and required work |
| --- | --- |
| `Unit`, represented as `void` | Injection constructs `NativePrimitive("void")`; the native primitive grammar omits `void`. Add deliberate support without implicitly permitting invalid void pointees. |
| `StringPtr`, represented as `%struct.String` internally | Injection constructs a `NativePointer` with a raw LLVM string. The pointer grammar accepts numeric pointees only. Model and resolve `*String` as an MML type reference, then derive its native representation. |
| Other injected primitive/pointer representations | The injector uses `i1`, `i8`, `i16`, `i32`, `i64`, `float`, `double`, `ptr`, and numeric pointees `i8`, `i32`, `i64`, `float`. These spellings are covered by the grammar; no additional representation-token gap was found in this inventory. |
| Destructor binding names | The transcription declares `__free_String`, `__free_Buffer`, `__free_Rng`, `__free_IntArray`, `__free_StringArray`, and `__free_FloatArray`. Binding identifiers must start lowercase; recovery classifies leading underscores as invalid. Resolve the source naming policy and destructor lookup implications before changing names or widening the grammar. |
| Remaining declaration forms | Native implementation parsing supports `@metal:ffi`, `@metal:tpl`, and attributes. Complete a declaration-by-declaration comparison and full-file parse check; token support alone does not establish transcription parity. |

Evidence sources:

- [Synthetic type definitions](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala)
- [Native representation grammar](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/types.scala)
- [Binding identifier grammar](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/identifiers.scala)
- [Native implementation grammar](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala)
- [Native type AST](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/types.scala)

## Plan (Approval Gate)

- [ ] Finish the survey and settle named-pointee resolution, alias handling, supported target
  types, invalid-target diagnostics, and destructor source naming.
- [ ] Extend the AST, parser, resolution, and emission paths as required for native void and
  MML named pointees. Keep backend names out of the source spelling.
- [ ] Reconcile the prelude declarations with the injectors and update relevant language docs.
- [ ] Add focused regressions and a full-prelude parsing check, then run applicable compiler gates.

Approval: pending for implementation; task definition and low-priority tracker placement authorized.

## Verification

Source inspection only. No compiler tests run for task creation.

Required implementation evidence:

- Accepted native void and named-pointee declarations produce the intended AST and native types.
- Unknown or invalid pointee types receive useful diagnostics; existing numeric forms still work.
- The complete prelude parses without recovery errors, not merely a successful outer parse result.
- Transcribed declarations preserve names or approved mappings, native symbols, ownership,
  allocation metadata, and templates from the synthetic definitions.
- Applicable compiler verification from the repository coding rules passes.

## Risks / Notes

Changing `NativePointer` from a raw backend string to a resolved source type affects more than
parsing. Inspect its traversal, resolution, and emission consumers. Do not make `%struct.String`
accepted source syntax merely to reproduce the internal string.

Destructor naming requires an explicit decision: silently renaming declarations can break
convention-based lookup. Additional survey findings must be stated concretely before expanding
the implementation plan.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Low-priority work, ordered last in the tracker. The two native representation gaps and the
leading-underscore destructor names have source evidence above. Next: finish the full declaration
survey and obtain approval for the bounded implementation plan. Prelude ingestion is separate.

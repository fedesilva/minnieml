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

* *Always read* `context/coding-rules.md` 
  before working with this file - even if you read it before, 
  it might have changed in the meantime.

* Follow the rules in those documents.
* These rules are mandatory unless the Author explicitly overrides them.

## Active Tasks

### Memory Management

Affine ownership with borrow-by-default. Enables safe automatic memory management.

GitHub: `https://github.com/fedesilva/minnieml/issues/134`

- Borrow by default, explicit move with `~` syntax
- OwnershipAnalyzer phase inserts `__free_T` calls into AST
- No codegen changes - just AST rewriting

- [ ] **Define and enforce global borrow-only ownership semantics**: document and implement
  no-move behavior for top-level bindings passed to consuming params.
  See `context/spaces/mem-globals-no-move.md`.

### QA Debt

Quality debt tasks tracked from QA misses.

GitHub: `https://github.com/fedesilva/minnieml/issues/235`

- [ ] **Define semantic detection for ownership free/clone assertions**: follow
  `context/qa-misses.md` section `2026-02-15 - Top-priority brittle string-name assertions in OwnershipAnalyzerTests`
  and decide resolved-id/type-aware checks for `__free_*` / `__clone_*` assertions instead of
  raw name string matching.

- [ ] **Refactor noisy wildcard AST matcher patterns with extractors**: follow
  `context/qa-misses.md` section `2026-02-15 - Brittle deep wildcard pattern matching in OwnershipAnalyzerTests helpers`
  and introduce extractor-first handling for repeated noisy match patterns in tests, or perform a
  focused review with documented rationale where extractors are not appropriate.

### LSP Logging

Bound LSP server log growth using startup-time rotation and prepare runtime cache setup flow.

GitHub: `https://github.com/fedesilva/minnieml/issues/223`

Spec: `context/specs/lsp-log-rotation.md`

- [ ] **Implement size-based LSP log rotation at startup**: before opening `server.log`,
  rotate when file size exceeds 5 MB using `server.log.1`, `.2`, `.3` up to 10 items, then delete.

### Entry Point Parameters

Pass parameters at the entry point when the internal function signature requires them,
using a monomorphic array.

GitHub: `https://github.com/fedesilva/minnieml/issues/200`

- [ ] **Pass parameters if internal entry point takes them (use monomorphic array)**.

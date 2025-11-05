# MML Active Context

## Current Focus

- TypeChecker multi-argument validation overhaul in progress; core fix merged, semantic app rewriting tests need alignment with new typing.
## Issues

### High priority

**Multi-Argument Function Type-Checking Bug** (TypeChecker.scala:421)

**Problem**: `determineApplicationType` only validates the first parameter of multi-argument functions and immediately returns the final return type, instead of returning a function type for the remaining parameters. This causes subsequent arguments to bypass type validation entirely.

**Example**:
```mml
fn mult(a: Int, b: Int): Int = ???;
let bad = mult 1 "oops";  # Should fail but passes
```

**Root Cause**:
1. When checking `mult 1` (where `mult: (Int, Int) → Int`):
   - Code checks first param: `Int` matches `1` ✓
   - **BUG**: Returns `Int` (final return type) instead of `Int → Int` (remaining function type)

2. When checking `(mult 1) "oops"`:
   - Inner app has `typeSpec = Int` from step 1
   - Hits catch-all case at line 541: returns `Int` without validating `"oops"`

**Location**: `TypeChecker.scala:421-451` (FnDef case) and line 541-543 (innerApp case)

**Impact**: Multi-argument functions accept arguments of any type after the first parameter, breaking type safety.

### Medium Priority

## Next Steps

### Fix Multi-Argument Type Checking

1. **FnDef application typing** ✅:
   - Remaining-parameter TypeFn construction added; multi-argument calls now validate each argument and return reduced function types until all params are consumed.

2. **Nested application handling** ✅:
   - Inner apps unpack TypeFn results, validate the next argument, and propagate updated function or return types.

3. **Operator parity** ⏳:
   - BinOp partial application returns remaining function type; update Unary/Bin tests and verify native operator paths.

4. **Regression coverage** ✅/⏳:
   - Added TypeChecker tests for wrong later-argument types and partial applications. AppRewritingTests now fail because semantics surface new TypeErrors—update expectations or fixtures before rerunning suite.

## Recent Changes

- Docs: Source tree overview consolidated into `docs/design-and-semantics.md` Appendix A; `context/systemPatterns.md` now references updated compilation flow.
- Unit type vs value
     * `Unit` is the type, `()` is the only inhabitant of that type.
     * Parser now rejects `()` in type positions; use `Unit` in annotations.
     * Samples and tests updated so Unit annotations remain only where required (e.g. native stubs).
- Semantic phases solidified: TypeChecker now lowers ascriptions, infers return types, validates calls, and surfaces errors consistently; TypeResolver covers alias chains and nested refs.
- Parsing and expression handling hardened: parser modules reorganized for identifiers, literals, modules, with better invalid-id reporting; expression rewriter now normalizes operator precedence and auto-calls nullary functions.
- LLVM codegen reworked: native op descriptors drive emission, literal globals become static definitions, string/multiline handling cleaned up, boolean ops emit direct LLVM instructions.
- Tooling and docs refreshed: design/semantics guide rewritten to match pipeline, AGENTS guidance updated, new Neovim syntax package and scripts added.
- Samples and tests updated: sample programs align with new semantics, grammar/semantic/codegen suites broadened to cover native types, operator precedence, and type inference paths.


### Future work        

* modules
* recursion 
* protocols 

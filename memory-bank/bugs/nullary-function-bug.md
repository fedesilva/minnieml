# 🐞 Nullary Function Call Is Not Lowered to IR Call

## Summary

The compiler fails to correctly lower nullary function applications when used in value position. Instead of generating a function call, it emits an invalid memory load from the function symbol, resulting in undefined behavior at runtime.

## Reproduction

```mml
fn println (a: String): () = @native;
fn to_string (a: Int): String = @native;

fn make_test_results(): String = to_string 42;

fn main(): () = println (make_test_results);
```

## Expected IR

```llvm
%0 = call %String @make_test_results()
call void @println(%String %0)
```

## Actual IR

```llvm
%0 = load %String, %String* @make_test_results
call void @println(%String %0)
```

## Impact

* The binary executes without crashing but prints nothing.
* The IR treats the function as a pointer to a `%String`, which is semantically invalid.
* This introduces silent logic errors and breaks composability.

## Root Cause

* The expression rewriter does not insert a function call for nullary function references.
* The typechecker fails to reject passing a `(): T` function where a `T` value is expected.

## Recommendations

* **Expression Rewriter**: rewrite references to nullary functions in value position to `App(fn, UnitLiteral)` or equivalent.
* **Typechecker**: reject `(): T` wherever `T` is required.
* **Optionally Parser**: require explicit `()` for nullary function calls, or desugar at parse time.

## Notes

This affects all nullary functions used as values. The issue is not fixed by adding parentheses (`(make_test_results)`), since the rewriter still fails to appify the call.

Source: 

# Owned aggregate return ABI

## Problem

---
fede note: 
no, I refuse to pollute the mml code with out params.
that is not the api I want and will not follow through.
I am leaving this document here, because I still need to think 
about why matmult-opt is slower under aarch (vs x86) 

Sieve, Matumul, QuickSort, etc. All slower on aarch m5.
Even things where we were at c level on x86. 
--- 

MML has owned values that are represented as aggregates containing pointers. Examples today include
`String`, `IntArray`, `StringArray`, and `FloatArray`, but the issue is not specific to those names.
Any struct-like value may contain pointer-like fields, and ownership may make those pointers
semantically fresh allocation results.

The current native ABI lowering can return small two-field aggregates through scalar or integer-like
carriers. On AArch64, a shape such as:

    { i64, ptr }

may be physically lowered as:

    [2 x i64]

That is ABI-compatible with C for a by-value struct return, but it changes how LLVM sees the pointer
field at the call boundary. If the pointer field crosses through an integer lane, the optimizer may
lose useful pointer provenance information. This is particularly bad for allocator-like functions:
the language knows the returned aggregate owns fresh heap storage, but the physical IR no longer
makes the data pointer look like a fresh allocation-derived pointer.

The motivating symptom is that optimized MML code using allocated arrays can remain scalar and
rolled in places where equivalent C gives LLVM enough information to unroll and combine memory
operations.

## Constraints

The solution must not be a name-based workaround. `ar_int_new` is a symptom, not a design boundary.

The solution must not depend on a native-vs-MML distinction. An MML struct and a native struct are
both aggregates. If they have the same ownership and layout properties, their return convention
should follow the same rule.

The solution must not depend on an external-vs-internal distinction as the conceptual model. The
compiler may have different implementation mechanics when it controls both caller and callee, but
the language-level rule should be regular.

The source-level signature should remain the ordinary MML signature:

    fn make_array(size: Int): IntArray

The physical ABI may use an out-result parameter, but this should be an implementation detail of
codegen, not a user-visible API change.

The rule should be based on type and ownership information:

- the return type resolves to an aggregate layout
- the returned value is owned/fresh, for example through `MemEffect.Alloc` or ownership inference
- the aggregate contains at least one pointer-like field

## Proposed rule

Any function returning an owned aggregate containing pointer-like fields uses an out-result physical
ABI.

Conceptually:

    fn f(a: A): T

where `T` is an owned aggregate with pointer-like fields, lowers physically as:

    void f(T* out, A a)

The caller allocates result storage, passes it as the first physical argument, then treats the loaded
or addressed result as the semantic return value.

In LLVM terms, this can use an explicit first `ptr` parameter, preferably with `sret(T)` when that
matches the target ABI and verifier constraints:

    declare void @f(ptr sret(%struct.T) align 8, ...)

The important point is that the aggregate's pointer fields stay pointer-typed in memory. They do not
need to be returned through integer lanes merely because the aggregate is small enough for a target
ABI register return class.

## Why this is regular

This rule is not about runtime functions, native functions, or arrays. It is about owned aggregate
return values.

The same rule covers:

- runtime constructors such as array and string constructors
- clone functions such as `__clone_String` and `__clone_IntArray`
- compiler-generated clone functions for user structs with owned fields
- user-written functions that allocate and return owned structs
- future aggregate-returning allocators introduced by the language or libraries

Non-owned aggregate returns can continue to use the normal target ABI. Aggregates without
pointer-like fields also do not need this treatment, because there is no pointer provenance to
preserve.

## Implementation sketch

Codegen needs a predicate over semantic return types:

    usesOwnedAggregateOutResult(returnType, functionEffects, ownershipFacts)

The predicate should resolve aliases and nominal types to aggregate layout. It should work for both
native aggregate layouts and MML structs.

The predicate is true when:

- the resolved layout is aggregate-like
- ownership facts say the result is owned/fresh
- any field is pointer-like, or recursively contains pointer-like owned storage

When true, function declaration and definition emission use a physical `void` return with an
out-result parameter. Call emission allocates or otherwise provides destination storage and passes it
as the first physical argument.

Existing ABI lowering still matters for ordinary parameters and non-owned returns. For example,
AArch64 may still pack non-owning `{ i64, ptr }` arguments as `[2 x i64]` if that is the correct C
ABI. The proposed rule only overrides owned aggregate returns.

## Runtime impact

Runtime C functions must match the physical ABI exactly. A function currently written as:

    IntArray ar_int_new(int64_t size)

would become:

    void ar_int_new(IntArray *out, int64_t size)

The source-level MML declaration remains:

    fn ar_int_new(size: Int): IntArray = @native[mem=alloc]

This is not a public MML API change. It is a runtime/codegen contract change.

Any handwritten runtime helper that calls another affected function must also use the new physical
ABI. For example, a helper constructing `StringArray` values cannot call `ar_str_new` as though it
still returned by value.

## Decision points and proposed path

Q1. What is the scope of the out-result ABI rule?

Options:

- Q1.A: only owned aggregates with pointer-like fields
- Q1.B: all owned aggregates
- Q1.C: all aggregate returns

Recommendation: Q1.A.

Reason: the concrete bug is loss of pointer provenance for owned pointer fields. Q1.A solves that
problem without changing return ABI for plain numeric/product values. Q1.B is simpler to explain but
changes more functions. Q1.C is too broad and turns a targeted ownership/provenance rule into a
general aggregate ABI rewrite.

Q2. What representation should the caller use after an out-result call?

Options:

- Q2.A: allocate an out slot, call the function, then load the aggregate SSA value
- Q2.B: keep the result as an address and compile field access directly from that address
- Q2.C: implement Q2.A first, then introduce Q2.B where the compiler can benefit from address-based
  aggregate handling

Recommendation: Q2.C.

Reason: Q2.A preserves the current expression model and is the smallest implementation step. Q2.B is
likely a better long-term representation for owned aggregates, but it touches more of expression
codegen and field access. Q2.C keeps the ABI fix scoped while leaving a path to stronger optimization.

Q3. How should the compiler know a function returns an owned aggregate?

Options:

- Q3.A: only explicit `MemEffect.Alloc`
- Q3.B: infer owned return when the body returns an owned constructor, allocator, or clone result
- Q3.C: support explicit effects first, then add inference as a separate compiler improvement

Recommendation: Q3.C.

Reason: explicit effects already exist and are enough to fix runtime allocators and clone functions.
Inference is the regular language direction, but it should not be mixed into the first ABI change.
The implementation should still name the predicate in terms of ownership facts so inferred facts can
feed it later.

Q4. How should the physical ABI be represented in codegen?

Options:

- Q4.A: emit LLVM `sret` directly wherever the rule applies
- Q4.B: emit a plain first `ptr` parameter
- Q4.C: represent this as an internal return convention, then let target-specific LLVM lowering
  choose `sret` or plain `ptr`

Recommendation: Q4.C.

Reason: the design rule should not be tied directly to one LLVM spelling. Codegen should model the
physical convention explicitly, then emit the target-valid form. In practice, that will probably
start by reusing the existing `sret` machinery where LLVM accepts it.

Q5. Which functions should be converted first?

Options:

- Q5.A: only runtime functions currently marked with `MemEffect.Alloc`
- Q5.B: runtime functions plus compiler-generated clone functions
- Q5.C: every function whose typed/effect-checked signature satisfies the predicate

Recommendation: Q5.C as the design target, staged through Q5.A and Q5.B if needed.

Reason: Q5.C is the only option that respects the constraint that MML structs and native structs are
not semantically different. Staging the implementation is acceptable, but each stage should be an
incomplete implementation of the general rule, not a separate special case.

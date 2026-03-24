# MinnieML Closures — Current Capture / Ownership Problem

## Current Behavior

When a closure captures a value, the compiler currently **copies the value into the closure environment**.

For primitive types (Int, Float, etc.), this is fine because the value is fully contained in the copied data.

For heap-backed types like `String`, the value is a struct like:

    String = { length, data_pointer }

When captured, the compiler copies this struct into the closure environment:

    store %struct.String %value, ptr %env_field

This means:
- The struct is copied
- The pointer inside the struct is copied
- The underlying heap buffer is NOT copied
- No clone function is called
- Ownership is NOT transferred
- The closure environment does NOT free the captured value
- Only the original owner frees the heap buffer

So after capture:

    original String struct ──┐
                             ├──> same heap buffer
    closure env String copy ─┘

Multiple structs point to the same buffer, but only one owner exists.

---

## The Core Problem

This model breaks when a closure **outlives the owner** of a captured heap value.

Example:

    let s = readline();
    let f = { println s };
    free s;
    f();   // uses freed memory → invalid

This is the classic **closure + ownership + lifetime** problem.

Closures need a defined capture mode for heap values.

---

## Future Capture Modes (Likely Needed)

Eventually closures will need explicit or inferred capture semantics such as:

- Capture by move (closure owns the value)
- Capture by borrow (closure references value owned elsewhere)
- Capture by clone (closure gets its own copy)
- Escape analysis to decide automatically
- Lifetime tracking to prevent use-after-free

But this is a later stage problem.

---

## Current State Summary

Right now MinnieML closures behave like this:

| Type                | Capture behavior                  |
|---------------------|-----------------------------------|
| Int / Float         | Value copied                      |
| Plain structs       | Value copied                      |
| Heap types (String) | Struct copied, pointer shared     |
| Closure env free    | Frees env only                    |
| Heap buffer free    | Done by original owner            |

So closures currently **share heap objects without owning them**.

This is acceptable temporarily, but ownership semantics for captured heap values must be defined later.
# MinnieML ConcatOp — Annotated LLVM IR

## LLVM IR crash course

Before reading the IR, here's what the main instructions do:

### `alloca <type>`
Allocates space **on the stack** (not the heap). Returns a pointer to that space.
It's like a local variable declaration in C. The memory is automatically freed
when the function returns.

```llvm
%4 = alloca %struct.String    ; allocate sizeof(String) bytes on the stack
                                ; %4 is now a String* (pointer to that stack slot)
```

### `getelementptr` (GEP)
Computes a pointer offset **without reading or writing memory**. It's pure pointer arithmetic. The arguments are:

```
getelementptr <base_type>, <ptr_type> <ptr>, <index0>, <index1>, ...
```

- **base_type**: the type the pointer points to (tells LLVM the element size)
- **ptr**: the pointer to start from
- **index0**: which element to select (if it's an array or a pointer to the first element of an array)
- **index1**: which field within that element (for struct types, this is the field index)

**Example 1 — indexing into a global array:**
```llvm
%11 = getelementptr [2 x i8], [2 x i8]* @str.1, i64 0, i64 0
;                   ^^^^^^^^  ^^^^^^^^^^^^^^^^^^^  ^^^^^  ^^^^^
;                   |         |                    |      |
;                   |         |                    |      field 0 of that array = first byte
;                   |         |                    element 0 (the whole [2 x i8])
;                   |         pointer to @str.1 (a global [2 x i8])
;                   base type: array of 2 bytes
;
; Result: %11 is an i8* pointing to the first character of ", "
; This is equivalent to C's: char* p = &str_1[0];
```

Why two indices? Because `@str.1` has type `[2 x i8]*` (pointer to an array).
The first `0` dereferences the pointer to get the array, the second `0` indexes
into the array to get the first element. This is a common LLVM idiom for
getting an `i8*` from a global string constant.

**Example 2 — accessing a struct field:**
```llvm
%13 = getelementptr %struct.String, %struct.String* %12, i32 0, i32 0
;                   ^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^^^  ^^^^^  ^^^^^
;                   |               |                     |      |
;                   |               |                     |      field index 0 = the 'len' field (i64)
;                   |               |                     element 0 (we have a single struct, not an array)
;                   |               pointer to a String on the stack
;                   base type: String = {i64, i8*}
;
; Result: %13 is an i64* pointing to the 'len' field of the String at %12
; Equivalent C: int64_t* p = &my_string->len;  (or &my_string[0].len)
```

```llvm
%14 = getelementptr %struct.String, %struct.String* %12, i32 0, i32 1
;                                                                  ^
;                                                    field index 1 = the 'data' field (i8*)
;
; Result: %14 is an i8** pointing to the 'data' field of the String at %12
; Equivalent C: char** p = &my_string->data;
```

### `store <value_type> <value>, <ptr_type> <ptr>`
Writes a value into memory at the given pointer. The pointer must be of type `<value_type>*`.

```llvm
store i64 2, i64* %13, !tbaa !10
;     ^^^    ^^^^^^^^   ^^^^^^^^^
;     |      |          |
;     |      |          TBAA metadata (tells optimizer about aliasing — see below)
;     |      where: pointer to the 'len' field
;     what: the integer 2
;
; Equivalent C: *p = 2;   or: my_string.len = 2;
```

```llvm
store i8* %11, i8** %14, !tbaa !11
;     ^^^^^^^^ ^^^^^^^^^
;     |        |
;     |        where: pointer to the 'data' field (which holds an i8*)
;     what: the i8* pointing to the ", " constant
;
; Equivalent C: my_string.data = pointer_to_comma_space;
```

### `load <type>, <ptr_type> <ptr>`
Reads a value from memory at the given pointer.

```llvm
%15 = load %struct.String, %struct.String* %12
;          ^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^^^
;          |               |
;          |               where: pointer to the String on the stack
;          what type to read (sizeof(String) = 16 bytes: i64 + i8*)
;
; Reads the entire 16-byte struct out of the stack slot into a register pair.
; After this, %15 holds the String {2, ptr_to_", "} as a VALUE.
; Equivalent C: String s = *my_string_ptr;
```

### `extractvalue <aggregate> <value>, <index>`
Pulls a field out of a struct that's already in **registers** (not memory). No memory access.

```llvm
%6 = extractvalue %struct.String %5, 0    ; get field 0 (len) from the struct value %5
%7 = extractvalue %struct.String %5, 1    ; get field 1 (data) from the struct value %5
```

### `phi`
A phi node selects a value based on which basic block we came from. It exists
because LLVM IR is in SSA (Static Single Assignment) form, where each variable
is assigned exactly once. At a merge point (like a loop header), you need phi
to reconcile different values from different paths.

```llvm
%2 = phi i64 [ %0, %entry ], [ %10, %loop.latch ]
;        ^^^   ^^^^^^^^^^^    ^^^^^^^^^^^^^^^^^^^^
;        |     |              |
;        |     |              if we came from loop.latch: use %10 (i+1)
;        |     if we came from entry: use %0 (initial value)
;        result type: i64
```

### `icmp <condition> <type> <a>, <b>`
Integer comparison. Returns `i1` (a boolean, 1 bit).

```llvm
%4 = icmp sle i64 %2, %3
;          ^^^
;          signed less-or-equal
; Other conditions: eq, ne, slt, sgt, sge, ult, ule, ugt, uge
```

### `br` (branch)
Either unconditional jump or conditional branch:

```llvm
br label %loop.header                              ; unconditional: goto loop.header
br i1 %4, label %loop.latch, label %loop.exit.0   ; conditional: if %4 then latch else exit
```

---

## The annotated IR

```llvm
; ============================================================================
; ModuleID = 'ConcatOp'
;
; This LLVM IR was generated by the MinnieML (MML) compiler from a program
; that demonstrates ownership-based memory management WITHOUT a garbage
; collector. It builds and prints a string 1000 times, never leaking a byte.
;
; The MML source:
;
;   fn make_test_results(): String =
;     "Zero: "      ++ (int_to_str 0)   ++ ", " ++
;     "Positive: "  ++ (int_to_str 123) ++ ", " ++
;     "Large: "     ++ (int_to_str 1234567890)
;
;   fn loop(i: Int, to: Int): Unit =
;     if i <= to then
;       println (make_test_results ());
;       loop (i+1) to
;     end
;
;   fn main(): Unit = loop 1 1000
; ============================================================================

target triple = "x86_64-apple-macosx"

; ============================================================================
; NATIVE TYPE DEFINITIONS
; ============================================================================
; MML represents compound types as C-style structs.
; Each "fat" type carries its length alongside its data pointer,
; so the runtime can do bounds-checked access and correct deallocation.
; ============================================================================

; String = { len: i64, data: i8* }
; A length-prefixed string. NOT null-terminated.
; Conceptually a "fat pointer": two scalars (length + data pointer).
; On x86_64 SysV/macOS, a 16-byte struct like this is typically passed
; and returned in two registers (rdi/rsi or rax/rdx), so "by value" is
; cheap. But this is ABI-dependent: on other platforms or with larger
; structs, the calling convention may pass via hidden pointer instead.
%struct.String = type { i64, i8* }

; IntArray = { len: i64, data: i64* }
%struct.IntArray = type { i64, i64* }

; StringArray = { len: i64, data: String* }
%struct.StringArray = type { i64, %struct.String* }

; FloatArray = { len: i64, data: float* }
%struct.FloatArray = type { i64, float* }

; ============================================================================
; STRING CONSTANTS
; ============================================================================
; Compile-time string literals in the binary's read-only data section.
; NOT heap-allocated — the MML compiler knows these are static/borrowed,
; so no __free_String is ever emitted for them.
; ============================================================================

@str.0 = private constant [7 x i8] c"Large: ", align 1
@str.1 = private constant [2 x i8] c", ", align 1
@str.2 = private constant [10 x i8] c"Positive: ", align 1
@str.3 = private constant [6 x i8] c"Zero: ", align 1

; ============================================================================
; EXTERNAL FUNCTION DECLARATIONS (MML Runtime Library)
; ============================================================================
; Naming convention:
;   op.XXX.N  — operator XXX with N arguments (e.g., op.plus.2 = binary +)
;   ar_*      — array operations (get, set, len, new)
;   __free_*  — ownership destructors: deallocate a value of that type
;   __clone_* — deep-copy a value (used when ownership must be shared)
; ============================================================================

; --- Float comparison ---
declare i1 @op.gt_eq_dot.2(float, float) #0    ; >=.
declare i1 @op.eq_eq_dot.2(float, float) #0    ; ==.
declare i1 @op.lt_eq_dot.2(float, float) #0    ; <=.
declare i1 @op.lt_dot.2(float, float) #0       ; <.
declare i1 @op.gt_dot.2(float, float) #0       ; >.
declare i1 @op.bang_eq_dot.2(float, float) #0   ; !=.

; --- Float arithmetic ---
declare float @op.slash_dot.2(float, float) #0  ; /.
declare float @op.plus_dot.2(float, float) #0   ; +.
declare float @op.minus_dot.2(float, float) #0  ; -. (binary)
declare float @op.star_dot.2(float, float) #0   ; *.
declare float @op.minus_dot.1(float) #0         ; -. (unary negation)

; --- Int comparison ---
declare i1 @op.eq_eq.2(i64, i64) #0     ; ==
declare i1 @op.bang_eq.2(i64, i64) #0   ; !=
declare i1 @op.lt_eq.2(i64, i64) #0     ; <=
declare i1 @op.lt.2(i64, i64) #0        ; <
declare i1 @op.gt_eq.2(i64, i64) #0     ; >=
declare i1 @op.gt.2(i64, i64) #0        ; >

; --- Int arithmetic ---
declare i64 @op.plus.2(i64, i64) #0     ; +
declare i64 @op.minus.2(i64, i64) #0    ; - (binary)
declare i64 @op.minus.1(i64) #0         ; - (unary)
declare i64 @op.plus.1(i64) #0          ; + (unary, identity)
declare i64 @op.star.2(i64, i64) #0     ; *
declare i64 @op.slash.2(i64, i64) #0    ; /
declare i64 @op.percent.2(i64, i64) #0  ; %

; --- Bitwise / shift ---
declare i64 @op.lt_lt.2(i64, i64) #0    ; <<
declare i64 @op.gt_gt.2(i64, i64) #0    ; >>

; --- Boolean ---
declare i1 @op.not.1(i1) #0             ; not
declare i1 @op.and.2(i1, i1) #0         ; and
declare i1 @op.or.2(i1, i1) #0          ; or

; --- Math builtins ---
declare float @fabs(float) #0
declare float @sqrt(float) #0

; --- I/O ---
; Note: println takes (len, data) — a length-prefixed byte slice, NOT a
; null-terminated C string. This is a deliberate design choice: MML strings
; carry their length, so printing never needs to scan for a null terminator.
; It's a C-compatible ABI (just two scalar args) without the C-string footgun.
declare void @println(i64, i8*) #0
declare void @print(i64, i8*) #0

; --- Buffered I/O ---
declare noalias i8* @mkBuffer() #0
declare noalias i8* @mkBufferWithSize(i64) #0
declare noalias i8* @mkBufferWithFd(i64) #0
declare noalias i8* @__clone_Buffer(i8*) #0
declare void @__free_Buffer(i8*) #0
declare void @flush(i8*) #0
declare void @buffer_write(i8*, i64, i8*) #0
declare void @buffer_writeln(i8*, i64, i8*) #0
declare void @buffer_write_int(i8*, i64) #0
declare void @buffer_writeln_int(i8*, i64) #0
declare void @buffer_write_float(i8*, float) #0
declare void @buffer_writeln_float(i8*, float) #0
declare void @mml_sys_flush() #0

; --- File I/O ---
declare i64 @open_file_write(i64, i8*) #0
declare i64 @open_file_read(i64, i8*) #0
declare i64 @open_file_append(i64, i8*) #0
declare void @close_file(i64) #0
declare %struct.String @readline() #0
declare %struct.String @read_line_fd(i64) #0

; --- String operations ---
declare %struct.String @concat(i64, i8*, i64, i8*) #0      ; concat two strings → new heap string
declare %struct.String @int_to_str(i64) #0                  ; int → new heap string
declare %struct.String @float_to_str(float) #0              ; float → new heap string
declare i64 @str_to_int(i64, i8*) #0
declare float @int_to_float(i64) #0
declare i64 @float_to_int(float) #0

; --- Array operations ---
declare %struct.IntArray @ar_int_new(i64) #0
declare i64 @ar_int_len(i64, i64*) #0
declare i64 @ar_int_get(i64, i64*, i64) #0
declare i64 @unsafe_ar_int_get(i64, i64*, i64) #0
declare void @ar_int_set(i64, i64*, i64, i64) #0
declare void @unsafe_ar_int_set(i64, i64*, i64, i64) #0
declare %struct.FloatArray @ar_float_new(i64) #0
declare i64 @ar_float_len(i64, float*) #0
declare float @ar_float_get(i64, float*, i64) #0
declare float @unsafe_ar_float_get(i64, float*, i64) #0
declare void @ar_float_set(i64, float*, i64, float) #0
declare void @unsafe_ar_float_set(i64, float*, i64, float) #0
declare %struct.StringArray @ar_str_new(i64) #0
declare i64 @ar_str_len(i64, %struct.String*) #0
declare %struct.String @ar_str_get(i64, %struct.String*, i64) #0
declare void @ar_str_set(i64, %struct.String*, i64, i64, i8*) #0

; --- Ownership: destructors ---
declare void @__free_String(i64, i8*) #0
declare void @__free_IntArray(i64, i64*) #0
declare void @__free_FloatArray(i64, float*) #0
declare void @__free_StringArray(i64, %struct.String*) #0

; --- Ownership: cloners ---
declare %struct.String @__clone_String(i64, i8*) #0
declare %struct.IntArray @__clone_IntArray(i64, i64*) #0
declare %struct.FloatArray @__clone_FloatArray(i64, float*) #0
declare %struct.StringArray @__clone_StringArray(i64, %struct.String*) #0


; ============================================================================
; OPERATOR WRAPPERS
; ============================================================================
; MML compiles operators into module-qualified functions.
; The "concatop_" prefix is the module name (ConcatOp). This avoids
; name collisions across modules.
; ============================================================================

; ----------------------------------------------------------------------------
; Buffer write operators -- convenience wrappers that allow you to say
; ` buffer write "blah"` for example.
; ----------------------------------------------------------------------------

define void @concatop_op.write.2(i8* %0, %struct.String %1) #0 {
entry:
  ; extractvalue pulls field 0 (len) out of the struct VALUE %1.
  ; No memory access — %1 is already in registers.
  %2 = extractvalue %struct.String %1, 0       ; %2 = string length (i64)
  %3 = extractvalue %struct.String %1, 1       ; %3 = string data ptr (i8*)
  ; Pass the unpacked (len, data) to the runtime
  call void @buffer_write(i8* %0, i64 %2, i8* %3)
  ret void
}

define void @concatop_op.writeln.2(i8* %0, %struct.String %1) #0 {
entry:
  %2 = extractvalue %struct.String %1, 0
  %3 = extractvalue %struct.String %1, 1
  call void @buffer_writeln(i8* %0, i64 %2, i8* %3)
  ret void
}

define void @concatop_op.write_int.2(i8* %0, i64 %1) #0 {
entry:
  call void @buffer_write_int(i8* %0, i64 %1)
  ret void
}

define void @concatop_op.writeln_int.2(i8* %0, i64 %1) #0 {
entry:
  call void @buffer_writeln_int(i8* %0, i64 %1)
  ret void
}

define void @concatop_op.write_float.2(i8* %0, float %1) #0 {
entry:
  call void @buffer_write_float(i8* %0, float %1)
  ret void
}

define void @concatop_op.writeln_float.2(i8* %0, float %1) #0 {
entry:
  call void @buffer_writeln_float(i8* %0, float %1)
  ret void
}

; ============================================================================
; THE ++ OPERATOR (String Concatenation)
; ============================================================================
; In MML, this is a binary operator that wraps the concat function.
;
;   "hello" ++ " world"
;
; Below: 7 lines of IR to destructure two fat-pointer structs, call
; the runtime allocator, and return the result.
;
; op.plus_plus.2 : String -> String -> String
;
; Takes two Strings by value, extracts their (len, ptr) pairs, calls
; the runtime's concat() which:
;   1. malloc's a new buffer of size len1 + len2
;   2. memcpy's both strings into it
;   3. returns a new String struct owning that buffer
;
; OWNERSHIP: returned String is a NEW heap allocation (caller must free).
; Input strings are BORROWED (read-only, not freed by ++).
; The ++ operator does not "consume" its arguments — it just reads them.
; The CALLER decides when to free each operand, based on whether any
; further uses of that value exist downstream in the IR.
; ============================================================================

define %struct.String @concatop_op.plus_plus.2(%struct.String %0, %struct.String %1) #0 {
entry:
  %2 = extractvalue %struct.String %0, 0    ; left.len
  %3 = extractvalue %struct.String %0, 1    ; left.data
  %4 = extractvalue %struct.String %1, 0    ; right.len
  %5 = extractvalue %struct.String %1, 1    ; right.data
  ; concat(left.len, left.data, right.len, right.data) → new heap String
  %6 = call %struct.String @concat(i64 %2, i8* %3, i64 %4, i8* %5)
  ret %struct.String %6
}

; ============================================================================
; STRUCT CONSTRUCTORS
; ============================================================================
; Pack raw (len, ptr) pairs into struct types via stack alloca + store + load.
; LLVM's SROA pass will optimize these into `insertvalue` instructions.
;
; NOTE: LLVM has `insertvalue` which does this directly in SSA without
; touching memory (the dual of `extractvalue`). Using that here would
; skip the alloca entirely and produce cleaner -O0 code. The alloca
; pattern is simpler to emit from a codegen perspective, and at -O2
; the result is identical.
; ============================================================================

define %struct.String @concatop___mk_String(i64 %0, i8* %1) #0 {
entry:
  ; Allocate a String-sized slot on the stack (16 bytes: i64 + i8*)
  %2 = alloca %struct.String

  ; Get a pointer to field 0 (len) of that stack struct:
  ;   getelementptr %struct.String, ptr_to_struct, 0th_element, 0th_field
  ;   The first 0 says "don't skip past any structs" (we have just one).
  ;   The second 0 says "field index 0" which is the i64 len.
  ;   Result: an i64* pointing into the stack slot.
  %3 = getelementptr %struct.String, %struct.String* %2, i32 0, i32 0
  ; Write the length value into that field
  store i64 %0, i64* %3, !tbaa !10

  ; Same thing for field 1 (data):
  ;   The second index is 1 → the i8* data field.
  ;   Result: an i8** pointing into the stack slot.
  %4 = getelementptr %struct.String, %struct.String* %2, i32 0, i32 1
  ; Write the data pointer into that field
  store i8* %1, i8** %4, !tbaa !11

  ; Load the entire 16-byte struct from the stack into registers.
  ; After this, %5 is a struct VALUE (not a pointer).
  %5 = load %struct.String, %struct.String* %2
  ret %struct.String %5
}

define %struct.IntArray @concatop___mk_IntArray(i64 %0, i64* %1) #0 {
entry:
  %2 = alloca %struct.IntArray
  %3 = getelementptr %struct.IntArray, %struct.IntArray* %2, i32 0, i32 0
  store i64 %0, i64* %3, !tbaa !12
  %4 = getelementptr %struct.IntArray, %struct.IntArray* %2, i32 0, i32 1
  store i64* %1, i64** %4, !tbaa !13
  %5 = load %struct.IntArray, %struct.IntArray* %2
  ret %struct.IntArray %5
}

define %struct.StringArray @concatop___mk_StringArray(i64 %0, %struct.String* %1) #0 {
entry:
  %2 = alloca %struct.StringArray
  %3 = getelementptr %struct.StringArray, %struct.StringArray* %2, i32 0, i32 0
  store i64 %0, i64* %3, !tbaa !14
  %4 = getelementptr %struct.StringArray, %struct.StringArray* %2, i32 0, i32 1
  store %struct.String* %1, %struct.String** %4, !tbaa !15
  %5 = load %struct.StringArray, %struct.StringArray* %2
  ret %struct.StringArray %5
}

define %struct.FloatArray @concatop___mk_FloatArray(i64 %0, float* %1) #0 {
entry:
  %2 = alloca %struct.FloatArray
  %3 = getelementptr %struct.FloatArray, %struct.FloatArray* %2, i32 0, i32 0
  store i64 %0, i64* %3, !tbaa !16
  %4 = getelementptr %struct.FloatArray, %struct.FloatArray* %2, i32 0, i32 1
  store float* %1, float** %4, !tbaa !17
  %5 = load %struct.FloatArray, %struct.FloatArray* %2
  ret %struct.FloatArray %5
}

; ============================================================================
; make_test_results : () -> String
; ============================================================================
; MML source (6 lines):
;
;   fn make_test_results(): String =
;     "Zero: "      ++ (int_to_str 0)   ++ ", " ++
;     "Positive: "  ++ (int_to_str 123) ++ ", " ++
;     "Large: "     ++ (int_to_str 1234567890)
;   ;
;
; The source has no explicit allocation, no frees, no temporaries.
; The compiler generates all of that: ~80 lines of IR that allocate
; 10 heap strings, free exactly 9 intermediates, and return the
; remaining one with ownership transferred to the caller.
; ============================================================================
;
; The expression tree (right-associative ++) evaluates inside-out:
;
;   t1 = int_to_str(0)              ← heap alloc
;   t2 = int_to_str(123)            ← heap alloc
;   t3 = int_to_str(1234567890)     ← heap alloc
;   t4 = "Large: " ++ t3            ← heap alloc  → FREE t3
;   t5 = ", " ++ t4                 ← heap alloc  → FREE t4
;   t6 = t2 ++ t5                   ← heap alloc  → FREE t5, FREE t2
;   t7 = "Positive: " ++ t6         ← heap alloc  → FREE t6
;   t8 = ", " ++ t7                 ← heap alloc  → FREE t7
;   t9 = t1 ++ t8                   ← heap alloc  → FREE t8, FREE t1
;   t10 = "Zero: " ++ t9            ← heap alloc  → FREE t9
;   return t10                       ← ownership transferred to caller
;
; Tally: 10 allocated, 9 freed, 1 returned, 0 leaked ✓
; String literals are BORROWED (static data) — never freed.
;
; KEY INVARIANT: the compiler inserts a free after the LAST USE of each
; heap-allocated value. The ++ operator itself borrows both operands
; (reads but doesn't consume them) — it's the compiler that decides
; when each value has no remaining uses and schedules the free there.
; This is use-based lifetime: the free isn't tied to ++ semantics,
; it's tied to "no more SSA references to this value exist below".
; ============================================================================

define %struct.String @concatop_make_test_results() #0 {
entry:
  ; ---- Step 1: Convert integers to heap-allocated strings ------------------
  ; MML source: (int_to_str 0), (int_to_str 123), (int_to_str 1234567890)
  ; Each call returns a heap-allocated String that must eventually be freed.

  %0 = call %struct.String @int_to_str(i64 0)           ; t1 = "0"         (heap)
  %1 = call %struct.String @int_to_str(i64 123)         ; t2 = "123"       (heap)
  %2 = call %struct.String @int_to_str(i64 1234567890)  ; t3 = "1234567890"(heap)

  ; ---- Step 2: Build string inside-out, freeing intermediates --------------
  ; MML source for everything below:
  ;   "Zero: " ++ (int_to_str 0) ++ ", " ++ "Positive: " ++ (int_to_str 123) ++ ", " ++ "Large: " ++ (int_to_str 1234567890)
  ;   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  ;   One line of MML. ~70 lines of IR. Seven concat calls, nine frees, four
  ;   literal-to-struct promotions.

  ; --- Wrap the literal "Large: " as a borrowed String struct on the stack ---
  ;
  ; NOTE ON THIS PATTERN: MML wraps string literals using alloca→store→load
  ; to build a String struct. This is simple and correct, but not the only way.
  ; LLVM also has `insertvalue` which builds aggregates directly in SSA without
  ; touching memory (clang uses it extensively). At -O2, SROA collapses this
  ; alloca pattern to the equivalent insertvalue form, so the generated code is
  ; identical. At -O0 this does introduce real stack traffic — a future compiler
  ; improvement could emit insertvalue directly for literals.
  ;
  ; First, get an i8* to the raw bytes of the global constant:
  ;   getelementptr [7 x i8], [7 x i8]* @str.0, i64 0, i64 0
  ;     base type:  [7 x i8] (array of 7 bytes)
  ;     pointer:    @str.0 (the global)
  ;     index 0:    deref the pointer → get the [7 x i8] array
  ;     index 0:    get element 0 of that array → i8*
  ;   Result: pointer to the 'L' in "Large: "
  %3 = getelementptr [7 x i8], [7 x i8]* @str.0, i64 0, i64 0

  ; Allocate a String struct on the stack to hold {len=7, data=ptr}
  %4 = alloca %struct.String

  ; Get pointer to field 0 (len) of the stack struct:
  ;   getelementptr %struct.String, %struct.String* %4, i32 0, i32 0
  ;     base type:  %struct.String = {i64, i8*}
  ;     pointer:    %4 (stack-allocated struct)
  ;     index 0:    the 0th String (just one, not an array)
  ;     index 0:    field 0 = the i64 'len' field
  ;   Result: i64* pointing to the len field
  %5 = getelementptr %struct.String, %struct.String* %4, i32 0, i32 0
  ; store i64 7, i64* %5 — write 7 into the len field
  store i64 7, i64* %5, !tbaa !10

  ; Get pointer to field 1 (data) of the stack struct:
  ;   index 1 → the i8* 'data' field
  ;   Result: i8** pointing to the data field
  %6 = getelementptr %struct.String, %struct.String* %4, i32 0, i32 1
  ; store i8* %3, i8** %6 — write the pointer-to-"Large: " into the data field
  store i8* %3, i8** %6, !tbaa !11

  ; Load the entire struct from the stack into a register-pair value:
  ;   load %struct.String, %struct.String* %4
  ;   Reads 16 bytes (i64 + i8*) from the stack slot.
  ;   %7 is now the value {7, ptr_to_"Large: "} — a BORROWED, non-heap String.
  %7 = load %struct.String, %struct.String* %4

  ; --- t4 = "Large: " ++ t3  →  "Large: 1234567890" ---
  ; concat() allocates a new heap string of len 7+10=17, copies both into it.
  %8 = call %struct.String @concatop_op.plus_plus.2(%struct.String %7, %struct.String %2)

  ; ** FREE t3: this is the LAST USE of int_to_str(1234567890)'s result **
  ; The ++ above borrowed %2 (read its bytes), and no instruction below
  ; references %2 again. The compiler sees "no more uses" and inserts the free.
  ; extractvalue pulls the len and data out of the struct value %2 (no memory access).
  %9 = extractvalue %struct.String %2, 0       ; t3.len
  %10 = extractvalue %struct.String %2, 1      ; t3.data
  call void @__free_String(i64 %9, i8* %10)   ; free("1234567890") ← NO LEAK

  ; --- Wrap literal ", " as borrowed String ---
  %11 = getelementptr [2 x i8], [2 x i8]* @str.1, i64 0, i64 0  ; i8* to ", "
  %12 = alloca %struct.String
  %13 = getelementptr %struct.String, %struct.String* %12, i32 0, i32 0  ; → len field
  store i64 2, i64* %13, !tbaa !10
  %14 = getelementptr %struct.String, %struct.String* %12, i32 0, i32 1  ; → data field
  store i8* %11, i8** %14, !tbaa !11
  %15 = load %struct.String, %struct.String* %12   ; {2, ptr_to_", "}

  ; --- t5 = ", " ++ t4  →  ", Large: 1234567890" ---
  %16 = call %struct.String @concatop_op.plus_plus.2(%struct.String %15, %struct.String %8)

  ; ** FREE t4 **
  %17 = extractvalue %struct.String %8, 0
  %18 = extractvalue %struct.String %8, 1
  call void @__free_String(i64 %17, i8* %18)   ; free("Large: 1234567890")

  ; --- t6 = t2 ++ t5  →  "123, Large: 1234567890" ---
  %19 = call %struct.String @concatop_op.plus_plus.2(%struct.String %1, %struct.String %16)

  ; ** FREE t5 **
  %20 = extractvalue %struct.String %16, 0
  %21 = extractvalue %struct.String %16, 1
  call void @__free_String(i64 %20, i8* %21)   ; free(", Large: 1234567890")

  ; ** FREE t2: last use of int_to_str(123) — no references below **
  %22 = extractvalue %struct.String %1, 0
  %23 = extractvalue %struct.String %1, 1
  call void @__free_String(i64 %22, i8* %23)   ; free("123")

  ; --- Wrap "Positive: " as borrowed String ---
  %24 = getelementptr [10 x i8], [10 x i8]* @str.2, i64 0, i64 0
  %25 = alloca %struct.String
  %26 = getelementptr %struct.String, %struct.String* %25, i32 0, i32 0
  store i64 10, i64* %26, !tbaa !10
  %27 = getelementptr %struct.String, %struct.String* %25, i32 0, i32 1
  store i8* %24, i8** %27, !tbaa !11
  %28 = load %struct.String, %struct.String* %25   ; {10, ptr_to_"Positive: "}

  ; --- t7 = "Positive: " ++ t6 ---
  %29 = call %struct.String @concatop_op.plus_plus.2(%struct.String %28, %struct.String %19)

  ; ** FREE t6 **
  %30 = extractvalue %struct.String %19, 0
  %31 = extractvalue %struct.String %19, 1
  call void @__free_String(i64 %30, i8* %31)

  ; --- Wrap ", " again (same global constant, fresh stack struct) ---
  %32 = getelementptr [2 x i8], [2 x i8]* @str.1, i64 0, i64 0
  %33 = alloca %struct.String
  %34 = getelementptr %struct.String, %struct.String* %33, i32 0, i32 0
  store i64 2, i64* %34, !tbaa !10
  %35 = getelementptr %struct.String, %struct.String* %33, i32 0, i32 1
  store i8* %32, i8** %35, !tbaa !11
  %36 = load %struct.String, %struct.String* %33

  ; --- t8 = ", " ++ t7 ---
  %37 = call %struct.String @concatop_op.plus_plus.2(%struct.String %36, %struct.String %29)

  ; ** FREE t7 **
  %38 = extractvalue %struct.String %29, 0
  %39 = extractvalue %struct.String %29, 1
  call void @__free_String(i64 %38, i8* %39)

  ; --- t9 = t1 ++ t8 ---
  %40 = call %struct.String @concatop_op.plus_plus.2(%struct.String %0, %struct.String %37)

  ; ** FREE t8 **
  %41 = extractvalue %struct.String %37, 0
  %42 = extractvalue %struct.String %37, 1
  call void @__free_String(i64 %41, i8* %42)

  ; ** FREE t1: last use of int_to_str(0) — no references below **
  %43 = extractvalue %struct.String %0, 0
  %44 = extractvalue %struct.String %0, 1
  call void @__free_String(i64 %43, i8* %44)   ; free("0")

  ; --- Wrap "Zero: " as borrowed String ---
  %45 = getelementptr [6 x i8], [6 x i8]* @str.3, i64 0, i64 0
  %46 = alloca %struct.String
  %47 = getelementptr %struct.String, %struct.String* %46, i32 0, i32 0
  store i64 6, i64* %47, !tbaa !10
  %48 = getelementptr %struct.String, %struct.String* %46, i32 0, i32 1
  store i8* %45, i8** %48, !tbaa !11
  %49 = load %struct.String, %struct.String* %46   ; {6, ptr_to_"Zero: "}

  ; --- t10 = "Zero: " ++ t9  →  THE FINAL RESULT ---
  %50 = call %struct.String @concatop_op.plus_plus.2(%struct.String %49, %struct.String %40)

  ; ** FREE t9 — the last intermediate **
  %51 = extractvalue %struct.String %40, 0
  %52 = extractvalue %struct.String %40, 1
  call void @__free_String(i64 %51, i8* %52)

  ; Return t10. Ownership TRANSFERRED to caller.
  ; Score: 10 allocated, 9 freed, 1 returned, 0 leaked ✓
  ret %struct.String %50
}

; ============================================================================
; loop : (i: Int, to: Int) -> Unit
; ============================================================================
; MML source (5 lines):
;
;   fn loop(i: Int, to: Int): Unit =
;     if i <= to then
;       println (make_test_results ());
;       loop (i+1) to
;     end
;   ;
;
; Two things to notice in the IR below:
;   1. The tail-recursive call becomes an iterative loop (TCO).
;   2. The string returned by make_test_results() is freed automatically
;      after println. In C you'd need to store the result in a variable,
;      print it, then free it. Here the compiler inserts that sequence.
; ============================================================================

define void @concatop_loop(i64 %0, i64 %1) #0 {
entry:
  ; Jump to loop header (standard LLVM loop pattern)
  br label %loop.header

loop.header:
  ; PHI: merge "i" from entry (initial %0=1) or latch (incremented %10=i+1)
  ;   phi <type> [ <value_if_from_block_A>, %block_A ], [ <value_if_from_block_B>, %block_B ]
  %2 = phi i64 [ %0, %entry ], [ %10, %loop.latch ]   ; i

  ; PHI: "to" is loop-invariant — same value from both paths
  ; This looks self-referential (%3 depends on %3), which can be confusing
  ; if you're new to SSA. It's valid LLVM IR — it just means "on the back-edge,
  ; keep the same value." Many compilers would skip this phi and thread %1
  ; directly to all uses, and LLVM's LICM pass will likely do exactly that.
  ; The MML compiler emits it uniformly for all loop parameters.
  %3 = phi i64 [ %1, %entry ], [ %3, %loop.latch ]    ; to (always 1000)

  ; icmp sle = signed less-or-equal: is i <= to?
  ;                                  ^^^^^^^^
  ;                        MML source: if i <= to then
  %4 = icmp sle i64 %2, %3

  ; Conditional branch: if i <= to → loop body, else → exit
  br i1 %4, label %loop.latch, label %loop.exit.0

loop.latch:
  ; MML source: println (make_test_results ())
  ;             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  ; One expression. Below: a call, two extractvalues, a println, two more
  ; extractvalues, and a free. Six IR instructions for one MML expression.

  ; Call make_test_results() — returns a NEW heap String (we own it)
  %5 = call %struct.String @concatop_make_test_results()

  ; Unpack the String for println (which BORROWS it — reads but doesn't free)
  %6 = extractvalue %struct.String %5, 0       ; result.len
  %7 = extractvalue %struct.String %5, 1       ; result.data
  call void @println(i64 %6, i8* %7)

  ; ** FREE the String — last use; println has finished reading it **
  ; Without this, we'd leak ~50 bytes × 1000 iterations = ~50KB
  %8 = extractvalue %struct.String %5, 0
  %9 = extractvalue %struct.String %5, 1
  call void @__free_String(i64 %8, i8* %9)    ; ← NO LEAK

  ; MML source: loop (i+1) to
  ;             ^^^^^^^^^^^^^^
  ; In the source this is a recursive call. In the IR it's an add
  ; and a backward branch — the TCO transformation from the header note.

  ; i = i + 1
  %10 = add i64 %2, 1

  ; Back to loop header (this IS the "recursive call" — compiled away)
  br label %loop.header

loop.exit.0:
  ; i > to — loop done
  ret void
}

; ============================================================================
; Entry points
; ============================================================================
; MML source (2 lines):
;
;   fn main(): Unit =
;     loop 1 1000
;   ;
;
; Two lines. The entire program is 13 lines of MML.
; The IR below is ~350 lines.
; ============================================================================

; MML's main — just calls loop(1, 1000)
; MML source: loop 1 1000
;             ^^^^^^^^^^^^  ← that's the whole function body
define void @concatop_main() #0 {
entry:
  call void @concatop_loop(i64 1, i64 1000)
  ret void
}

; C entry point — calls MML main, flushes buffered stdout, exits 0
define i32 @main(i32 %0, ptr %1) #0 {
entry:
  call void @concatop_main()
  call void @mml_sys_flush()     ; flush MML's internal stdout buffer
  ret i32 0
}

; ============================================================================
; FUNCTION ATTRIBUTES
; ============================================================================
attributes #0 = { "target-cpu"="skylake" }
attributes #1 = { inlinehint "target-cpu"="skylake" }

; ============================================================================
; TBAA (Type-Based Alias Analysis) METADATA
; ============================================================================
; Tells LLVM which memory accesses CAN'T alias. This lets LLVM
; reorder and optimize loads/stores more aggressively (load hoisting,
; store sinking, instruction reordering).
;
; IMPORTANT: TBAA is a trust contract, not a proof. The frontend (MML)
; promises "these types don't alias in ways that violate the hierarchy."
; LLVM takes this on faith. If the frontend lies (e.g., stores an i8*
; through an i64*), LLVM will silently miscompile. MML's type system
; ensures the promises are kept.
;
; Hierarchy:
;   MML TBAA Root
;   ├── Int64          (scalar i64)
;   ├── CharPtr        (i8* — string data pointers)
;   ├── Int64Ptr       (i64* — int array data pointers)
;   ├── StringPtr      (String* — string array data pointers)
;   ├── FloatPtr       (float* — float array data pointers)
;   ├── String         { Int64 @ 0, CharPtr @ 8 }
;   ├── IntArray       { Int64 @ 0, Int64Ptr @ 8 }
;   ├── StringArray    { Int64 @ 0, StringPtr @ 8 }
;   └── FloatArray     { Int64 @ 0, FloatPtr @ 8 }
;
; Because Int64 and CharPtr are siblings (no parent-child relationship),
; LLVM is told that a store to String.len CANNOT affect String.data.
; This gives the optimizer freedom to reorder field accesses.
;
; Access tags (!10–!17) are attached to load/store instructions via
; the !tbaa metadata annotation. They tell LLVM:
;   "this instruction accesses field X of struct type Y"
; ============================================================================

!0 = !{!"MML TBAA Root"}
!1 = !{!"Int64", !0, i64 0}
!2 = !{!"CharPtr", !0, i64 0}
!3 = !{!"String", !1, i64 0, !2, i64 8}
!4 = !{!"Int64Ptr", !0, i64 0}
!5 = !{!"IntArray", !1, i64 0, !4, i64 8}
!6 = !{!"StringPtr", !0, i64 0}
!7 = !{!"StringArray", !1, i64 0, !6, i64 8}
!8 = !{!"FloatPtr", !0, i64 0}
!9 = !{!"FloatArray", !1, i64 0, !8, i64 8}

; Access path tags — used on load/store !tbaa annotations:
!10 = !{!3, !1, i64 0}   ; String.len   (Int64 at offset 0)
!11 = !{!3, !2, i64 8}   ; String.data  (CharPtr at offset 8)
!12 = !{!5, !1, i64 0}   ; IntArray.len
!13 = !{!5, !4, i64 8}   ; IntArray.data
!14 = !{!7, !1, i64 0}   ; StringArray.len
!15 = !{!7, !6, i64 8}   ; StringArray.data
!16 = !{!9, !1, i64 0}   ; FloatArray.len
!17 = !{!9, !8, i64 8}   ; FloatArray.data
```

----

> **A note on the quality of this IR — SROA-friendly codegen**
>
> SROA (Scalar Replacement of Aggregates) is an LLVM optimization pass that eliminates
> unnecessary stack allocations. When a compiler naively builds a struct, it typically does:
> `alloca` a stack slot → `getelementptr` to each field → `store` values → `load` the whole
> struct back. SROA's job is to recognize that the struct never actually *needs* to live in
> memory and replace all of that with direct register operations (`insertvalue`/`extractvalue`).
>
> The MML compiler already emits the scalar, post-SROA form in most places, particularly
> the operator wrappers. For example, `op.writeln.2` receives a `%struct.String` by value and
> immediately does `extractvalue` to decompose it into `(len, ptr)` scalars. No alloca, no
> GEP, no memory traffic. This means LLVM's SROA pass has little left to do on these paths.
> (Other passes like inlining, arg promotion, and mem2reg still have work to do. SROA isn't
> the only aggregate-related cleanup. But the point is that MML doesn't generate the kind of
> alloca-heavy code that makes SROA work hard.)
>
> The `__mk_*` constructors *do* use the alloca→store→load pattern (going scalars → aggregate),
> but SROA trivially collapses these into `insertvalue` instructions, and when a `__mk_String`
> feeds directly into an `extractvalue`, the entire round-trip optimizes to nothing.
>
> MML generates IR that cooperates with LLVM's optimizer rather than fighting it.
> At `-O2`, LLVM can focus on the interesting optimizations (inlining, loop transforms,
> vectorization) instead of spending passes cleaning up messy struct manipulation. At `-O0`,
> the operator wrappers are already clean, though the literal-wrapping alloca pattern does
> introduce some unnecessary stack traffic that only disappears with optimization enabled.

> **Tail Call Optimization — recursion compiled to loops**
>
> The `loop` function in the MML source is written as tail recursion:
> ```
> fn loop(i: Int, to: Int): Unit =
>   if i <= to then
>     println (make_test_results ());
>     loop (i+1) to    ← recursive call in tail position
>   end
> ```
> The MML compiler detects that the recursive call to `loop` is in tail position: it's the
> last thing the function does, with no work after it returns. The compiler transforms it into
> an iterative loop *before* emitting IR. The generated code uses `br label %loop.header`
> (a backward jump) and `phi` nodes to merge the loop counter, rather than a `call` to itself.
>
> This matters for two reasons. First, **constant stack space**: a naive recursive version
> would push 1000 stack frames (one per iteration), risking stack overflow for large ranges.
> The loop version uses the same stack frame throughout. Second, **no call/ret overhead**:
> a backward branch avoids the cost of pushing/popping frames, saving/restoring registers,
> and the function call machinery. (A well-predicted branch is cheap, though not literally
> free; mispredicts still hurt. But the dominant win here is eliminating 1000 stack frames.)
>
> Note that this TCO happens in the MML compiler itself, not in LLVM. LLVM *can* do tail
> call elimination, but only under specific conditions (e.g., the `musttail` annotation).
> By handling it at the source level, MML guarantees the optimization regardless of LLVM's
> optimization level — even at `-O0`, the loop is a loop.

; ModuleID = 'InnerFnCapturesSiblingFn'
target triple = "x86_64-apple-macosx"

; ============================================================================
; MML Source:
;
;   pub fn main(): Unit =
;     fn inc(x: Int): Int = x + 1;;
;     fn applyInc(y: Int): Int = inc y;;
;     let result = applyInc 41;
;     println (int_to_str result);
;   ;
;
; Key idea: `applyInc` is a local fn that *captures* sibling local fn `inc`.
; Since `inc` is a value (a closure pair) in scope, capturing it means
; `applyInc` needs a heap-allocated closure environment that stores `inc`
; as a field of function type { ptr, ptr } (code pointer + env pointer).
;
; `inc` itself captures nothing, so its env pointer is null —
; but it still has the uniform closure representation { fnPtr, envPtr }.
; ============================================================================

; --- Type definitions -------------------------------------------------------

; The closure environment struct for applyInc.
; Layout:
;   [0]  ptr               — destructor function pointer (always slot 0)
;   [8]  { ptr, ptr }      — captured closure value `inc` (code ptr + env ptr)
%closure_env_innerfncapturessiblingfn_applyInc_1 = type { ptr, { ptr, ptr } }

; The "generic" name the TBAA metadata references. Same layout, just with
; legacy i8* instead of opaque ptr for the dtor slot.
%struct.__closure_env_0 = type { i8*, { ptr, ptr } }

; MML's built-in aggregate types — only String is used in this program.
%struct.String      = type { i64, i8* }   ; { length, data_ptr }
%struct.StringArray = type { i64, %struct.String* }
%struct.IntArray    = type { i64, i64* }
%struct.FloatArray  = type { i64, float* }

; --- External functions (only those actually called) ------------------------

declare void    @println(i64, i8*)           ; print string + newline
declare void    @__free_String(i64, i8*)     ; free a String's buffer
declare %struct.String @int_to_str(i64)      ; Int → String
declare ptr     @malloc(i64)                 ; libc malloc
declare void    @mml_free_raw(i8*)           ; free raw (non-typed) heap alloc
declare void    @mml_sys_flush()             ; flush stdout at program exit

; ============================================================================
; inc — the inner function `fn inc(x: Int): Int = x + 1`
;
; Signature:  (i64 %x, ptr %env) → i64
;
; All MML closures share the uniform calling convention:
;   (payload args..., ptr %env)
; `inc` captures nothing, so %env is always null — but the parameter
; is still present so every closure can be called through the same
; { fnPtr, envPtr } pair.
; ============================================================================
defstore ptr @innerfncapturessiblingfn___free___closure_env_0ine internal i64 @innerfncapturessiblingfn_inc_0(i64 %0, ptr %1) #0 {
entry:
  %2 = add i64 %0, 1          ; x + 1
  ret i64 %2
}

; ============================================================================
; applyInc — the inner function `fn applyInc(y: Int): Int = inc y`
;
; This one *does* capture `inc`, so it reads the captured closure pair
; out of its environment and calls through it.
; ============================================================================
define internal i64 @innerfncapturessiblingfn_applyInc_1(i64 %0, ptr %1) #0 {
entry:
  ; --- Load the captured `inc` closure from the env struct ----
  ; GEP to field [1] of the env: the { ptr, ptr } holding `inc`.
  %2 = getelementptr %closure_env_innerfncapturessiblingfn_applyInc_1, ptr %1, i32 0, i32 1

  ; Load the full closure pair { code_ptr, env_ptr }.
  %3 = load { ptr, ptr }, ptr %2

  ; Destructure the pair:
  %4 = extractvalue { ptr, ptr } %3, 0   ; inc's code pointer
  %5 = extractvalue { ptr, ptr } %3, 1   ; inc's env pointer (null)

  ; --- Indirect call through the captured closure ----
  ; Equivalent to: inc(y)
  %6 = call i64 %4(i64 %0, ptr %5)
  ret i64 %6
}

; ============================================================================
; Destructor for applyInc's closure environment.
;
; Slot 0 of every closure env is a pointer to its destructor.
; For this env, the only resource is the malloc'd block itself —
; the captured `inc` closure has a null env, so nothing inside
; needs recursive freeing.
; ============================================================================
define internal void @innerfncapturessiblingfn___free___closure_env_0(i8* %0) #0 {
entry:
  call void @mml_free_raw(i8* %0)   ; free the env block
  ret void
}

; ============================================================================
; Module-level main — the body of `pub fn main(): Unit`
;
; This is where the closure construction and the call happen.
; ============================================================================
define void @innerfncapturessiblingfn_main() #0 {
entry:
  ; === 1. Build the closure environment for `applyInc` =======================
  ;
  ; The env struct is 24 bytes:
  ;   [0..7]   ptr   — dtor pointer          (8 bytes)
  ;   [8..23]  {ptr,ptr} — captured `inc`    (16 bytes)

  %0 = call ptr @malloc(i64 24)

  ; --- Store the destructor in slot 0 ---
  %1 = getelementptr %closure_env_innerfncapturessiblingfn_applyInc_1, ptr %0, i32 0, i32 0
  store ptr @innerfncapturessiblingfn___free___closure_env_0, ptr %1

  ; --- Store the `inc` closure pair in slot 1 ---
  ; `inc` has code = @innerfncapturessiblingfn_inc_0, env = null
  ; (null because `inc` captures nothing).
  %2 = getelementptr %closure_env_innerfncapturessiblingfn_applyInc_1, ptr %0, i32 0, i32 1
  store { ptr, ptr } { ptr @innerfncapturessiblingfn_inc_0, ptr null }, ptr %2

  ; === 2. Build the `applyInc` closure pair (code ptr + env ptr) =============
  ;
  ; { ptr, ptr } is MML's uniform closure representation:
  ;   .0 = pointer to the function's code
  ;   .1 = pointer to the heap-allocated env (or null if no captures)

  %3 = insertvalue { ptr, ptr } undef, ptr @innerfncapturessiblingfn_applyInc_1, 0
  %4 = insertvalue { ptr, ptr } %3, ptr %0, 1
  ; %4 is now { @applyInc_1, %0 } — a fully formed closure value.

  ; === 3. Call `applyInc 41` =================================================
  ;
  ; Destructure the closure pair to get code and env, then indirect-call.

  %5 = extractvalue { ptr, ptr } %4, 0   ; code ptr
  %6 = extractvalue { ptr, ptr } %4, 1   ; env ptr
  %7 = call i64 %5(i64 41, ptr %6)       ; applyInc(41) → 42

  ; === 4. Convert result to string and print =================================

  %8 = call %struct.String @int_to_str(i64 %7)   ; 42 → "42"

  ; Destructure the String { len, data } for println.
  %9  = extractvalue %struct.String %8, 0         ; length
  %10 = extractvalue %struct.String %8, 1         ; data pointer
  call void @println(i64 %9, i8* %10)             ; prints "42\n"

  ; === 5. Cleanup: free the temporary String =================================

  %11 = extractvalue %struct.String %8, 0
  %12 = extractvalue %struct.String %8, 1
  call void @__free_String(i64 %11, i8* %12)

  ; === 6. Cleanup: free applyInc's closure env ===============================
  ;
  ; Extract the env pointer from the closure pair and call its dtor
  ; (which is the function stored in slot 0 of the env, but here we
  ; call the dtor directly by name — the slot-0 pointer is for when
  ; generic code needs to destroy an env it doesn't know the type of).

  %13 = extractvalue { ptr, ptr } %4, 1           ; env ptr
  call void @innerfncapturessiblingfn___free___closure_env_0(ptr %13)

  ret void
}

; ============================================================================
; C entry point — calls the MML main, flushes, exits.
; ============================================================================
define i32 @main(i32 %0, ptr %1) #0 {
entry:
  call void @innerfncapturessiblingfn_main()
  call void @mml_sys_flush()       ; flush any buffered output
  ret i32 0
}

; --- Attributes --------------------------------------------------------------
attributes #0 = { "target-cpu"="skylake" }

; ============================================================================
; TBAA (Type-Based Alias Analysis) Metadata
;
; MML emits struct-path TBAA so LLVM can prove that accesses to
; different struct fields don't alias.
;
; The tree rooted at !0 ("MML TBAA Root"):
;
;   MML TBAA Root
;   ├── Int64       — i64 scalars
;   ├── CharPtr     — i8* (string data pointers)
;   ├── Int64Ptr    — i64* (int array data pointers)
;   ├── StringPtr   — %struct.String* (string array data pointers)
;   ├── FloatPtr    — float* (float array data pointers)
;   ├── RawPtr      — i8* (opaque/raw pointers, e.g. dtor in env)
;   └── Function    — { ptr, ptr } closure values
;
; Struct-level TBAA nodes compose these scalars at known offsets:
;   String      = { Int64 @0, CharPtr @8 }
;   IntArray    = { Int64 @0, Int64Ptr @8 }
;   StringArray = { Int64 @0, StringPtr @8 }
;   FloatArray  = { Int64 @0, FloatPtr @8 }
;   __closure_env_0 = { RawPtr @0, Function @8 }
;
; The closure env's TBAA is the interesting one for this test:
;   !12 = { RawPtr @0, Function @8 }
; This tells LLVM that a load from offset 8 of a closure env
; (the captured `inc` function) cannot alias with, say, an Int64
; field of a String — enabling optimizations across closure calls.
; ============================================================================
!0  = !{!"MML TBAA Root"}
!1  = !{!"Int64",     !0, i64 0}
!2  = !{!"CharPtr",   !0, i64 0}
!3  = !{!"String",    !1, i64 0, !2, i64 8}
!4  = !{!"Int64Ptr",  !0, i64 0}
!5  = !{!"IntArray",  !1, i64 0, !4, i64 8}
!6  = !{!"StringPtr", !0, i64 0}
!7  = !{!"StringArray", !1, i64 0, !6, i64 8}
!8  = !{!"FloatPtr",  !0, i64 0}
!9  = !{!"FloatArray", !1, i64 0, !8, i64 8}
!10 = !{!"RawPtr",    !0, i64 0}
!11 = !{!"Function",  !0, i64 0}
!12 = !{!"__closure_env_0", !10, i64 0, !11, i64 8}

; Access-path metadata (used by !tbaa on load/store instructions):
;   !13 = String.len    (String → Int64 @ offset 0)
;   !14 = String.data   (String → CharPtr @ offset 8)
;   !15 = IntArray.len
;   !16 = IntArray.data
;   !17 = StringArray.len
;   !18 = StringArray.data
;   !19 = FloatArray.len
;   !20 = FloatArray.data
!13 = !{!3, !1, i64 0}
!14 = !{!3, !2, i64 8}
!15 = !{!5, !1, i64 0}
!16 = !{!5, !4, i64 8}
!17 = !{!7, !1, i64 0}
!18 = !{!7, !6, i64 8}
!19 = !{!9, !1, i64 0}
!20 = !{!9, !8, i64 8}

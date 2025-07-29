The global value code generation logic in the compiler should be refactored to distinguish between statically known constant expressions and dynamic expressions requiring runtime initialization. Currently, all global bindings are initialized via per-binding _init_global_* functions registered in @llvm.global_ctors, even when the initializer is a compile-time constant (e.g., string literals or literals like 1 + 1). This results in unnecessary runtime code and bloated IR. The revised implementation must introduce a compile-time check to determine whether an expression is fully constant. If so, the compiler should emit a static LLVM global definition with the evaluated value directly embedded, eliminating the need for an initialization function or registration in llvm.global_ctors.

Expressions that cannot be statically evaluated (e.g., involving function calls, conditionals, or runtime values) must still fall back to the current mechanism: emitting a dedicated _init_global_* function that computes and stores the value into the global, and registering it in @llvm.global_ctors. The compiler must cleanly separate the logic for both paths and treat the constant-evaluation capability as a first-class decision point during IR emission. This change will reduce IR complexity, avoid redundant runtime logic, and ensure more efficient startup and link-time performance. Future enhancements may include partial constant folding to extend static evaluation beyond literals.


Example Code:

let a = "Fede";
let b = "Fede";

fn print (a: String): () = @native;
fn concat (a: String, b: String): String = @native;

fn main(): () = 
  print (concat a b)
;

Unoptimized:

; ModuleID = 'ManyGlobals'
target triple = "x86_64-unknown-unknown"
; Native type definitions
%String = type { i64, i8* }

; String constants
@str.0 = private constant [4 x i8] c"Fede", align 1

; External functions
declare void @print(%String)
declare %String @concat(%String, %String)

@a = global %String zeroinitializer
define internal void @_init_global_a() {
entry:
  %0 = add i64 0, 4
  %1 = getelementptr [4 x i8], [4 x i8]* @str.0, i64 0, i64 0
  %2 = alloca %String
  %3 = getelementptr %String, %String* %2, i32 0, i32 0
  store i64 %0, i64* %3
  %4 = getelementptr %String, %String* %2, i32 0, i32 1
  store i8* %1, i8** %4
  %5 = load %String, %String* %2
  store %String %5, %String* @a
  ret void
}

@b = global %String zeroinitializer
define internal void @_init_global_b() {
entry:
  %0 = add i64 0, 4
  %1 = getelementptr [4 x i8], [4 x i8]* @str.0, i64 0, i64 0
  %2 = alloca %String
  %3 = getelementptr %String, %String* %2, i32 0, i32 0
  store i64 %0, i64* %3
  %4 = getelementptr %String, %String* %2, i32 0, i32 1
  store i8* %1, i8** %4
  %5 = load %String, %String* %2
  store %String %5, %String* @b
  ret void
}

define void @main() {
entry:
  %0 = load %String, %String* @a
  %1 = load %String, %String* @b
  %2 = call %String @concat(%String %0, %String %1)
  call void @print(%String %2)
  %3 = add i32 0, 0
  ret void
}

@llvm.global_ctors = appending global [2 x { i32, void ()*, i8* }] [
  { i32, void ()*, i8* } { i32 65535, void ()* @_init_global_a, i8* null },
  { i32, void ()*, i8* } { i32 65535, void ()* @_init_global_b, i8* null }
]


Optimized by llvm:

; ModuleID = '/Users/f/Workshop/mine/mml/mml/build/out/x86_64-apple-macosx/ManyGlobals.bc'
source_filename = "/Users/f/Workshop/mine/mml/mml/build/ManyGlobals.ll"
target datalayout = "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"
target triple = "x86_64-unknown-unknown"

%String = type { i64, ptr }

@str.0 = private constant [4 x i8] c"Fede", align 1
@a = local_unnamed_addr global %String { i64 4, ptr @str.0 }
@b = local_unnamed_addr global %String { i64 4, ptr @str.0 }
@llvm.global_ctors = appending global [0 x { i32, ptr, ptr }] zeroinitializer

declare void @print(%String) local_unnamed_addr

declare %String @concat(%String, %String) local_unnamed_addr

define void @main() local_unnamed_addr {
entry:
  %.unpack = load i64, ptr @a, align 8
  %0 = insertvalue %String poison, i64 %.unpack, 0
  %.unpack1 = load ptr, ptr getelementptr inbounds nuw (i8, ptr @a, i64 8), align 8
  %1 = insertvalue %String %0, ptr %.unpack1, 1
  %.unpack2 = load i64, ptr @b, align 8
  %2 = insertvalue %String poison, i64 %.unpack2, 0
  %.unpack3 = load ptr, ptr getelementptr inbounds nuw (i8, ptr @b, i64 8), align 8
  %3 = insertvalue %String %2, ptr %.unpack3, 1
  %4 = tail call %String @concat(%String %1, %String %3)
  tail call void @print(%String %4)
  ret void
}



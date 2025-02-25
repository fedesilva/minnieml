; ModuleID = 'out/program-2-two-lets-one-fn.bc'
source_filename = "program-2-two-lets-one-fn.ll"
target datalayout = "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"
target triple = "x86_64-unknown-unknown"

@a = local_unnamed_addr global i32 2
@b = local_unnamed_addr global i32 5

; Function Attrs: mustprogress nofree norecurse nosync nounwind willreturn memory(read, argmem: none, inaccessiblemem: none)
define i32 @main() local_unnamed_addr #0 {
entry:
  %0 = load i32, ptr @a, align 4
  %1 = load i32, ptr @b, align 4
  %2 = add i32 %1, %0
  ret i32 %2
}

attributes #0 = { mustprogress nofree norecurse nosync nounwind willreturn memory(read, argmem: none, inaccessiblemem: none) }

; ModuleID = 'ackermann.c'
source_filename = "ackermann.c"
target datalayout = "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"
target triple = "x86_64-apple-macosx15.0.0"

@.str = private unnamed_addr constant [25 x i8] c"ackermann(3, 10) = %lld\0A\00", align 1

; Function Attrs: nofree nounwind ssp uwtable
define noundef i32 @main() local_unnamed_addr #0 {
  %1 = tail call fastcc i64 @ackermann(i64 noundef 3, i64 noundef 10)
  %2 = tail call i32 (ptr, ...) @printf(ptr noundef nonnull dereferenceable(1) @.str, i64 noundef %1)
  ret i32 0
}

; Function Attrs: nofree nosync nounwind ssp memory(none) uwtable
define internal fastcc range(i64 -9223372036854775807, -9223372036854775808) i64 @ackermann(i64 noundef range(i64 0, 4) %0, i64 noundef %1) unnamed_addr #1 {
  %3 = icmp eq i64 %0, 0
  br i1 %3, label %4, label %7

4:                                                ; preds = %11, %2
  %5 = phi i64 [ %1, %2 ], [ %12, %11 ]
  %6 = add nsw i64 %5, 1
  ret i64 %6

7:                                                ; preds = %2, %11
  %8 = phi i64 [ %12, %11 ], [ %1, %2 ]
  %9 = phi i64 [ %13, %11 ], [ %0, %2 ]
  %10 = icmp eq i64 %8, 0
  br i1 %10, label %11, label %15

11:                                               ; preds = %7, %15
  %12 = phi i64 [ %17, %15 ], [ 1, %7 ]
  %13 = add nsw i64 %9, -1
  %14 = icmp eq i64 %13, 0
  br i1 %14, label %4, label %7

15:                                               ; preds = %7
  %16 = add nsw i64 %8, -1
  %17 = tail call fastcc i64 @ackermann(i64 noundef %9, i64 noundef %16)
  br label %11
}

; Function Attrs: nofree nounwind
declare noundef i32 @printf(ptr noundef readonly captures(none), ...) local_unnamed_addr #2

attributes #0 = { nofree nounwind ssp uwtable "frame-pointer"="all" "min-legal-vector-width"="0" "no-trapping-math"="true" "stack-protector-buffer-size"="8" "target-cpu"="penryn" "target-features"="+cmov,+cx16,+cx8,+fxsr,+mmx,+sahf,+sse,+sse2,+sse3,+sse4.1,+ssse3,+x87" "tune-cpu"="generic" }
attributes #1 = { nofree nosync nounwind ssp memory(none) uwtable "frame-pointer"="all" "min-legal-vector-width"="0" "no-trapping-math"="true" "stack-protector-buffer-size"="8" "target-cpu"="penryn" "target-features"="+cmov,+cx16,+cx8,+fxsr,+mmx,+sahf,+sse,+sse2,+sse3,+sse4.1,+ssse3,+x87" "tune-cpu"="generic" }
attributes #2 = { nofree nounwind "frame-pointer"="all" "no-trapping-math"="true" "stack-protector-buffer-size"="8" "target-cpu"="penryn" "target-features"="+cmov,+cx16,+cx8,+fxsr,+mmx,+sahf,+sse,+sse2,+sse3,+sse4.1,+ssse3,+x87" "tune-cpu"="generic" }

!llvm.module.flags = !{!0, !1, !2, !3, !4}
!llvm.ident = !{!5}

!0 = !{i32 2, !"SDK Version", [2 x i32] [i32 15, i32 5]}
!1 = !{i32 1, !"wchar_size", i32 4}
!2 = !{i32 8, !"PIC Level", i32 2}
!3 = !{i32 7, !"uwtable", i32 2}
!4 = !{i32 7, !"frame-pointer", i32 2}
!5 = !{!"Homebrew clang version 21.1.5"}

; ModuleID = 'mml'
target triple = "x86_64-unknown-unknown"


define i32 @main() {
entry:
  %0 = add i32 1, 0
  %1 = add i32 1, 0
  %2 = add i32 %0, %1
  ret i32 %2
}

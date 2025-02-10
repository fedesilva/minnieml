; ModuleID = 'mml'
target triple = "x86_64-unknown-unknown"


%0 = add i32 2, 0
@a = global i32 %0
%1 = add i32 5, 0
@b = global i32 %1
define i32 @main() {
entry:
  %0 = load i32, i32* %a
  %1 = load i32, i32* %b
  %2 = add i32 %0, %1
  ret i32 %2
}

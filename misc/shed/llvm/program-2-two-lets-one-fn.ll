; ModuleID = 'Anon'
target triple = "x86_64-unknown-unknown"

@a = global i32 2
@b = global i32 5
define i32 @main() {
entry:
  %0 = load i32, i32* @a
  %1 = load i32, i32* @b
  %2 = add i32 %0, %1
  ret i32 %2
}
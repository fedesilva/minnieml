; ModuleID = 'Anon'
target triple = "x86_64-unknown-unknown"

@x = global i32 3
@y = global i32 4
@a = global i32 0
define internal void @_init_global_a() {
entry:
  %0 = load i32, i32* @y
  %1 = sdiv i32 3, %0
  %2 = load i32, i32* @x
  %3 = mul i32 %1, %2
  %4 = add i32 2, %3
  store i32 %4, i32* @a
  ret void
}

@llvm.global_ctors = appending global [1 x { i32, void ()*, i8* }] [
  { i32, void ()*, i8* } { i32 65535, void ()* @_init_global_a, i8* null }
]

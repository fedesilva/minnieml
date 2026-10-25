%Pair = type { i32, i32 }
@counter = global i32 0
$group = comdat any
declare i32 @external(i32)
define i32 @first(i32 %input) #0 {
entry:
  %value = add i32 %input, 1
  br label %done
done:
  %result = call i32 @external(i32 %value)
  ret i32 %result
}
define i32 @second(i32 %input) {
entry:
  %value = add i32 %input, 2
  ret i32 %value
}
attributes #0 = { nounwind }
!0 = !{i32 1}

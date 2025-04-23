; Front-end language source code:
;
; # Front-end language definition
; # Int is an alias for llvm's i64
; # String is immutable, struct of char[]* and length (i64) ish
; data Person {
;    name : String
;    age : Int
; };
;
; let 
;   name = "Fede",
;   age = 50
; ;
;
; let p = Person name age;
;
; # LLVM IR translation of the above:
; Type definitions
%String = type { i8*, i64 }            ; String = { char* data, i64 length }
%Person = type { %String, i64 }        ; Person = { String name, i64 age }

; TBAA Metadata definitions
!0 = !{!"root"}                                  ; Root node of TBAA hierarchy
!1 = !{!"Int", !0}                               ; Int type, child of root
!2 = !{!"String", !0}                            ; String type, child of root
!3 = !{!"Person", !0}                            ; Person type, child of root
!4 = !{!"name_field", !3, i64 0}                 ; Field 'name' in Person at offset 0
!5 = !{!"age_field", !3, i64 16}                 ; Field 'age' in Person at offset 16 (after String struct)
!6 = !{!"String.data", !2, i64 0}                ; Field 'data' in String at offset 0
!7 = !{!"String.length", !2, i64 8}              ; Field 'length' in String at offset 8

; Function to create a Person
define %Person @create_person(%String %name, i64 %age) {
entry:
  ; Allocate memory for Person
  %person = alloca %Person, align 8
  
  ; Store name field
  %name_field_ptr = getelementptr %Person, %Person* %person, i32 0, i32 0
  store %String %name, %String* %name_field_ptr, !tbaa !4  ; TBAA: accessing name field
  
  ; Store age field
  %age_field_ptr = getelementptr %Person, %Person* %person, i32 0, i32 1
  store i64 %age, i64* %age_field_ptr, !tbaa !5           ; TBAA: accessing age field
  
  ; Return the Person
  %result = load %Person, %Person* %person, !tbaa !3      ; TBAA: accessing Person struct
  ret %Person %result
}

; Function to get person's name
define %String @get_name(%Person* %person) {
entry:
  ; Access name field
  %name_ptr = getelementptr %Person, %Person* %person, i32 0, i32 0
  %name = load %String, %String* %name_ptr, !tbaa !4      ; TBAA: accessing name field
  ret %String %name
}

; Function to get person's age
define i64 @get_age(%Person* %person) {
entry:
  ; Access age field
  %age_ptr = getelementptr %Person, %Person* %person, i32 0, i32 1
  %age = load i64, i64* %age_ptr, !tbaa !5               ; TBAA: accessing age field
  ret i64 %age
}

; Example of how TBAA helps with optimization
define void @optimization_example(%Person* %p1, %Person* %p2) {
entry:
  ; Load person1's age
  %age_ptr1 = getelementptr %Person, %Person* %p1, i32 0, i32 1
  %age1 = load i64, i64* %age_ptr1, !tbaa !5            ; TBAA: accessing age field
  
  ; Store to person2's name (a String field)
  %name_ptr2 = getelementptr %Person, %Person* %p2, i32 0, i32 0
  %name2 = load %String, %String* %name_ptr2, !tbaa !4  ; TBAA: accessing name field
  
  ; Do something with name2
  %name_data_ptr = getelementptr %String, %String* %name_ptr2, i32 0, i32 0
  %name_data = load i8*, i8** %name_data_ptr, !tbaa !6  ; TBAA: accessing String.data field
  
  ; Now use age1 again - TBAA tells LLVM this load can't be affected by the store to name field
  ; So LLVM can optimize by reusing the previously loaded age1 value rather than reloading it
  %age1_plus1 = add i64 %age1, 1
  
  ret void
}

; Function to explain our example from the post
define void @example_code() {
entry:
  ; let name = "Fede"
  %name_data = global [5 x i8] c"Fede\00"
  %name_length = add i64 0, 4
  %name = alloca %String, align 8
  %name_data_ptr = getelementptr %String, %String* %name, i32 0, i32 0
  %name_length_ptr = getelementptr %String, %String* %name, i32 0, i32 1
  store i8* getelementptr ([5 x i8], [5 x i8]* %name_data, i32 0, i32 0), i8** %name_data_ptr, !tbaa !6
  store i64 %name_length, i64* %name_length_ptr, !tbaa !7
  
  ; let age = 50
  %age = add i64 0, 50
  
  ; let p = Person name age
  %p = alloca %Person, align 8
  %p_name_ptr = getelementptr %Person, %Person* %p, i32 0, i32 0
  %p_age_ptr = getelementptr %Person, %Person* %p, i32 0, i32 1
  %name_val = load %String, %String* %name, !tbaa !2
  store %String %name_val, %String* %p_name_ptr, !tbaa !4
  store i64 %age, i64* %p_age_ptr, !tbaa !5
  
  ret void
}

; Commentary on key points about TBAA:
; 1. TBAA is a description of types and their relationships only
; 2. It doesn't know about mutability - that's handled separately in LLVM
; 3. Each module contains its own TBAA hierarchy, just for the types it uses
; 4. The hierarchy tells LLVM which memory accesses cannot possibly alias each other
; 5. This lets LLVM perform more aggressive optimizations like:
;    - Reordering loads/stores that access different types
;    - Eliminating redundant loads when stores to different types happen in between
;    - Hoisting loads out of loops when the loop stores to unrelated types

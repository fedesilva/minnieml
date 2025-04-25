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
;   age = 30
; ;
;
; let p = Person name age;
;
; # LLVM IR translation of the above:

; Type definitions - these define the data structures we'll use
; String = { char* data, i64 length } - A String consists of a pointer to characters and a length
%String = type { i8*, i64 }
            
; Person = { String name, i64 age } - A Person consists of a String (name) and an integer (age)
%Person = type { %String, i64 }

; TBAA Metadata definitions - these help LLVM understand the relationships between types for optimization
; Root node of TBAA hierarchy - all types descend from this
!0 = !{!"root"}

; Int type, child of root - defines the integer type
!1 = !{!"Int", !0}

; String type, child of root - defines the String type
!2 = !{!"String", !0}

; Person type, child of root - defines the Person type
!3 = !{!"Person", !0}

; Field 'name' in Person at offset 0 - the first field in Person
!4 = !{!"name_field", !3, i64 0}

; Field 'age' in Person at offset 16 (after String struct)
; Note: offset is 16 bytes because String takes 16 bytes (8 for pointer + 8 for length)
!5 = !{!"age_field", !3, i64 16}

; Field 'data' in String at offset 0 - the character pointer
!6 = !{!"String.data", !2, i64 0}

; Field 'length' in String at offset 8 - the length field after the pointer
!7 = !{!"String.length", !2, i64 8}

; Function to create a Person
define %Person @create_person(%String %name, i64 %age) {
entry:
  ; Allocate memory for a Person structure on the stack
  %person = alloca %Person, align 8
  
  ; Get a pointer to the name field of the Person structure
  %name_field_ptr = getelementptr %Person, %Person* %person, i32 0, i32 0
  
  ; Store the name parameter into the name field
  ; The !tbaa metadata helps LLVM understand this is accessing the name field
  store %String %name, %String* %name_field_ptr, !tbaa !4
  
  ; Get a pointer to the age field of the Person structure
  %age_field_ptr = getelementptr %Person, %Person* %person, i32 0, i32 1
  
  ; Store the age parameter into the age field
  ; The !tbaa metadata helps LLVM understand this is accessing the age field
  store i64 %age, i64* %age_field_ptr, !tbaa !5
  
  ; Load the entire Person structure to return it
  ; The !tbaa metadata helps LLVM understand this is accessing a Person
  %result = load %Person, %Person* %person, !tbaa !3
  
  ; Return the Person structure
  ret %Person %result
}

; Function to get person's name
define %String @get_name(%Person* %person) {
entry:
  ; Get a pointer to the name field of the Person structure
  %name_ptr = getelementptr %Person, %Person* %person, i32 0, i32 0
  
  ; Load the String value from the name field
  ; The !tbaa metadata helps LLVM understand this is accessing the name field
  %name = load %String, %String* %name_ptr, !tbaa !4
  
  ; Return the String value
  ret %String %name
}

; Function to get person's age
define i64 @get_age(%Person* %person) {
entry:
  ; Get a pointer to the age field of the Person structure
  %age_ptr = getelementptr %Person, %Person* %person, i32 0, i32 1
  
  ; Load the integer value from the age field
  ; The !tbaa metadata helps LLVM understand this is accessing the age field
  %age = load i64, i64* %age_ptr, !tbaa !5
  
  ; Return the age value
  ret i64 %age
}

; Example of how TBAA helps with optimization
; This is a CONTRIVED example showing code BEFORE and AFTER optimization
; to demonstrate TBAA's purpose

; This represents what a front-end compiler might naively generate BEFORE optimization
; (This is contrived - a good compiler wouldn't actually generate this redundant load)
define void @optimization_example_before_opt(%Person* %p1, %Person* %p2) {
entry:
  ; Get a pointer to person1's age field
  %age_ptr1 = getelementptr %Person, %Person* %p1, i32 0, i32 1
  
  ; First load of person1's age
  %age1 = load i64, i64* %age_ptr1, !tbaa !5
  
  ; Operations on person2's name fields
  %name_ptr2 = getelementptr %Person, %Person* %p2, i32 0, i32 0
  %name2 = load %String, %String* %name_ptr2, !tbaa !4
  %name_data_ptr = getelementptr %String, %String* %name_ptr2, i32 0, i32 0
  %name_data = load i8*, i8** %name_data_ptr, !tbaa !6
  
  ; Without TBAA, compiler might need to reload age1 here
  ; since it can't tell if operations on name affected age
  %age1_reload = load i64, i64* %age_ptr1, !tbaa !5
  
  ; Use the reloaded value
  %age1_plus1 = add i64 %age1_reload, 1
  
  ret void
}

; This represents what LLVM might produce AFTER optimization
; when it uses TBAA to determine that name operations can't affect age values
define void @optimization_example(%Person* %p1, %Person* %p2) {
entry:
  ; Get a pointer to person1's age field
  %age_ptr1 = getelementptr %Person, %Person* %p1, i32 0, i32 1
  
  ; Load person1's age value
  ; This marks the load as accessing the age field
  %age1 = load i64, i64* %age_ptr1, !tbaa !5
  
  ; Get pointer to person2's name field
  %name_ptr2 = getelementptr %Person, %Person* %p2, i32 0, i32 0
  
  ; Load the String from person2's name field
  ; This marks the load as accessing the name field
  %name2 = load %String, %String* %name_ptr2, !tbaa !4
  
  ; Get pointer to the data field in person2's name String
  %name_data_ptr = getelementptr %String, %String* %name_ptr2, i32 0, i32 0
  
  ; Load the character array pointer from the String
  ; This marks the load as accessing the data field of a String
  %name_data = load i8*, i8** %name_data_ptr, !tbaa !6
  
  ; Now use age1 again - TBAA tells LLVM this load can't be affected by the name operations
  ; So LLVM has optimized by eliminating the redundant reload of age1
  %age1_plus1 = add i64 %age1, 1
  
  ; Return from the function
  ret void
}

define void @example_code() {
entry:
  ; let name = "Fede"
  ; First, define global data for the string "Fede"
  ; Create a global array with the string "Fede" and null terminator
  %name_data = global [5 x i8] c"Fede\00"
  
  ; Calculate the length of "Fede" (which is 4)
  ; Creates the constant 4 (string length)
  %name_length = add i64 0, 4
  
  ; Allocate memory for a String structure on the stack
  %name = alloca %String, align 8
  
  ; Get pointers to the data and length fields of the String structure
  ; Get pointer to data field (index 0)
  %name_data_ptr = getelementptr %String, %String* %name, i32 0, i32 0
  
  ; Get pointer to length field (index 1)
  %name_length_ptr = getelementptr %String, %String* %name, i32 0, i32 1
  
  ; Store the address of the "Fede" string into the data field
  ; The !tbaa metadata marks this as accessing String.data
  store i8* getelementptr ([5 x i8], [5 x i8]* %name_data, i32 0, i32 0), i8** %name_data_ptr, !tbaa !6
  
  ; Store the length (4) into the length field
  ; The !tbaa metadata marks this as accessing String.length
  store i64 %name_length, i64* %name_length_ptr, !tbaa !7
  
  ; let age = 30
  ; Creates the constant 30
  %age = add i64 0, 30
  
  ; let p = Person name age
  ; Allocate memory for a Person structure on the stack
  %p = alloca %Person, align 8
  
  ; Get pointers to the name and age fields of the Person structure
  ; Get pointer to name field (index 0)
  %p_name_ptr = getelementptr %Person, %Person* %p, i32 0, i32 0
  
  ; Get pointer to age field (index 1)
  %p_age_ptr = getelementptr %Person, %Person* %p, i32 0, i32 1
  
  ; Load the String value that we created earlier
  ; The !tbaa metadata marks this as accessing a String
  %name_val = load %String, %String* %name, !tbaa !2
  
  ; Store the String into the name field of the Person
  ; The !tbaa metadata marks this as accessing Person.name
  store %String %name_val, %String* %p_name_ptr, !tbaa !4
  
  ; Store the age value into the age field of the Person
  ; The !tbaa metadata marks this as accessing Person.age
  store i64 %age, i64* %p_age_ptr, !tbaa !5
  
  ; Return from the function
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

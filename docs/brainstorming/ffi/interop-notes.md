
How types work now

1. NativeStruct (like String) -> LLVM type %TypeName (struct by value)
2. NativePointer -> LLVM type llvmType* (e.g., i8*)

When calling a function, emitCall generates:
call void @fn(%String %1, %String %2)   ; structs passed by value

The mutation problem

For String this is fine - print(String str) only reads, doesn't mutate.

For Buffer with my C code:
typedef BufferImpl *Buffer;  // Buffer IS a pointer
void buffer_write(Buffer b, String s) { b->length += ...; }

If I define Buffer as NativeStruct, LLVM passes the struct by value, but C expects a pointer. Type mismatch.

If I define Buffer as NativePointer(i8*), types match, but codegen doesn't know the struct layout.

The real issue

Codegen has no way to:
- Pass a NativeStruct by pointer (for mutation)
- Distinguish "pass by value" vs "pass by reference" semantics

Options

1. Keep NativePointer - works now, opaque type
2. Define struct fields but use pointer - needs something like NativePointerTo(NativeStruct)
3. Change C to pass struct by value, return modified struct - but I wanted Unit return

For now, NativePointer is the pragmatic choice.

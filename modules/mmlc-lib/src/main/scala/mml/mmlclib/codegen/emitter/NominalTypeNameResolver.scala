package mml.mmlclib.codegen.emitter

import mml.mmlclib.ast.*

/** Extract the nominal MML-level name carried by the AST.
  *
  * Metadata codegen must not guess nominal types back from raw LLVM layouts. If a bare native type
  * reaches one of these paths, that is an upstream compiler bug that should be exposed.
  */
def getNominalTypeName(typeSpec: Type): Either[CodeGenError, String] =
  typeSpec match
    case TypeGroup(_, types) if types.size == 1 =>
      getNominalTypeName(types.head)
    case TypeRef(_, name, _, _) =>
      Right(name)
    case ts: TypeStruct =>
      Right(ts.name)
    case _: TypeFn =>
      Right("Function")
    case _: TypeUnit =>
      Right("Unit")
    case _: TypeTuple =>
      Right("Tuple")
    case np: NativePrimitive =>
      Left(
        CodeGenError(CodeGenReason.BareNativePrimitive(np))
      )
    case np: NativePointer =>
      Left(
        CodeGenError(CodeGenReason.BareNativePointer(np))
      )
    case other =>
      Left(
        CodeGenError(CodeGenReason.MissingNominalName(other))
      )

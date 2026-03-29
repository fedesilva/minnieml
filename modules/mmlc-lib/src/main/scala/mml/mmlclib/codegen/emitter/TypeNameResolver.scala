package mml.mmlclib.codegen.emitter

import mml.mmlclib.ast.*

object TypeNameResolver:

  // TODO:QA: What is the purpose of this?
  //         (P1) issue affects reliability and extensibility.
  //         it is incredibly brittle given that a user can define a new primitive type
  //         mapped to a native type.
  //         we need to think about who uses this and for what, then come up with
  //         a way to find the right type using the ast.
  /** Derive the MML-level name for a type specification when possible.
    *
    * Returns a unique string (e.g., `String`, `Int64`, `i64`) that can be used for alias metadata
    * and debugging. This mirrors the behavior previously encapsulated in
    * `TbaaEmitter.getMmlTypeName`.
    */
  def getMmlTypeName(
    typeSpec:    Type,
    resolvables: ResolvablesIndex
  ): Either[CodeGenError, String] =
    typeSpec match
      case TypeGroup(_, types) if types.size == 1 =>
        getMmlTypeName(types.head, resolvables)
      case TypeRef(_, name, resolvedId, _) =>
        resolvedId.flatMap(resolvables.lookupType) match
          case Some(_: TypeDef) =>
            Right(name)
          case Some(_: TypeStruct) =>
            Right(name)
          case Some(_: TypeAlias) =>
            // Keep the alias name so metadata preserves distinct MML identities such as Int vs Int64.
            Right(name)
          case None =>
            Left(CodeGenError(s"Unresolved type reference '$name'"))
      case ts: TypeStruct =>
        Right(ts.name)
      case NativePrimitive(_, "i1", _, _) =>
        Right("Bool")
      case NativePrimitive(_, "i64", _, _) =>
        Right("Int")
      case NativePrimitive(_, "void", _, _) =>
        Right("Unit")
      case NativePrimitive(_, llvmType, _, _) =>
        Right(llvmType)
      case NativePointer(_, llvmType, _, _) =>
        Right(s"Pointer($llvmType)")
      case _: NativeStruct =>
        Right("NativeStruct")
      case _: TypeFn =>
        Right("Function")
      case _: TypeUnit =>
        Right("Unit")
      case _: TypeTuple =>
        Right("Tuple")
      case other =>
        Left(CodeGenError(s"Cannot extract type name from ${other.getClass.getSimpleName}"))

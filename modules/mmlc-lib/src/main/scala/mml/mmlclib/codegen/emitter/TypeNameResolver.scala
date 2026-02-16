package mml.mmlclib.codegen.emitter

import mml.mmlclib.ast.*

object TypeNameResolver:

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
      case TypeRef(_, name, resolvedId, _) =>
        resolvedId.flatMap(resolvables.lookupType) match
          case Some(_: TypeDef) =>
            Right(name)
          case Some(_: TypeStruct) =>
            Right(name)
          case Some(alias: TypeAlias) =>
            alias.typeSpec match
              case Some(spec) => getMmlTypeName(spec, resolvables)
              case None => getMmlTypeName(alias.typeRef, resolvables)
          case None =>
            Left(CodeGenError(s"Unresolved type reference '$name'"))
      case ts: TypeStruct =>
        Right(ts.name)
      case NativePrimitive(_, llvmType, _, _) =>
        Right(llvmType)
      case NativePointer(_, llvmType, _, _) =>
        Right(llvmType)
      case other =>
        Left(CodeGenError(s"Cannot extract type name from ${other.getClass.getSimpleName}"))

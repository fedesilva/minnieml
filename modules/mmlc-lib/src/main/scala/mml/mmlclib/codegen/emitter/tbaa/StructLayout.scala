package mml.mmlclib.codegen.emitter.tbaa

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{CodeGenError, alignOfLlvmType, alignTo, sizeOfLlvmType}

/** Computes struct field layouts for TBAA metadata generation. Handles nested structs by
  * recursively computing sizes and alignments from TypeSpec rather than LLVM type strings.
  */
object StructLayout:
  /** Compute size of a TypeSpec in bytes. Handles nested structs recursively. */
  def sizeOf(typeSpec: Type, resolvables: ResolvablesIndex): Either[CodeGenError, Int] =
    typeSpec match
      case TypeRef(_, name, resolvedId, _) =>
        resolvedId.flatMap(resolvables.lookupType) match
          case Some(td: TypeDef) =>
            td.typeSpec match
              case Some(ns: NativeStruct) => computeStructSize(ns.fields, resolvables)
              case Some(np: NativePrimitive) => Right(sizeOfLlvmType(np.llvmType))
              case Some(_: NativePointer) => Right(8)
              case _ => Left(CodeGenError(s"Cannot compute size for type: $name"))
          case Some(ts: TypeStruct) =>
            computeStructSize(ts.fields.toList.map(f => f.name -> f.typeSpec), resolvables)
          case Some(ta: TypeAlias) =>
            ta.typeSpec match
              case Some(spec) => sizeOf(spec, resolvables)
              case None => sizeOf(ta.typeRef, resolvables)
          case None =>
            Left(CodeGenError(s"Cannot compute size for unresolved type: $name"))
      case ns: NativeStruct => computeStructSize(ns.fields, resolvables)
      case np: NativePrimitive => Right(sizeOfLlvmType(np.llvmType))
      case _:  NativePointer => Right(8)
      case ts: TypeStruct =>
        computeStructSize(ts.fields.toList.map(f => f.name -> f.typeSpec), resolvables)
      case TypeUnit(_) => Right(0)
      case other => Left(CodeGenError(s"Cannot compute size for: ${other.getClass.getSimpleName}"))

  /** Compute alignment of a TypeSpec in bytes. Handles nested structs recursively. */
  def alignOf(typeSpec: Type, resolvables: ResolvablesIndex): Either[CodeGenError, Int] =
    typeSpec match
      case TypeRef(_, name, resolvedId, _) =>
        resolvedId.flatMap(resolvables.lookupType) match
          case Some(td: TypeDef) =>
            td.typeSpec match
              case Some(ns: NativeStruct) => computeStructAlignment(ns.fields, resolvables)
              case Some(np: NativePrimitive) => Right(alignOfLlvmType(np.llvmType))
              case Some(_: NativePointer) => Right(8)
              case _ => Left(CodeGenError(s"Cannot compute alignment for type: $name"))
          case Some(ts: TypeStruct) =>
            computeStructAlignment(ts.fields.toList.map(f => f.name -> f.typeSpec), resolvables)
          case Some(ta: TypeAlias) =>
            ta.typeSpec match
              case Some(spec) => alignOf(spec, resolvables)
              case None => alignOf(ta.typeRef, resolvables)
          case None =>
            Left(CodeGenError(s"Cannot compute alignment for unresolved type: $name"))
      case ns: NativeStruct => computeStructAlignment(ns.fields, resolvables)
      case np: NativePrimitive => Right(alignOfLlvmType(np.llvmType))
      case _:  NativePointer => Right(8)
      case ts: TypeStruct =>
        computeStructAlignment(ts.fields.toList.map(f => f.name -> f.typeSpec), resolvables)
      case TypeUnit(_) => Right(1)
      case other =>
        Left(CodeGenError(s"Cannot compute alignment for: ${other.getClass.getSimpleName}"))

  /** Compute total size of a struct including tail padding. */
  private def computeStructSize(
    fields:      List[(String, Type)],
    resolvables: ResolvablesIndex
  ): Either[CodeGenError, Int] =
    val lastOffsetE = fields.foldLeft(Right(0): Either[CodeGenError, Int]) {
      case (accE, (_, fieldTypeSpec)) =>
        for
          currentOffset <- accE
          fieldAlign <- alignOf(fieldTypeSpec, resolvables)
          fieldSize <- sizeOf(fieldTypeSpec, resolvables)
        yield
          val alignedOffset = alignTo(currentOffset, fieldAlign)
          alignedOffset + fieldSize
    }
    for
      lastOffset <- lastOffsetE
      structAlign <- computeStructAlignment(fields, resolvables)
    yield alignTo(lastOffset, structAlign)

  /** Compute alignment of a struct (max alignment of all fields). */
  private def computeStructAlignment(
    fields:      List[(String, Type)],
    resolvables: ResolvablesIndex
  ): Either[CodeGenError, Int] =
    fields.foldLeft(Right(1): Either[CodeGenError, Int]) { case (accE, (_, fieldTypeSpec)) =>
      for
        maxAlign <- accE
        fieldAlign <- alignOf(fieldTypeSpec, resolvables)
      yield maxAlign.max(fieldAlign)
    }

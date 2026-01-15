package mml.mmlclib.codegen.emitter.tbaa

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{CodeGenError, CodeGenState, TypeNameResolver}

object TbaaEmitter:

  /** Get or create a TBAA access tag for a given type spec. Uses the MML type name to preserve type
    * distinctions (e.g., Int64 vs SizeT even though both are i64).
    */
  def getTbaaTag(typeSpec: Type, state: CodeGenState): (CodeGenState, Option[String]) =
    typeSpec match
      case TypeRef(_, _, _, _) =>
        TypeNameResolver.getMmlTypeName(typeSpec, state.resolvables) match
          case Right(typeName) =>
            state.getTbaaAccessTag(typeName) match
              case (s2, tag) => (s2, Some(tag))
          case Left(_) => (state, None)
      case np: NativePrimitive =>
        state.getTbaaAccessTag(np.llvmType) match
          case (s2, tag) => (s2, Some(tag))
      case np: NativePointer =>
        state.getTbaaAccessTag(np.llvmType) match
          case (s2, tag) => (s2, Some(tag))
      case _ =>
        (state, None)

  /** Get TBAA access tag for a specific struct field.
    *
    * @param structTypeSpec
    *   The struct's TypeSpec (must resolve to a NativeStruct)
    * @param fieldIndex
    *   Which field is being accessed (0-based)
    * @param state
    *   The current code generation state
    * @return
    *   Either an error or (updated state, tag string)
    */
  def getTbaaStructFieldTag(
    structTypeSpec: Type,
    fieldIndex:     Int,
    state:          CodeGenState
  ): Either[CodeGenError, (CodeGenState, String)] =
    for
      (structName, fields) <- resolveStructFields(structTypeSpec, state.resolvables)
      fieldLayout <- computeStructFieldLayout(structName, fields, state.resolvables)
      _ <- Either.cond(
        fieldIndex < fieldLayout.size,
        (),
        CodeGenError(
          s"Field index $fieldIndex out of bounds for struct '$structName' (has ${fieldLayout.size} fields)"
        )
      )
    yield
      val (s2, tag) = state.getTbaaFieldAccessTag(structName, fieldLayout, fieldIndex)
      (s2, tag)

  /** Resolve a TypeSpec to its underlying struct fields and type name. */
  private def resolveStructFields(
    typeSpec:    Type,
    resolvables: ResolvablesIndex
  ): Either[CodeGenError, (String, List[(String, Type)])] =
    typeSpec match
      case TypeRef(_, name, resolvedId, _) =>
        resolvedId.flatMap(resolvables.lookupType) match
          case Some(td: TypeDef) =>
            td.typeSpec match
              case Some(ns: NativeStruct) => Right((name, ns.fields))
              case other =>
                Left(
                  CodeGenError(
                    s"Type '$name' is not a struct (got ${other.map(_.getClass.getSimpleName)})"
                  )
                )
          case Some(ts: TypeStruct) =>
            Right((name, ts.fields.toList.map(f => f.name -> f.typeSpec)))
          case Some(ta: TypeAlias) =>
            ta.typeSpec match
              case Some(spec) => resolveStructFields(spec, resolvables)
              case None => resolveStructFields(ta.typeRef, resolvables)
          case None =>
            Left(CodeGenError(s"Unresolved type reference '$name'"))
      case ts: TypeStruct =>
        Right((ts.name, ts.fields.toList.map(f => f.name -> f.typeSpec)))
      case other =>
        Left(CodeGenError(s"Expected struct type, got ${other.getClass.getSimpleName}"))

  /** Ensure a TBAA struct node exists for a TypeDef with NativeStruct. This is called during module
    * initialization to register all struct types, ensuring types like IntArray and StringArray have
    * distinct TBAA identities even though they share the same layout as String.
    *
    * @param typeDef
    *   The TypeDef to register (must have NativeStruct typeSpec)
    * @param state
    *   The current code generation state
    * @return
    *   Either an error or updated state with TBAA struct node registered
    */
  def ensureTbaaStructForTypeDef(
    typeDef: TypeDef,
    state:   CodeGenState
  ): Either[CodeGenError, CodeGenState] =
    typeDef.typeSpec match
      case Some(ns: NativeStruct) =>
        computeStructFieldLayout(typeDef.name, ns.fields, state.resolvables).map { layout =>
          val (stateWithTbaa, _) = state.getTbaaStruct(typeDef.name, layout)
          stateWithTbaa
        }
      case _ => Right(state)

  def ensureTbaaStructForTypeStruct(
    typeStruct: TypeStruct,
    state:      CodeGenState
  ): Either[CodeGenError, CodeGenState] =
    computeStructFieldLayout(
      typeStruct.name,
      typeStruct.fields.toList.map(f => f.name -> f.typeSpec),
      state.resolvables
    ).map { layout =>
      val (stateWithTbaa, _) = state.getTbaaStruct(typeStruct.name, layout)
      stateWithTbaa
    }

  /** Compute struct field layout: list of (mmlTypeName, byteOffset) pairs. Uses MML type names to
    * preserve type distinctions in TBAA metadata.
    */
  private def computeStructFieldLayout(
    structName:  String,
    fields:      List[(String, Type)],
    resolvables: ResolvablesIndex
  ): Either[CodeGenError, List[(String, Int)]] =
    fields
      .foldLeft(
        Right((List.empty[(String, Int)], 0)): Either[CodeGenError, (List[(String, Int)], Int)]
      ) {
        case (Right((acc, currentOffset)), (fieldName, fieldTypeSpec)) =>
          for
            typeName <- TypeNameResolver
              .getMmlTypeName(fieldTypeSpec, resolvables)
              .left
              .map(e => CodeGenError(s"In struct '$structName', field '$fieldName': ${e.message}"))
            fieldAlignment <- StructLayout
              .alignOf(fieldTypeSpec, resolvables)
              .left
              .map(e => CodeGenError(s"In struct '$structName', field '$fieldName': ${e.message}"))
            fieldSize <- StructLayout
              .sizeOf(fieldTypeSpec, resolvables)
              .left
              .map(e => CodeGenError(s"In struct '$structName', field '$fieldName': ${e.message}"))
          yield
            val alignedOffset = mml.mmlclib.codegen.emitter.alignTo(currentOffset, fieldAlignment)
            (acc :+ (typeName, alignedOffset), alignedOffset + fieldSize)
        case (left @ Left(_), _) => left
      }
      .map(_._1)

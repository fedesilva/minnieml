package mml.mmlclib.ast

/** Utilities for querying type properties from AST nodes. */
object TypeUtils:

  /** Get the type name from a Type */
  def getTypeName(t: Type): Option[String] = t match
    case TypeRef(_, name, _, _) => Some(name)
    case TypeStruct(_, _, _, name, _, _) => Some(name)
    case _ => None

  /** Find a type definition by name */
  private def findTypeByName(
    typeName:    String,
    resolvables: ResolvablesIndex
  ): Option[ResolvableType] =
    resolvables
      .lookupType(s"stdlib::typedef::$typeName")
      .orElse(resolvables.lookupType(s"stdlib::typealias::$typeName"))
      .orElse:
        resolvables.resolvableTypes.values.find:
          case td: TypeDef => td.name == typeName
          case ta: TypeAlias => ta.name == typeName
          case ts: TypeStruct => ts.name == typeName

  /** Check if a type is heap-allocated by looking at its NativeType.memEffect */
  def isHeapType(typeName: String, resolvables: ResolvablesIndex): Boolean =
    findTypeByName(typeName, resolvables) match
      case Some(TypeDef(_, _, _, Some(ns: NativeStruct), _, _, _)) =>
        ns.memEffect.contains(MemEffect.Alloc)
      case Some(TypeDef(_, _, _, Some(np: NativePointer), _, _, _)) =>
        np.memEffect.contains(MemEffect.Alloc)
      case Some(TypeDef(_, _, _, Some(prim: NativePrimitive), _, _, _)) =>
        prim.memEffect.contains(MemEffect.Alloc)
      case Some(s: TypeStruct) =>
        hasHeapFields(s, resolvables)
      case _ => false

  /** Check if a user struct has any heap-typed fields */
  def hasHeapFields(struct: TypeStruct, resolvables: ResolvablesIndex): Boolean =
    struct.fields.exists { field =>
      getTypeName(field.typeSpec).exists(isHeapType(_, resolvables))
    }

  /** Get free function name for a type, or None if not heap type */
  def freeFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    if isHeapType(typeName, resolvables) then Some(s"__free_$typeName")
    else None

  /** Get clone function name for a type, or None if not heap type */
  def cloneFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    if isHeapType(typeName, resolvables) then Some(s"__clone_$typeName")
    else None

  /** Check if a type resolves to a NativePointer (actual LLVM pointer, not a struct). */
  def isPointerType(typeName: String, resolvables: ResolvablesIndex): Boolean =
    findTypeByName(typeName, resolvables) match
      case Some(TypeDef(_, _, _, Some(_: NativePointer), _, _, _)) => true
      case _ => false

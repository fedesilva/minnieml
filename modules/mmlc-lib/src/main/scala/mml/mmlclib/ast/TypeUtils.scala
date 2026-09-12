package mml.mmlclib.ast

/** Utilities for querying type properties from AST nodes. */
object TypeUtils:

  /** Follow resolved aliases and single-type groups without changing nominal declarations. */
  def canonical(tpe: Type, index: ResolvablesIndex): Option[Type] =

    @scala.annotation.tailrec
    def loop(current: Type, seen: Set[String]): Option[Type] = current match

      case TypeGroup(_, List(inner)) => loop(inner, seen)

      case ref: TypeRef =>
        ref.resolvedId.flatMap(index.lookupType) match

          case Some(alias: TypeAlias) if !ref.resolvedId.exists(seen.contains) =>
            loop(alias.typeSpec.getOrElse(alias.typeRef), seen ++ ref.resolvedId)

          case Some(_: TypeAlias) => None

          case _ => Some(ref)
      case other => Some(other)

    loop(tpe, Set.empty)

  def requiresDestruction(tpe: Type, index: ResolvablesIndex): Boolean =
    requiresDestruction(tpe, index, Set.empty)

  private def requiresDestruction(
    tpe:   Type,
    index: ResolvablesIndex,
    seen:  Set[String]
  ): Boolean =
    canonical(tpe, index).exists {
      case _: TypeFn => true
      case other => getTypeName(other).exists(isHeapType(_, index, seen))
    }
  def isPointerNativeType(nativeType: NativeType): Boolean = nativeType match
    case _: NativePointer => true
    case NativePrimitive(_, "ptr", _, _) => true
    case _ => false

  /** Get the type name from a Type */
  def getTypeName(t: Type): Option[String] = t match
    case TypeRef(_, name, _, _) => Some(name)
    case ts: TypeStruct => Some(ts.name)
    case _ => None

  /** Find a type definition by name */
  private def findTypeByName(
    typeName:    String,
    resolvables: ResolvablesIndex
  ): Option[ResolvableType] =
    resolvables
      // FIXME:QA: Preserve resolved type identity instead of looking up types by name.
      // See QA-001 in context/tasks/qa-misses.md.
      .lookupType(s"stdlib::typedef::$typeName")
      .orElse(resolvables.lookupType(s"stdlib::typealias::$typeName"))
      .orElse:
        resolvables.resolvableTypes.values.find:
          case td: TypeDef => td.name == typeName
          case ta: TypeAlias => ta.name == typeName
          case ts: TypeStruct => ts.name == typeName

  def resolveNativeType(
    typeSpec:    Type,
    resolvables: ResolvablesIndex
  ): Option[NativeType] =
    typeSpec match
      case nt: NativeType => Some(nt)
      case TypeGroup(_, types) if types.size == 1 =>
        resolveNativeType(types.head, resolvables)
      case TypeRef(_, name, resolvedId, _) =>
        resolvedId
          .flatMap(resolvables.lookupType)
          .orElse(findTypeByName(name, resolvables))
          .flatMap(resolveNativeType(_, resolvables))
      case _ => None

  private def resolveNativeType(
    resolvableType: ResolvableType,
    resolvables:    ResolvablesIndex
  ): Option[NativeType] =
    resolvableType match
      case TypeDef(_, _, _, Some(nt: NativeType), _, _, _) => Some(nt)
      case ta: TypeAlias =>
        ta.typeSpec
          .flatMap(resolveNativeType(_, resolvables))
          .orElse(resolveNativeType(ta.typeRef, resolvables))
      case _ => None

  def isPointerLike(
    typeSpec:    Type,
    resolvables: ResolvablesIndex
  ): Boolean =
    resolveNativeType(typeSpec, resolvables).exists(isPointerNativeType)

  /** Check if a type is heap-allocated by looking at its NativeType.memEffect */
  def isHeapType(typeName: String, resolvables: ResolvablesIndex): Boolean =
    isHeapType(typeName, resolvables, Set.empty)

  private def isHeapType(
    typeName:    String,
    resolvables: ResolvablesIndex,
    seen:        Set[String]
  ): Boolean =
    if seen.contains(typeName) then false
    else
      findTypeByName(typeName, resolvables) match
        case Some(TypeDef(_, _, _, Some(nt: NativeType), _, _, _)) =>
          nt.memEffect.contains(MemEffect.Alloc)
        case Some(s: TypeStruct) =>
          s.fields.exists(field =>
            requiresDestruction(field.typeSpec, resolvables, seen + typeName)
          )
        case Some(alias: TypeAlias) =>
          canonical(alias.typeRef, resolvables)
            .flatMap(getTypeName)
            .filterNot(_ == typeName)
            .exists(isHeapType(_, resolvables, seen + typeName))
        case _ => false

  /** Structs own heap values and function environments, including through nested fields. */
  def hasHeapFields(struct: TypeStruct, resolvables: ResolvablesIndex): Boolean =
    struct.fields.exists { field =>
      requiresDestruction(field.typeSpec, resolvables, Set(struct.name))
    }

  /** Check if a type resolves to a user-defined TypeStruct with heap fields. Unlike isHeapType,
    * this excludes native TypeDef types (String, IntArray, etc.).
    */
  def isStructWithHeapFields(typeName: String, resolvables: ResolvablesIndex): Boolean =
    findTypeByName(typeName, resolvables) match
      case Some(s: TypeStruct) => hasHeapFields(s, resolvables)
      case _ => false

  /** Get free function name for a type, or None if not heap type */
  def freeFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    findTypeByName(typeName, resolvables) match
      case Some(TypeDef(_, _, _, Some(nt: NativeType), _, _, _))
          if nt.memEffect.contains(MemEffect.Alloc) =>
        Some(nt.freeFn.getOrElse(s"__free_$typeName"))
      case Some(s: TypeStruct) if hasHeapFields(s, resolvables) =>
        Some(s"__free_$typeName")
      case Some(alias: TypeAlias) =>
        canonical(alias.typeRef, resolvables)
          .flatMap(getTypeName)
          .filterNot(_ == typeName)
          .flatMap(freeFnFor(_, resolvables))
      case _ => None

  /** Function environments cannot be duplicated, including through aggregate fields. */
  def containsFunction(tpe: Type, index: ResolvablesIndex): Boolean =
    def loop(current: Type, seen: Set[String]): Boolean =
      canonical(current, index).exists {
        case _:      TypeFn => true
        case struct: TypeStruct => struct.fields.exists(field => loop(field.typeSpec, seen))
        case ref:    TypeRef if !ref.resolvedId.exists(seen.contains) =>
          ref.resolvedId.flatMap(index.lookupType).exists {
            case struct: TypeStruct =>
              struct.fields.exists(field => loop(field.typeSpec, seen ++ ref.resolvedId))
            case _ => false
          }
        case _ => false
      }
    loop(tpe, Set.empty)

  /** Get clone function name for a type, or None if its ownership cannot be duplicated. */
  def cloneFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    if isHeapType(typeName, resolvables) &&
      !findTypeByName(typeName, resolvables).exists {
        case struct: TypeStruct => containsFunction(struct, resolvables)
        case alias:  TypeAlias => containsFunction(alias.typeRef, resolvables)
        case _ => false
      }
    then Some(s"__clone_$typeName")
    else None

  /** Check if a named type resolves to an LLVM pointer-like native type. */
  def isPointerType(typeName: String, resolvables: ResolvablesIndex): Boolean =
    findTypeByName(typeName, resolvables) match
      case Some(rt) => resolveNativeType(rt, resolvables).exists(isPointerNativeType)
      case None => false

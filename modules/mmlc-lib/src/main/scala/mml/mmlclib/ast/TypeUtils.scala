package mml.mmlclib.ast

import cats.syntax.option.*

/** Utilities for querying type properties from AST nodes. */
object TypeUtils:

  /** Follow resolved aliases, type groups, and schemes without changing nominal declarations. */
  def canonical(tpe: Type, index: ResolvablesIndex): Option[Type] =

    @scala.annotation.tailrec
    def loop(current: Type, seen: Set[String]): Option[Type] = current match

      case TypeGroup(_, List(inner)) => loop(inner, seen)

      case TypeScheme(_, _, body) => loop(body, seen)

      case ref: TypeRef =>
        ref.resolvedId.flatMap(index.lookupType) match

          case Some(alias: TypeAlias) if !ref.resolvedId.exists(seen.contains) =>
            loop(alias.typeSpec.getOrElse(alias.typeRef), seen ++ ref.resolvedId)

          case Some(_: TypeAlias) => None

          case _ => Some(ref)
      case other => Some(other)

    loop(tpe, Set.empty)

  /** Find explicit failed types through their structure and resolved declarations. A recursive
    * reference alone is not a failed type; callers still decide whether its type is usable.
    */
  def containsInvalidType(tpe: Type, index: ResolvablesIndex): Boolean =

    def check(current: Type, seen: Set[String]): Boolean =
      containsInvalidTypeWithin(
        current,
        ref =>
          ref.resolvedId.filterNot(seen.contains).exists { id =>
            index.lookupType(id).exists { declaration =>
              declarationTypes(declaration).exists(check(_, seen + id))
            }
          }
      )

    check(tpe, Set.empty)

  /** Named declarations with an explicit failed type, including through recursive references. */
  def invalidTypeIds(index: ResolvablesIndex): Set[String] =
    index.resolvableTypes.iterator.collect {
      case (id, declaration)
          if declarationTypes(declaration).exists(containsInvalidType(_, index)) =>
        id
    }.toSet

  /** Inspect inline type structure with failure identities from the same declaration index. */
  def containsInvalidType(tpe: Type, invalidTypeIds: Set[String]): Boolean =
    containsInvalidTypeWithin(tpe, _.resolvedId.exists(invalidTypeIds.contains))

  private def declarationTypes(declaration: ResolvableType): List[Type] = declaration match
    case alias:      TypeAlias => List(alias.typeRef)
    case definition: TypeDef => definition.typeAsc.toList ++ definition.typeSpec.toList
    case struct:     TypeStruct => struct.fields.map(_.typeSpec).toList

  private def containsInvalidTypeWithin(tpe: Type, reference: TypeRef => Boolean): Boolean =

    def annotations(value: Typeable): Boolean =
      (value.typeAsc.toList ++ value.typeSpec.toList).exists(check)

    def check(current: Type): Boolean = current match

      case _: ParseTypeError | _: TypeResolutionError => true

      case ref: TypeRef => reference(ref)

      case function: TypeFn =>
        function.paramTypes.exists(check) || check(function.returnType)

      case application: TypeApplication =>
        check(application.base) || application.args.exists(check)

      case tuple:        TypeTuple => tuple.elements.exists(check)
      case group:        TypeGroup => group.types.exists(check)
      case union:        Union => union.types.exists(check)
      case intersection: Intersection => intersection.types.exists(check)
      case scheme:       TypeScheme => check(scheme.bodyType)
      case record:       TypeOpenRecord => record.fields.exists(field => check(field._2))
      case native:       NativeStruct => native.fields.exists(field => check(field._2))
      case struct:       TypeStruct => struct.fields.exists(field => check(field.typeSpec))

      case refinement: TypeRefinement =>
        TermTraversal
          .collect(refinement.expr) {
            case lambda: Lambda =>
              annotations(lambda) || lambda.params.exists(annotations)
            case binding: BindOwnership =>
              List(binding, binding.valueParam, binding.witnessParam).exists(annotations)
            case term => annotations(term)
          }
          .exists(identity)

      case _: NativePrimitive | _: NativePointer | _: TypeUnit | _: TypeVariable => false

    check(tpe)

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

  private def underlyingTypeByName(
    typeName: String,
    index:    ResolvablesIndex
  ): Option[ResolvableType] =
    findTypeByName(typeName, index).flatMap {

      case alias: TypeAlias =>
        canonical(alias.typeRef, index).flatMap {
          case ref:    TypeRef => ref.resolvedId.flatMap(index.lookupType)
          case struct: TypeStruct => struct.some
          case _ => none
        }

      case declaration => declaration.some
    }

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

  private def heapNativeType(definition: TypeDef): Option[NativeType] =
    definition.typeSpec.collect {
      case native: NativeType if native.memEffect.contains(MemEffect.Alloc) => native
    }

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
        case Some(definition: TypeDef) => heapNativeType(definition).isDefined
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
    underlyingTypeByName(typeName, resolvables) match
      case Some(s: TypeStruct) => hasHeapFields(s, resolvables)
      case _ => false

  /** Get free function name for a type, or None if not heap type */
  def freeFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    findTypeByName(typeName, resolvables) match
      case Some(definition: TypeDef) =>
        heapNativeType(definition).map(_.freeFn.getOrElse(s"__free_$typeName"))
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
    underlyingTypeByName(typeName, resolvables).flatMap {
      case definition: TypeDef =>
        heapNativeType(definition).map(_ => s"__clone_${definition.name}")

      case struct: TypeStruct
          if hasHeapFields(struct, resolvables) && !containsFunction(struct, resolvables) =>
        s"__clone_${struct.name}".some

      case _ => none
    }

  /** Check if a named type resolves to an LLVM pointer-like native type. */
  def isPointerType(typeName: String, resolvables: ResolvablesIndex): Boolean =
    findTypeByName(typeName, resolvables) match
      case Some(rt) => resolveNativeType(rt, resolvables).exists(isPointerNativeType)
      case None => false

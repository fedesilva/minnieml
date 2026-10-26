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

  /** Resolve nominal declarations without guessing from an unresolved reference's name. */
  def declaration(tpe: Type, index: ResolvablesIndex): Option[ResolvableType] =
    canonical(tpe, index).flatMap {
      case ref:    TypeRef => ref.resolvedId.flatMap(index.lookupType)
      case struct: TypeStruct => struct.some
      case _ => none
    }

  def requiresDestruction(tpe: Type, index: ResolvablesIndex): Boolean =
    requiresDestruction(tpe, index, Set.empty)

  private def requiresDestruction(tpe: Type, index: ResolvablesIndex, seen: Set[String]): Boolean =
    canonical(tpe, index).exists {
      case _: TypeFn => true
      case other => isHeapType(other, index, seen)
    }

  def isPointerNativeType(nativeType: NativeType): Boolean = nativeType match
    case _: NativePointer => true
    case NativePrimitive(_, "ptr", _, _) => true
    case _ => false

  /** The display name of a nominal type; not a declaration lookup key. */
  def getTypeName(t: Type): Option[String] = t match
    case ref:    TypeRef => ref.name.some
    case struct: TypeStruct => struct.name.some
    case _ => none

  def resolveNativeType(tpe: Type, index: ResolvablesIndex): Option[NativeType] =
    canonical(tpe, index).flatMap {
      case native: NativeType => native.some
      case other =>
        declaration(other, index).flatMap {
          case definition: TypeDef =>
            definition.typeSpec.collect { case native: NativeType => native }
          case _ => none
        }
    }

  def isPointerLike(tpe: Type, index: ResolvablesIndex): Boolean =
    resolveNativeType(tpe, index).exists(isPointerNativeType)

  private def heapNativeType(definition: TypeDef): Option[NativeType] =
    definition.typeSpec.collect {
      case native: NativeType if native.memEffect.contains(MemEffect.Alloc) => native
    }

  /** Nominal heap storage, including structs that own values or function environments. */
  def isHeapType(tpe: Type, index: ResolvablesIndex): Boolean =
    isHeapType(tpe, index, Set.empty)

  private def isHeapType(tpe: Type, index: ResolvablesIndex, seen: Set[String]): Boolean =
    declaration(tpe, index).exists {
      case definition: TypeDef => heapNativeType(definition).isDefined
      case struct:     TypeStruct if !struct.id.exists(seen.contains) =>
        struct.fields.exists(field => requiresDestruction(field.typeSpec, index, seen ++ struct.id))
      case _ => false
    }

  /** Structs own heap values and function environments, including through nested fields. */
  def hasHeapFields(struct: TypeStruct, index: ResolvablesIndex): Boolean =
    isHeapType(struct, index)

  def isStructWithHeapFields(tpe: Type, index: ResolvablesIndex): Boolean =
    declaration(tpe, index).exists {
      case struct: TypeStruct => hasHeapFields(struct, index)
      case _ => false
    }

  /** Memory-function names come from the resolved declaration, including explicit destructors. */
  def freeFnFor(tpe: Type, index: ResolvablesIndex): Option[String] =
    declaration(tpe, index).flatMap {
      case definition: TypeDef =>
        heapNativeType(definition).map(_.freeFn.getOrElse(s"__free_${definition.name}"))
      case struct: TypeStruct if hasHeapFields(struct, index) => s"__free_${struct.name}".some
      case _ => none
    }

  /** Function environments cannot be duplicated, including through aggregate fields. */
  def containsFunction(tpe: Type, index: ResolvablesIndex): Boolean =
    def loop(current: Type, seen: Set[String]): Boolean =
      canonical(current, index).exists {
        case _: TypeFn => true
        case other =>
          declaration(other, index).exists {
            case struct: TypeStruct if !struct.id.exists(seen.contains) =>
              struct.fields.exists(field => loop(field.typeSpec, seen ++ struct.id))
            case _ => false
          }
      }
    loop(tpe, Set.empty)

  /** Get clone function name for a type, or None if its ownership cannot be duplicated. */
  def cloneFnFor(tpe: Type, index: ResolvablesIndex): Option[String] =
    declaration(tpe, index).flatMap {
      case definition: TypeDef =>
        heapNativeType(definition).map(_ => s"__clone_${definition.name}")
      case struct: TypeStruct if hasHeapFields(struct, index) && !containsFunction(struct, index) =>
        s"__clone_${struct.name}".some
      case _ => none
    }

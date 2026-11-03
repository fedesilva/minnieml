package mml.mmlclib.ast.queries

import mml.mmlclib.ast.*

/** Explicit parse and resolution failures within types and their resolved declarations. */
object TypeFailureQueries:

  /** Finds explicit failed types through structure, original alias bodies, and nominal annotations.
    * Missing identities and recursive references alone are not failed types.
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

  /** IDs of named declarations with an explicit failed type, including through references. */
  def invalidTypeIds(index: ResolvablesIndex): Set[String] =
    index.resolvableTypes.iterator.collect {
      case (id, declaration)
          if declarationTypes(declaration).exists(containsInvalidType(_, index)) =>
        id
    }.toSet

  /** Inspects inline types using failure IDs summarized from the same declaration index. */
  def containsInvalidType(tpe: Type, invalidTypeIds: Set[String]): Boolean =
    containsInvalidTypeWithin(tpe, _.resolvedId.exists(invalidTypeIds.contains))

  /** Returns the written alias target, both nominal annotations, or all struct field types. */
  private def declarationTypes(declaration: ResolvableType): List[Type] = declaration match
    case alias:      TypeAlias => List(alias.typeRef)
    case definition: TypeDef => definition.typeAsc.toList ++ definition.typeSpec.toList
    case struct:     TypeStruct => struct.fields.map(_.typeSpec).toList

  /** Finds inline type failures using the supplied predicate for references. Refinement expressions
    * lie outside type traversal, so their terms' declared and computed types are included here.
    * Lambda and BindOwnership parameter types are included because term traversal omits parameters.
    */
  private def containsInvalidTypeWithin(tpe: Type, reference: TypeRef => Boolean): Boolean =

    def annotations(value: Typeable): Boolean =
      (value.typeAsc.toList ++ value.typeSpec.toList).exists(check)

    def check(current: Type): Boolean = current match

      case _: ParseTypeError | _: TypeResolutionError => true

      case ref: TypeRef => reference(ref)

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

      case other => TypeTraversal.children(other).exists(check)

    check(tpe)

package mml.mmlclib.ast.queries

import cats.syntax.option.*
import mml.mmlclib.ast.*

/** Alias expansion, nominal declarations, and type display names. */
object TypeQueries:

  /** Follows resolved aliases, single-type groups, and schemes, preserving nominal types. Aliases
    * prefer their computed type. Alias cycles yield None; absent or unindexed identities retain the
    * reference.
    */
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

  /** Returns a direct struct or the nominal declaration of a canonical type's resolved identity. */
  def declaration(tpe: Type, index: ResolvablesIndex): Option[ResolvableType] =
    canonical(tpe, index).flatMap {
      case ref:    TypeRef => ref.resolvedId.flatMap(index.lookupType)
      case struct: TypeStruct => struct.some
      case _ => none
    }

  /** The display name carried by a type reference or struct, without expanding aliases. */
  def getTypeName(t: Type): Option[String] = t match
    case ref:    TypeRef => ref.name.some
    case struct: TypeStruct => struct.name.some
    case _ => none

package mml.mmlclib.ast

import cats.Id

/** Substitutes free, resolved type variables without changing names, declaration identities, or
  * source evidence on surrounding types. Replacement types retain their own source evidence.
  */
object TypeSubstitution:

  /** Scheme binders protect their occurrences. Replacements are simultaneous: variables inside a
    * replacement are not substituted again. Unresolved variables never match by spelling.
    */
  def apply(tpe: Type, replacements: Map[String, Type]): Type = tpe match

    case variable: TypeVariable =>
      variable.resolvedId.flatMap(replacements.get).getOrElse(variable)

    case scheme: TypeScheme =>
      val free = replacements -- scheme.vars.flatMap(_.resolvedId)
      scheme.copy(bodyType = apply(scheme.bodyType, free))

    case other => TypeTraversal.traverseChildren[Id](other)(apply(_, replacements))

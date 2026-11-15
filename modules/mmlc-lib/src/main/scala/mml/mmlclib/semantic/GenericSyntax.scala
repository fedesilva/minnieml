package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Detects generic expressions independently of whether their type checking succeeded. Ordinary
  * protocol parameter declarations do not require function instantiation.
  */
object GenericSyntax:

  def exists(module: Module): Boolean = sites(module).nonEmpty

  private def sites(member: Member): List[Expr] = member match

    case module: Module => module.members.flatMap(sites)

    case binding: Bnd =>
      val expressions = termSites(binding.value)
      if expressions.nonEmpty then expressions.take(1)
      else if binding.typeAsc.exists(hasVariables) then List(binding.value)
      else Nil

    case instance:  ProtocolInstance => instance.members.flatMap(m => sites(m.binding))
    case invalid:   InvalidMember => sites(invalid.originalMember)
    case duplicate: DuplicateMember => sites(duplicate.originalMember)
    case _ => Nil

  private def termSites(term: Term): List[Expr] =
    val generic = term match
      case _:      FnTypeApp => true
      case lambda: Lambda =>
        lambda.typeParams.nonEmpty || lambda.constraints.nonEmpty ||
        lambda.params.exists(_.typeAsc.exists(hasVariables)) ||
        lambda.typeAsc.exists(hasVariables)
      case _ => term.typeAsc.exists(hasVariables)

    if generic then List(Expr(term.source, List(term)))
    else TermTraversal.children(term).flatMap(termSites)

  private def hasVariables(tpe: Type): Boolean =
    TypeTraversal.exists(tpe)(_.isInstanceOf[TypeVariable])

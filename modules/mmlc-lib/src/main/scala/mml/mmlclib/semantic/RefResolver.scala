package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object RefResolver:

  private val phaseName = "mml.mmlclib.semantic.RefResolver"

  /** Resolve all references in a module, accumulating errors in the state. */
  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState =
    val (errors, members) =
      state.module.members.foldLeft((List.empty[SemanticError], List.empty[Member])) {
        case ((accErrors, accMembers), member) =>
          resolveMember(member, state.module) match
            case Left(errs) =>
              // Important: Use the rewritten member with InvalidExpression nodes, not the original
              val rewrittenMember = rewriteMemberWithInvalidExpressions(member, state.module)
              (accErrors ++ errs, accMembers :+ rewrittenMember)
            case Right(updated) => (accErrors, accMembers :+ updated)
      }
    state.addErrors(errors).withModule(state.module.copy(members = members))

  /** Rewrite a member to use InvalidExpression nodes for undefined references */
  private def rewriteMemberWithInvalidExpressions(member: Member, module: Module): Member =
    member match
      case bnd: Bnd =>
        bnd.copy(value = rewriteExprWithInvalidExpressions(bnd.value, bnd, module))
      case fnDef: FnDef =>
        fnDef.copy(body = rewriteExprWithInvalidExpressions(fnDef.body, fnDef, module))
      case bin: BinOpDef =>
        bin.copy(body = rewriteExprWithInvalidExpressions(bin.body, bin, module))
      case unary: UnaryOpDef =>
        unary.copy(body = rewriteExprWithInvalidExpressions(unary.body, unary, module))
      case _ => member

  /** Rewrite expression to use InvalidExpression for undefined references */
  private def rewriteExprWithInvalidExpressions(expr: Expr, member: Member, module: Module): Expr =
    val rewrittenTerms = expr.terms.map {
      case ref: Ref =>
        val candidates = lookupRefs(ref, member, module)
        if candidates.isEmpty then
          // Create InvalidExpression wrapping the undefined ref
          InvalidExpression(
            span         = ref.span,
            originalExpr = Expr(ref.span, List(ref)),
            typeSpec     = ref.typeSpec,
            typeAsc      = ref.typeAsc
          )
        else if candidates.length == 1 then
          ref.copy(candidates    = candidates, resolvedAs = Some(candidates.head))
        else ref.copy(candidates = candidates)

      case group: TermGroup =>
        group.copy(inner = rewriteExprWithInvalidExpressions(group.inner, member, module))

      case e: Expr =>
        rewriteExprWithInvalidExpressions(e, member, module)

      case t: Tuple =>
        t.copy(elements = t.elements.map(e => rewriteExprWithInvalidExpressions(e, member, module)))

      case cond: Cond =>
        cond.copy(
          cond    = rewriteExprWithInvalidExpressions(cond.cond, member, module),
          ifTrue  = rewriteExprWithInvalidExpressions(cond.ifTrue, member, module),
          ifFalse = rewriteExprWithInvalidExpressions(cond.ifFalse, member, module)
        )

      case term => term
    }
    expr.copy(terms = rewrittenTerms)

  /** Resolve references in a module member. */
  private def resolveMember(member: Member, module: Module): Either[List[SemanticError], Member] =
    member match
      case bnd: Bnd =>
        resolveExpr(bnd.value, bnd, module).map(updatedExpr => bnd.copy(value = updatedExpr))
      case fnDef: FnDef =>
        resolveExpr(fnDef.body, fnDef, module).map(updatedExpr => fnDef.copy(body = updatedExpr))
      case opDef: OpDef =>
        resolveExpr(opDef.body, opDef, module).map { updatedExpr =>
          opDef match
            case bin:   BinOpDef => bin.copy(body = updatedExpr)
            case unary: UnaryOpDef => unary.copy(body = updatedExpr)
        }
      case _ =>
        member.asRight[List[SemanticError]]

  /** Returns all members (bindings, functions, operators) whose name matches the reference.
    */
  private def lookupRefs(ref: Ref, member: Member, module: Module): List[Resolvable] =

    def collectMembers =
      module.members
        .filter(_ != member)
        .collect {
          case bnd:   Bnd if bnd.name == ref.name => bnd
          case fnDef: FnDef if fnDef.name == ref.name => fnDef
          case opDef: OpDef if opDef.name == ref.name => opDef
        }

    val params = member match
      case fnDef: FnDef =>
        fnDef.params.filter(_.name == ref.name)
      case opDef: OpDef =>
        opDef match
          case bin:   BinOpDef => List(bin.param1, bin.param2).filter(_.name == ref.name)
          case unary: UnaryOpDef => List(unary.param).filter(_.name == ref.name)
      case _ => Nil

    if params.nonEmpty then params else collectMembers

  /** Resolve references in an expression.
    *
    * Returns either a list of errors or a new expression with resolved references.
    */
  private def resolveExpr(
    expr:   Expr,
    member: Member,
    module: Module
  ): Either[List[SemanticError], Expr] =

    expr.terms.traverse {

      case ref: Ref =>
        val candidates = lookupRefs(ref, member, module)
        if candidates.isEmpty then List(SemanticError.UndefinedRef(ref, member, phaseName)).asLeft
        else if candidates.length == 1 then
          ref.copy(candidates = candidates, resolvedAs = Some(candidates.head)).asRight
        else ref.copy(candidates = candidates).asRight

      case group: TermGroup =>
        resolveExpr(group.inner, member, module)
          .map(updatedExpr => group.copy(inner = updatedExpr))

      case e: Expr =>
        resolveExpr(e, member, module)
          .map(updatedExpr => e.copy(terms = updatedExpr.terms))

      case t: Tuple =>
        t.elements
          .traverse(e => resolveExpr(e, member, module))
          .map(newElems => t.copy(elements = newElems))

      case cond: Cond =>
        for
          newCond <- resolveExpr(cond.cond, member, module)
          newIfTrue <- resolveExpr(cond.ifTrue, member, module)
          newIfFalse <- resolveExpr(cond.ifFalse, member, module)
        yield cond.copy(cond = newCond, ifTrue = newIfTrue, ifFalse = newIfFalse)

      case term =>
        term.asRight[List[SemanticError]]

    } map { updatedTerms =>
      expr.copy(terms = updatedTerms)
    }

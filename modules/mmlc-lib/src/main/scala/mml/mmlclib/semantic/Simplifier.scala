package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

object Simplifier:

  /** Simplify executable bindings and keep their indexed definitions current. */
  def rewriteModule(state: CompilerState): CompilerState =
    val (updatedMembers, updatedResolvables) = state.module.members.foldLeft(
      (List.empty[Member], state.module.resolvables)
    ) { case ((accMembers, resolvables), member) =>
      simplifyMember(member) match
        case updatedBnd: Bnd =>
          (accMembers :+ updatedBnd, resolvables.updated(updatedBnd))
        case other =>
          (accMembers :+ other, resolvables)
    }
    state.withModule(state.module.copy(members = updatedMembers, resolvables = updatedResolvables))

  def simplifyMember(member: Member): Member =
    member match
      case b: Bnd => b.copy(value = simplifyExpr(b.value))
      case other => other

  /** Simplifies an expression that must remain an Expr (e.g., member body, cond branch). */
  def simplifyTopLevelExpr(expr: Expr): Expr =
    val simplifiedTerm = simplifyTerm(expr)
    ensureExpr(simplifiedTerm)

  /** Simplify the terms within an expression. Does not unwrap the expression itself. */
  def simplifyExpr(expr: Expr): Expr =
    val simplifiedTerms = expr.terms.map(simplifyTerm)
    expr.copy(terms = simplifiedTerms)

  /** Remove grouping and unannotated singleton wrappers while preserving value annotations. */
  def simplifyTerm(term: Term): Term =

    term match

      case expr: Expr =>
        val simplifiedExpr = simplifyExpr(expr)
        simplifiedExpr.terms match
          case single :: Nil if simplifiedExpr.typeAsc.isEmpty => single
          case _ => simplifiedExpr

      case group: TermGroup =>
        val finalTerm = simplifyTerm(group.inner)
        // A group annotation constrains the whole value. Keeping it on an Expr also preserves
        // a nested lambda's own return annotation and any annotation already on the inner term.
        group.typeAsc.fold(finalTerm) { annotation =>
          Expr(group.source, List(finalTerm), typeAsc = annotation.some)
        }

      case callee: Callee => simplifyCallee(callee)

      case c: Cond =>
        c.copy(
          cond    = simplifyTopLevelExpr(c.cond),
          ifTrue  = simplifyTopLevelExpr(c.ifTrue),
          ifFalse = simplifyTopLevelExpr(c.ifFalse)
        )
      case t: Tuple =>
        t.copy(elements = t.elements.map(simplifyTopLevelExpr))

      case other => other

  private def ensureExpr(term: Term): Expr = term match
    case expr: Expr => expr
    case other => Expr(other.source, List(other))

  private def simplifyCallee(callee: Callee): Callee =

    callee match

      case app: App =>
        val simplifiedArg = simplifyExpr(app.arg)
        val simplifiedFn  = simplifyCallee(app.fn)
        app.copy(fn = simplifiedFn, arg = simplifiedArg)

      case ref: Ref =>
        ref.copy(qualifier = ref.qualifier.map(simplifyTerm))

      case lambda: Lambda => lambda

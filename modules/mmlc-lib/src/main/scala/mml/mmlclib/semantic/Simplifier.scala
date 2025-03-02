package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object Simplifier:

  def rewriteModule(module: Module): Either[List[SemanticError], Module] =
    module.copy(members = module.members.map(simplifyMember)).asRight[List[SemanticError]]

  def simplifyMember(member: Member): Member =
    member match
      case b:  Bnd => b.copy(value = simplifyExpr(b.value))
      case fn: FnDef => fn.copy(body = simplifyExpr(fn.body))
      case op: OpDef =>
        op match
          case bin: BinOpDef => bin.copy(body = simplifyExpr(bin.body))
          case un:  UnaryOpDef => un.copy(body = simplifyExpr(un.body))
      case other => other

  /** Simplify an expression by removing any unnecessary Expr wrappers. */
  def simplifyExpr(expr: Expr): Expr =
    simplifyTerm(expr) match
      case e: Expr => e
      case term => Expr(expr.span, List(term)) // fallback; shouldn't normally happen

  /** Recursively simplify a term.
    *   - For an Expr: simplify all subterms and, if exactly one subterm remains, unwrap the
    *     expression.
    *   - For a GroupTerm: simplify its inner expression.
    *   - Other terms are returned unchanged.
    */
  def simplifyTerm(term: Term): Term =
    term match
      case e: Expr =>
        val simplifiedSubterms = e.terms.map(simplifyTerm)
        simplifiedSubterms match
          case single :: Nil =>
            // Unwrap the redundant Expr by returning the simplified single subterm.
            simplifyTerm(single)
          case many =>
            // Otherwise, rebuild the expression with the simplified list.
            Expr(e.span, many)
      case group: GroupTerm =>
        // Remove the GroupTerm wrapper by simplifying its inner expression.
        simplifyTerm(group.inner)
      case other => other

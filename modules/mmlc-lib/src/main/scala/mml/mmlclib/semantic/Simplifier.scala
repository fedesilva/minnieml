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
    // First, simplify all subterms
    val simplifiedTerms = expr.terms.map(simplifyTerm)

    // Then apply additional simplifications if possible
    simplifiedTerms match
      case single :: Nil =>
        // If there's only a single term, potentially unwrap it
        single match
          case e: Expr =>
            // Unwrap nested Expr - this may happen during preceding climbing
            e
          case app: App =>
            // Simplify App argument
            val simplifiedArg = simplifyExpr(app.arg)
            val simplifiedFn  = simplifyTerm(app.fn)
            Expr(
              expr.span,
              List(app.copy(fn = simplifiedFn.asInstanceOf[Ref | App], arg = simplifiedArg))
            )
          case _ =>
            // Single non-Expr term, keep the Expr wrapper but with simplified term
            Expr(expr.span, List(single), expr.typeSpec, expr.typeAsc)
      case many =>
        // Otherwise, rebuild with simplified terms
        Expr(expr.span, many, expr.typeSpec, expr.typeAsc)

  /** Recursively simplify a term.
    *   - For an Expr: simplify all subterms and, if exactly one subterm remains, unwrap the
    *     expression.
    *   - For a GroupTerm: simplify its inner expression.
    *   - For AppN: simplify all arguments
    *   - Other terms are returned unchanged.
    */
  def simplifyTerm(term: Term): Term =
    term match
      case e: Expr =>
        val simplified = simplifyExpr(e)
        // If simplified expression has exactly one term, extract it to reduce nesting
        simplified.terms match
          case single :: Nil => single
          case _ => simplified

      case group: TermGroup =>
        // Simplify inner expression
        val simplifiedInner = simplifyExpr(group.inner)
        // If inner expression has only one term, extract it
        simplifiedInner.terms match
          case single :: Nil =>
            // Return the single term with the type ascription from the group
            single match
              case ref: Ref =>
                ref.copy(typeAsc = group.typeAsc.orElse(ref.typeAsc))
              case lit: LiteralValue =>
                lit // Literals have fixed types, ignore type ascription
              case other =>
                // Keep the GroupTerm but with simplified inner
                group.copy(inner = simplifiedInner)
          case _ =>
            // Inner has multiple terms, keep the GroupTerm
            group.copy(inner = simplifiedInner)

      case app: App =>
        // Simplify the function and argument
        val simplifiedArg = simplifyExpr(app.arg)
        val simplifiedFn  = simplifyTerm(app.fn)
        app.copy(fn = simplifiedFn.asInstanceOf[Ref | App], arg = simplifiedArg)

      case other => other

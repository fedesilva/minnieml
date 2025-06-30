package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object Simplifier:
  def rewriteModule(module: Module): Either[List[SemanticError], Module] =
    module.copy(members = module.members.map(simplifyMember)).asRight[List[SemanticError]]

  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState =
    state.withModule(state.module.copy(members = state.module.members.map(simplifyMember)))

  def simplifyMember(member: Member): Member =
    member match
      case b:  Bnd => b.copy(value = simplifyExpr(b.value))
      case fn: FnDef => fn.copy(body = simplifyExpr(fn.body))
      case op: OpDef =>
        op match
          case bin: BinOpDef => bin.copy(body = simplifyExpr(bin.body))
          case un:  UnaryOpDef => un.copy(body = simplifyExpr(un.body))
      case other => other

  // Helper to ensure a Term is an Expr, wrapping if necessary
  private def ensureExpr(term: Term): Expr = term match {
    case e: Expr => e // Already an Expr
    case other => Expr(other.span, List(other)) // Wrap non-Expr in Expr
  }

  /** Simplifies an expression that must remain an Expr (e.g., member body, cond branch). */
  def simplifyTopLevelExpr(expr: Expr): Expr = {
    val simplifiedTerm = simplifyTerm(expr)
    ensureExpr(simplifiedTerm)
  }

  /** Simplify the terms within an expression. Does not unwrap the expression itself. */
  def simplifyExpr(expr: Expr): Expr =
    // Simplify all subterms recursively using simplifyTerm
    val simplifiedTerms = expr.terms.map(simplifyTerm)
    // Always rebuild the Expr with the simplified terms
    Expr(expr.span, simplifiedTerms, expr.typeSpec, expr.typeAsc)

  /** Recursively simplify a term.
    *   - For an Expr: simplify its contents, then unwrap if only one term remains.
    *   - For a GroupTerm: simplify its inner expression and remove the group wrapper.
    *   - For AppN: simplify arguments
    *   - For Cond: simplify branches using simplifyTopLevelExpr.
    *   - Other terms are returned unchanged.
    */
  def simplifyTerm(term: Term): Term = {
    val result = term match {
      case e: Expr =>
        // Simplify the inside first using simplifyExpr (which calls simplifyTerm)
        val simplifiedExpr = simplifyExpr(e)
        // Check if the simplified expression should be unwrapped
        simplifiedExpr.terms match {
          // If only a single term remains, return it directly.
          case single :: Nil => single // Return the single term
          case _ => simplifiedExpr // Keep Expr with multiple terms
        }

      case group: TermGroup =>
        // Simplify the inner expression using simplifyExpr
        val simplifiedInnerExpr = simplifyExpr(group.inner)
        // Recursively simplify the result (which must be an Expr) using simplifyTerm
        val finalTerm = simplifyTerm(simplifiedInnerExpr) // Simplify the Expr result
        // Discard the group wrapper, transferring type ascription if present
        if group.typeAsc.isDefined then
          // Attempt to apply typeAsc; might need refinement depending on Term types
          finalTerm match {
            case ex: Expr => ex.copy(typeAsc = group.typeAsc.orElse(ex.typeAsc))
            // TODO: How to apply typeAsc to non-Expr terms? For now, ignore if not Expr.
            case other => other
          }
        else finalTerm

      case app: App =>
        // Simplify the function and argument (simplifyExpr calls simplifyTerm)
        val simplifiedArg = simplifyExpr(app.arg) // Use simplifyExpr for args
        val simplifiedFn  = simplifyTerm(app.fn) // Use simplifyTerm for fn part
        app.copy(fn = simplifiedFn.asInstanceOf[Ref | App], arg = simplifiedArg)

      // --- Modify Cond Case ---
      case c: Cond =>
        // Use simplifyTopLevelExpr for branches as they must remain Expr
        c.copy(
          cond    = simplifyTopLevelExpr(c.cond),
          ifTrue  = simplifyTopLevelExpr(c.ifTrue),
          ifFalse = simplifyTopLevelExpr(c.ifFalse)
        )
      // -------------------------

      // --- Add Case for Tuple ---
      case t: Tuple =>
        // Simplify each element, ensuring they remain Expr
        t.copy(elements = t.elements.map(simplifyTopLevelExpr))
      // --------------------------

      case other => other // Keep other terms as is
    } // End of term match
    result // Return the result of the match
  } // End of simplifyTerm function

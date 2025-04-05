package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object PrecedenceClimber:

  val MinPrecedence: Int = 1
  val FnPrecedence:  Int = 100 // Function application has highest precedence

  def rewriteModule(module: Module): Either[List[SemanticError], Module] =
    val rewrittenMembers = module.members.map {
      case bnd: Bnd =>
        rewriteExpr(bnd.value).map(updatedExpr => bnd.copy(value = updatedExpr))
      case fn: FnDef =>
        rewriteExpr(fn.body).map(updatedExpr => fn.copy(body = updatedExpr))
      case op: BinOpDef =>
        rewriteExpr(op.body).map(updatedExpr => op.copy(body = updatedExpr))
      case op: UnaryOpDef =>
        rewriteExpr(op.body).map(updatedExpr => op.copy(body = updatedExpr))
      case other => other.asRight
    }
    val errors = rewrittenMembers.collect { case Left(errs) => errs }.flatten
    if errors.nonEmpty then errors.asLeft
    else module.copy(members = rewrittenMembers.collect { case Right(member) => member }).asRight

  def rewriteExpr(expr: Expr): Either[List[SemanticError], Expr] =
    rewriteSubExpr(expr.terms, MinPrecedence, expr.span).map(_._1)

  private def rewriteSubExpr(
    terms:   List[Term],
    minPrec: Int,
    span:    SrcSpan
  ): Either[List[SemanticError], (Expr, List[Term])] =
    // Process one "atom" (a literal, group, or prefix application)
    def rewriteAtom(ts: List[Term]): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case (g: TermGroup) :: rest =>
          // A parenthesized group is handled as a single atom.
          rewriteSubExpr(g.inner.terms, MinPrecedence, g.span).map { case (subExpr, _) =>
            (Expr(g.span, List(subExpr)), rest)
          }
        case IsPrefixOpRef(ref, opDef, prec, assoc) :: rest =>
          // Prefix unary operator - set resolvedAs to the UnaryOpDef
          val resolvedRef = ref.copy(resolvedAs = opDef.some)
          rewriteSubExpr(rest, prec, span).flatMap { case (operand, remaining) =>
            val combined = Expr(span, List(resolvedRef, operand))
            rewriteOps(combined, remaining, minPrec)
          }
        case IsAtom(atom) :: rest =>
          (Expr(atom.span, List(atom)), rest).asRight
        case _ =>
          List(
            SemanticError.InvalidExpression(Expr(span, Nil), s"Expected an atom, got: $ts")
          ).asLeft

    // Process trailing operators on an expression.
    def rewriteOps(
      lhs:            Expr,
      ts:             List[Term],
      currentMinPrec: Int
    ): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case IsBinOpRef(ref, opDef, prec, assoc) :: rest if prec >= currentMinPrec =>
          // Binary operator - set resolvedAs to the BinOpDef
          val resolvedRef = ref.copy(resolvedAs = opDef.some)
          val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec
          rewriteSubExpr(rest, nextMinPrec, span).flatMap { case (rhs, remaining) =>
            val combined = Expr(span, List(lhs, resolvedRef, rhs))
            rewriteOps(combined, remaining, currentMinPrec)
          }
        case IsPostfixOpRef(ref, opDef, prec, _) :: rest if prec >= currentMinPrec =>
          // Postfix unary operator - set resolvedAs to the UnaryOpDef
          val resolvedRef = ref.copy(resolvedAs = opDef.some)
          val combined    = Expr(span, List(lhs, resolvedRef))
          rewriteOps(combined, rest, currentMinPrec)
        case (g: TermGroup) :: rest =>
          rewriteSubExpr(g.inner.terms, MinPrecedence, g.span).flatMap {
            case (subExpr, remaining) =>
              rewriteOps(Expr(g.span, List(subExpr)), rest, currentMinPrec)
          }
        case _ =>
          (lhs, ts).asRight

    for
      (atom, tsAfterAtom) <- rewriteAtom(terms)
      result <- rewriteOps(atom, tsAfterAtom, minPrec)
    yield result

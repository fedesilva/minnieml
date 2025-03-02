package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object PrecedenceClimber:

  val MinPrecedence = 1

  /** Rewrite a module by applying precedence climbing to all expressions. Assumes that all
    * references have been resolved.
    */
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
    else
      module
        .copy(
          members = rewrittenMembers.collect { case Right(member) => member }
        )
        .asRight

  def rewriteExpr(expr: Expr): Either[List[SemanticError], Expr] =
    rewriteSubExpr(expr.terms, MinPrecedence, expr.span).map(_._1)

  private def rewriteSubExpr(
    terms:   List[Term],
    minPrec: Int,
    span:    SourceSpan
  ): Either[List[SemanticError], (Expr, List[Term])] =
    // Parse a single atom or prefix operator application.
    def parseAtom(ts: List[Term]): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case (g: GroupTerm) :: rest =>
          rewriteSubExpr(g.inner.terms, MinPrecedence, g.span).flatMap {
            case (subExpr, remaining) =>
              (Expr(g.span, List(subExpr)), rest).asRight
          }
        case IsOp(ref, opDef, prec, assoc) :: rest
            if isUnary(opDef) && (assoc == Associativity.Right) =>
          // Prefix unary operator: parse its operand with operator's precedence,
          // then continue processing trailing operators with the outer minPrec.
          rewriteSubExpr(rest, prec, span).flatMap { case (operand, remaining) =>
            val combined = Expr(span, List(ref, operand))
            parseOps(combined, remaining, minPrec)
          }
        case IsAtom(atom) :: rest =>
          (Expr(atom.span, List(atom)), rest).asRight
        case _ =>
          List(
            SemanticError.InvalidExpression(Expr(span, Nil), s"Expected an atom, got: $ts")
          ).asLeft

    // Parse left-hand side combined with trailing operators.
    def parseOps(
      lhs:            Expr,
      ts:             List[Term],
      currentMinPrec: Int
    ): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case IsOp(ref, opDef, prec, assoc) :: rest if prec >= currentMinPrec =>
          opDef match
            case bin: BinOpDef =>
              val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec
              rewriteSubExpr(rest, nextMinPrec, span).flatMap { case (rhs, remaining) =>
                val combined = Expr(span, List(lhs, ref, rhs))
                parseOps(combined, remaining, currentMinPrec)
              }

            case unary: UnaryOpDef if assoc == Associativity.Left =>
              // Postfix operator case.
              val combined = Expr(span, List(lhs, ref))
              parseOps(combined, rest, currentMinPrec)

            case _ =>
              List(
                SemanticError.InvalidExpression(lhs, s"Operator ${ref.name} not supported here")
              ).asLeft
        case (g: GroupTerm) :: rest =>
          rewriteSubExpr(g.inner.terms, MinPrecedence, g.span).flatMap {
            case (subExpr, remaining) =>
              (Expr(g.span, List(subExpr)), remaining).asRight
          }
        case _ =>
          (lhs, ts).asRight

    for
      (atom, tsAfterAtom) <- parseAtom(terms)
      result <- parseOps(atom, tsAfterAtom, minPrec)
    yield result

  private def isUnary(op: OpDef): Boolean =
    op match
      case _: UnaryOpDef => true
      case _ => false

  // ---- Extractors abstract pattern matching away ----

  private object IsOp:
    def unapply(ref: Ref): Option[(Ref, OpDef, Int, Associativity)] =
      ref.resolvedAs match
        case Some(op: BinOpDef) => (ref, op, op.precedence, op.assoc).some
        case Some(op: UnaryOpDef) => (ref, op, op.precedence, op.assoc).some
        case _ => None

  private object IsFn:
    def unapply(ref: Ref): Option[(Ref, FnDef, Int, Associativity)] =
      ref.resolvedAs match
        case Some(fn: FnDef) => (ref, fn, MinPrecedence, Associativity.Left).some
        case _ => None

  /** Extracts terms that behave like values (bindings or literals) */
  private object IsAtom:
    def unapply(term: Term): Option[Term] =
      term match
        case v:     LiteralValue => v.some // Literals are always values
        case group: GroupTerm => group.some
        case h:     Hole => h.some
        case ref:   Ref if ref.resolvedAs.isDefined =>
          ref.resolvedAs match
            case Some(_: Bnd) => ref.some
            case _ => None
        case x => x.some

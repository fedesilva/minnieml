package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import cats.syntax.all.*
import eu.timepit.refined.api.Min

object PrecedenceClimbing:

  val MinPrecedence = 1

  /** Rewrite a module by applying precedence climbing to all expressions. Assumes that all
    * references have been resolved.
    */
  def rewriteModule(module: Module): Either[List[SemanticError], Module] =
    val rewrittenMembers = module.members.map {
      case bnd: Bnd =>
        rewriteExpr(bnd.value).map(updatedExpr => bnd.copy(value = updatedExpr))
      case other => other.asRight
    }
    val errors = rewrittenMembers.collect { case Left(errs) => errs }.flatten
    if errors.nonEmpty then errors.asLeft
    else module.copy(members = rewrittenMembers.collect { case Right(member) => member }).asRight

  def rewriteExpr(expr: Expr): Either[List[SemanticError], Expr] =
    parseSubExpr(expr.terms, MinPrecedence, expr.span).map(_._1)

  private def parseSubExpr(
    terms:   List[Term],
    minPrec: Int,
    span:    SourceSpan
  ): Either[List[SemanticError], (Expr, List[Term])] =
    // Parse a single atom or prefix operator application.
    def parseAtom(ts: List[Term]): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case IsOpLike(ref, opDef, prec, assoc) :: rest
            if isUnary(opDef) && (assoc == Associativity.Right) =>
          // Prefix unary operator
          val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec
          parseSubExpr(rest, nextMinPrec, span).flatMap { case (operand, remaining) =>
            // Build a prefix expression node as an Expr.
            val combined = Expr(span, List(ref, operand))
            Right((combined, remaining))
          }
        case IsAtom(atom) :: rest =>
          Right((Expr(atom.span, List(atom)), rest))
        case _ =>
          Left(
            List(SemanticError.InvalidExpression(Expr(span, Nil), s"Expected an atom, got: $ts"))
          )

    // Parse left-hand side combined with trailing operators.
    def parseOps(lhs: Expr, ts: List[Term]): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case IsOpLike(ref, opDef, prec, assoc) :: rest if prec >= minPrec =>
          opDef match
            case bin: BinOpDef =>
              val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec
              parseSubExpr(rest, nextMinPrec, span).flatMap { case (rhs, remaining) =>
                // Build a binary expression node as an Expr.
                val combined = Expr(span, List(lhs, ref, rhs))
                parseOps(combined, remaining)
              }
            case unary: UnaryOpDef if assoc == Associativity.Left =>
              // Postfix operator case.
              val combined = Expr(span, List(lhs, ref))
              parseOps(combined, rest)
            case _ =>
              Left(
                List(
                  SemanticError.InvalidExpression(lhs, s"Operator ${ref.name} not supported here")
                )
              )
        case _ =>
          Right((lhs, ts))

    for
      (atom, tsAfterAtom) <- parseAtom(terms)
      result <- parseOps(atom, tsAfterAtom)
    yield result

  private def isUnary(op: OpDef): Boolean =
    op match
      case _: UnaryOpDef => true
      case _ => false

  // ---- Embedded Extractors ----

  /** Extracts operators and functions that behave like operators */
  private object IsOpLike:
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
        case v:   LiteralValue => v.some // Literals are always values
        case ref: Ref if ref.resolvedAs.isDefined =>
          ref.resolvedAs.get match
            case _: Bnd => ref.some
            case _ => None
        case _ => None

package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object PrecedenceClimber:

  val MinPrecedence = 1
  val FnPrecedence  = 100 // Function application has highest precedence

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
    span:    SourceSpan
  ): Either[List[SemanticError], (Expr, List[Term])] =
    // Process one “atom” (a literal, group, or prefix application)
    def rewriteAtom(ts: List[Term]): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case (g: GroupTerm) :: rest =>
          // A parenthesized group is handled as a single atom.
          rewriteSubExpr(g.inner.terms, MinPrecedence, g.span).map { case (subExpr, _) =>
            (Expr(g.span, List(subExpr)), rest)
          }
        case IsOp(ref, opDef, prec, assoc) :: rest
            if isUnary(opDef) && assoc == Associativity.Right =>
          // Prefix unary operator.
          rewriteSubExpr(rest, prec, span).flatMap { case (operand, remaining) =>
            val combined = Expr(span, List(ref, operand))
            rewriteOps(combined, remaining, minPrec)
          }
        case IsFn(ref, fnDef) :: rest =>
          if rest.isEmpty then (Expr(ref.span, List(ref)), rest).asRight
          else rewriteFunctionApplication(ref, fnDef, rest, span)
        case IsAtom(atom) :: rest =>
          (Expr(atom.span, List(atom)), rest).asRight
        case _ =>
          List(
            SemanticError.InvalidExpression(Expr(span, Nil), s"Expected an atom, got: $ts")
          ).asLeft

    // Rewrite a function application: collect exactly the number of arguments declared.
    def rewriteFunctionApplication(
      fnRef:    Ref,
      fnDef:    FnDef,
      rest:     List[Term],
      exprSpan: SourceSpan
    ): Either[List[SemanticError], (Expr, List[Term])] =
      val expectedArgs = fnDef.params.size

      def collectArgs(
        remaining:  List[Term],
        collected:  List[Expr],
        argsNeeded: Int
      ): Either[List[SemanticError], (List[Expr], List[Term])] =
        if argsNeeded == 0 || remaining.isEmpty then (collected, remaining).asRight
        else
          // Always process one atom at a time.
          rewriteAtom(remaining).flatMap { case (argExpr, nextRemaining) =>
            collectArgs(nextRemaining, collected :+ argExpr, argsNeeded - 1)
          }

      collectArgs(rest, Nil, expectedArgs).flatMap { case (args, remaining) =>
        val appNode = AppN(exprSpan, fnRef, args, None, None)
        // If no tokens remain, do not rewrite further.
        if remaining.isEmpty then (Expr(exprSpan, List(appNode)), remaining).asRight
        else rewriteOps(Expr(exprSpan, List(appNode)), remaining, minPrec)
      }

    // Process trailing operators on an expression.
    def rewriteOps(
      lhs:            Expr,
      ts:             List[Term],
      currentMinPrec: Int
    ): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case IsFn(ref, fnDef) :: rest if FnPrecedence >= currentMinPrec =>
          if rest.isEmpty then (lhs, ts).asRight
          else
            rewriteFunctionApplication(ref, fnDef, rest, span).flatMap { case (fnApp, remaining) =>
              rewriteOps(fnApp, remaining, currentMinPrec)
            }
        case IsOp(ref, opDef, prec, assoc) :: rest if prec >= currentMinPrec =>
          opDef match
            case bin: BinOpDef =>
              val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec
              rewriteSubExpr(rest, nextMinPrec, span).flatMap { case (rhs, remaining) =>
                val combined = Expr(span, List(lhs, ref, rhs))
                rewriteOps(combined, remaining, currentMinPrec)
              }
            case unary: UnaryOpDef if assoc == Associativity.Left =>
              val combined = Expr(span, List(lhs, ref))
              rewriteOps(combined, rest, currentMinPrec)
            case _ =>
              List(
                SemanticError.InvalidExpression(lhs, s"Operator ${ref.name} not supported here")
              ).asLeft
        case (g: GroupTerm) :: rest =>
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

  private def isUnary(op: OpDef): Boolean =
    op match
      case _: UnaryOpDef => true
      case _ => false

  // --- Extractors for pattern matching ---

  private object IsOp:
    def unapply(term: Term): Option[(Ref, OpDef, Int, Associativity)] = term match
      case ref: Ref =>
        ref.resolvedAs match
          case Some(op: BinOpDef) => Some((ref, op, op.precedence, op.assoc))
          case Some(op: UnaryOpDef) => Some((ref, op, op.precedence, op.assoc))
          case _ => None
      case _ => None

  private object IsFn:
    def unapply(term: Term): Option[(Ref, FnDef)] = term match
      case ref: Ref =>
        ref.resolvedAs match
          case Some(fn: FnDef) => Some((ref, fn))
          case _ => None
      case _ => None

  private object IsAtom:
    def unapply(term: Term): Option[Term] = term match
      case v:   LiteralValue => Some(v)
      case g:   GroupTerm => Some(g)
      case h:   Hole => Some(h)
      case ref: Ref if ref.resolvedAs.isDefined =>
        ref.resolvedAs match
          case Some(_: Bnd) => Some(ref)
          case _ => None
      case x => Some(x)

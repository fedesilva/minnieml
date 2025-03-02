package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object PrecedenceClimber:

  val MinPrecedence = 1
  val FnPrecedence  = 100 // Function application has highest precedence

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
    // Rewrite a single atom or prefix operator application.
    def rewriteAtom(ts: List[Term]): Either[List[SemanticError], (Expr, List[Term])] =
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
            rewriteOps(combined, remaining, minPrec)
          }
        case IsFn(ref, fnDef) :: rest =>
          // Function application has highest precedence
          // But only create AppN if there are actually arguments
          if rest.isEmpty then
            // No arguments - just return the function reference
            (Expr(ref.span, List(ref)), rest).asRight
          else
            // Collect function arguments and create AppN
            rewriteFunctionApplication(ref, fnDef, rest, span)
        case IsAtom(atom) :: rest =>
          (Expr(atom.span, List(atom)), rest).asRight
        case _ =>
          List(
            SemanticError.InvalidExpression(Expr(span, Nil), s"Expected an atom, got: $ts")
          ).asLeft

    // Rewrite function application with highest precedence
    def rewriteFunctionApplication(
      fnRef:    Ref,
      fnDef:    FnDef,
      rest:     List[Term],
      exprSpan: SourceSpan
    ): Either[List[SemanticError], (Expr, List[Term])] =
      // Count expected arguments
      val expectedArgs = fnDef.params.size

      // Collect arguments, stopping at operators or when we have enough arguments
      def collectArgs(
        remaining:  List[Term],
        collected:  List[Expr],
        argsNeeded: Int
      ): Either[List[SemanticError], (List[Expr], List[Term])] =
        if argsNeeded == 0 || remaining.isEmpty then (collected, remaining).asRight
        else
          remaining match
            case IsOp(_, _, _, _) :: _ if collected.nonEmpty =>
              // Stop at operators if we've collected at least one argument
              (collected, remaining).asRight
            case _ =>
              // Try to rewrite next argument with minimum precedence
              rewriteSubExpr(remaining, MinPrecedence, exprSpan).flatMap {
                case (argExpr, nextRemaining) =>
                  collectArgs(nextRemaining, collected :+ argExpr, argsNeeded - 1)
              }

      // Collect function arguments
      collectArgs(rest, Nil, expectedArgs).flatMap { case (args, remaining) =>
        if args.isEmpty then
          // If no arguments were collected, return function reference as is
          (Expr(fnRef.span, List(fnRef)), remaining).asRight
        else
          // Create AppN node with collected arguments
          val appNode = AppN(exprSpan, fnRef, args, None, None)

          // Continue with operator parsing
          rewriteOps(Expr(exprSpan, List(appNode)), remaining, minPrec)
      }

    // Rewrite left-hand side combined with trailing operators.
    def rewriteOps(
      lhs:            Expr,
      ts:             List[Term],
      currentMinPrec: Int
    ): Either[List[SemanticError], (Expr, List[Term])] =
      ts match
        case IsFn(ref, fnDef) :: rest if FnPrecedence >= currentMinPrec =>
          // Function application has highest precedence
          if rest.isEmpty then
            // If there are no arguments, just treat the function as a value
            // and continue with the current LHS
            (lhs, ts).asRight
          else
            // Otherwise rewrite function application
            rewriteFunctionApplication(ref, fnDef, rest, span).flatMap { case (fnApp, remaining) =>
              // After function application, check for more operators
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
              // Postfix operator case.
              val combined = Expr(span, List(lhs, ref))
              rewriteOps(combined, rest, currentMinPrec)

            case _ =>
              List(
                SemanticError.InvalidExpression(lhs, s"Operator ${ref.name} not supported here")
              ).asLeft
        case (g: GroupTerm) :: rest =>
          rewriteSubExpr(g.inner.terms, MinPrecedence, g.span).flatMap {
            case (subExpr, remaining) =>
              // After group, check for function application or operators
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

  // ---- Extractors abstract pattern matching away ----

  private object IsOp:
    def unapply(term: Term): Option[(Ref, OpDef, Int, Associativity)] =
      term match
        case ref: Ref =>
          ref.resolvedAs match
            case Some(op: BinOpDef) => (ref, op, op.precedence, op.assoc).some
            case Some(op: UnaryOpDef) => (ref, op, op.precedence, op.assoc).some
            case _ => None
        case _ => None

  private object IsFn:
    def unapply(term: Term): Option[(Ref, FnDef)] =
      term match
        case ref: Ref =>
          ref.resolvedAs match
            case Some(fn: FnDef) => (ref, fn).some
            case _ => None
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

package mml.mmlclib.semantic

import cats.data.NonEmptyList as NEL
import cats.syntax.all.*
import mml.mmlclib.ast.*

/** ExpressionRewriter handles expression transformations, including function applications and
  * operator precedence. It treats function application as an implicit high-precedence operator
  * (juxtaposition).
  */
object ExpressionRewriter:

  private val phaseName = "mml.mmlclib.semantic.ExpressionRewriter"

  private val MinPrecedence: Int = 1

  /** Rewrite a module, handling all expression transformations in a single pass
    */
  def rewriteModule(module: Module): Either[List[SemanticError], Module] =
    val rewrittenMembers: List[Either[List[SemanticError], Member]] = module.members.map {
      case bnd: Bnd =>
        rewriteExpr(bnd.value).map(updatedExpr => bnd.copy(value = updatedExpr)).leftMap(_.toList)
      case fn: FnDef =>
        rewriteExpr(fn.body).map(updatedExpr => fn.copy(body = updatedExpr)).leftMap(_.toList)
      case op: BinOpDef =>
        rewriteExpr(op.body).map(updatedExpr => op.copy(body = updatedExpr)).leftMap(_.toList)
      case op: UnaryOpDef =>
        rewriteExpr(op.body).map(updatedExpr => op.copy(body = updatedExpr)).leftMap(_.toList)
      case other =>
        other.asRight
    }

    val errors = rewrittenMembers.collect { case Left(errs) => errs }.flatten
    if errors.nonEmpty then errors.asLeft
    else module.copy(members = rewrittenMembers.collect { case Right(member) => member }).asRight

  /** Rewrite a module, accumulating errors in the state. */
  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState =
    val (errors, members) =
      state.module.members.foldLeft((List.empty[SemanticError], List.empty[Member])) {
        case ((accErrors, accMembers), member) =>
          val result = member match
            case bnd: Bnd =>
              rewriteExpr(bnd.value)
                .map(updatedExpr => bnd.copy(value = updatedExpr))
                .leftMap(_.toList)
            case fn: FnDef =>
              rewriteExpr(fn.body).map(updatedExpr => fn.copy(body = updatedExpr)).leftMap(_.toList)
            case op: BinOpDef =>
              rewriteExpr(op.body).map(updatedExpr => op.copy(body = updatedExpr)).leftMap(_.toList)
            case op: UnaryOpDef =>
              rewriteExpr(op.body).map(updatedExpr => op.copy(body = updatedExpr)).leftMap(_.toList)
            case other =>
              other.asRight

          result match
            case Left(errs) =>
              (accErrors ++ errs, accMembers :+ member) // Keep original member on error
            case Right(updated) => (accErrors, accMembers :+ updated)
      }
    state.addErrors(errors).withModule(state.module.copy(members = members))

  /** Rewrite an expression using precedence climbing for both operators and function application
    */
  private def rewriteExpr(expr: Expr): Either[NEL[SemanticError], Expr] =
    rewritePrecedenceExpr(expr.terms, MinPrecedence, expr.span).flatMap {
      case (result, remaining) =>
        if remaining.isEmpty then
          // All terms processed successfully
          result.asRight
        else
          // Remaining terms after expression - this means they're dangling
          NEL
            .one(
              SemanticError.DanglingTerms(
                remaining,
                "Unexpected terms outside expression context",
                phaseName
              )
            )
            .asLeft
    }

  /** Rewrite an expression using precedence climbing
    */
  private def rewritePrecedenceExpr(
    terms:   List[Term],
    minPrec: Int,
    span:    SrcSpan
  ): Either[NEL[SemanticError], (Expr, List[Term])] =
    for
      // First, rewrite any inner expressions in each term
      rewrittenTerms <- terms.traverse(rewriteTerm)

      // Rewrite the first atom in the expression
      (lhs, restAfterAtom) <- rewriteAtom(rewrittenTerms, span)

      // Rewrite any operations with sufficient precedence
      result <- rewriteOps(lhs, restAfterAtom, minPrec, span)
    yield result

  /** Rewrite the first atom in a list of terms
    */
  private def rewriteAtom(
    terms: List[Term],
    span:  SrcSpan
  ): Either[NEL[SemanticError], (Expr, List[Term])] =
    terms match
      case Nil =>
        NEL
          .one(
            SemanticError.InvalidExpression(
              Expr(span, Nil),
              "Expected an expression, got empty terms",
              phaseName
            )
          )
          .asLeft

      case (g: TermGroup) :: rest =>
        // Process a parenthesized group - treat as a bounded expression
        rewritePrecedenceExpr(g.inner.terms, MinPrecedence, g.span).map { case (innerExpr, _) =>
          (Expr(g.span, List(innerExpr)), rest)
        }

      case IsPrefixOpRef(ref, opDef, prec, assoc) :: rest =>
        // Prefix unary operator (like +, -, etc.)
        val resolvedRef = ref.copy(resolvedAs = opDef.some)
        val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec

        rewritePrecedenceExpr(rest, nextMinPrec, span).map { case (operand, remaining) =>
          // Transform prefix operator to function application
          val opApp = App(span, resolvedRef, operand)
          (Expr(span, List(opApp)), remaining)
        }

      case IsFnRef(ref, fnDef) :: rest =>
        // Function reference - handle as potential function application
        val resolvedRef = ref.copy(resolvedAs = fnDef.some)
        // This is the entry point for an application chain.
        // We recursively build the application chain from left to right.
        buildAppChain(resolvedRef, rest, span)

      case IsAtom(atom) :: rest =>
        // Simple atom (literal, variable, etc.)
        (Expr(atom.span, List(atom)), rest).asRight

      case terms =>
        // Invalid expression structure
        NEL
          .one(
            SemanticError
              .InvalidExpression(Expr(span, terms), "Invalid expression structure", phaseName)
          )
          .asLeft

  /** Process operations using precedence climbing
    */
  private def rewriteOps(
    lhs:     Expr,
    terms:   List[Term],
    minPrec: Int,
    span:    SrcSpan
  ): Either[NEL[SemanticError], (Expr, List[Term])] =
    terms match
      case IsBinOpRef(ref, opDef, prec, assoc) :: rest if prec >= minPrec =>
        // Binary operator with sufficient precedence
        val resolvedRef = ref.copy(resolvedAs = opDef.some)
        val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec

        rewritePrecedenceExpr(rest, nextMinPrec, span).flatMap { case (rhs, remaining) =>
          // Transform operator expression to function application
          val opApp    = App(span, App(span, resolvedRef, lhs), rhs)
          val combined = Expr(span, List(opApp))
          rewriteOps(combined, remaining, minPrec, span)
        }

      case IsPostfixOpRef(ref, opDef, prec, _) :: rest if prec >= minPrec =>
        // Postfix unary operator with sufficient precedence
        val resolvedRef = ref.copy(resolvedAs = opDef.some)
        // Transform postfix operator to function application
        val opApp    = App(span, resolvedRef, lhs)
        val combined = Expr(span, List(opApp))
        rewriteOps(combined, rest, minPrec, span)

      case (g: TermGroup) :: rest =>
        // A group after a complete expression is invalid - expected operator
        // For example: expr (term) is invalid without an operator between them
        NEL
          .one(
            SemanticError.DanglingTerms(
              List(g),
              "Unexpected group after expression - expected an operator",
              phaseName
            )
          )
          .asLeft

      case term :: rest if !isOperator(term) && !canBeApplied(lhs) =>
        // Non-operator term after an expression that can't be applied to
        // For example: literal juxtaposed with other term, like 1 2
        NEL
          .one(
            SemanticError.DanglingTerms(
              List(term),
              "Unexpected term after expression - expected an operator",
              phaseName
            )
          )
          .asLeft

      case _ =>
        // No more operations with sufficient precedence
        (lhs, terms).asRight

  /** Check if a function reference is to a nullary (zero-parameter) function
    */
  private def isNullaryFunction(term: Term): Boolean =
    term match
      case ref: Ref =>
        ref.resolvedAs match
          case Some(fnDef: FnDef) => fnDef.params.isEmpty
          case _ => false
      case _ => false

  /** Build a chain of function applications recursively.
    *
    * This function handles the left-associativity of application (`f x y` -> `((f x) y)`) and also
    * the correct grouping for nested function calls (`f g x` -> `(f (g x))`).
    *
    * It works by parsing one argument, applying it to the current function (`fn`), and then
    * recursively calling itself with the new application as the function.
    *
    * Special case: For nullary functions in value position (no arguments following), automatically
    * wraps them in an App node with a unit argument to ensure proper code generation.
    */
  private def buildAppChain(
    fn:    Term,
    terms: List[Term],
    span:  SrcSpan
  ): Either[NEL[SemanticError], (Expr, List[Term])] =
    terms match
      case Nil =>
        // No more arguments. Check if this is a nullary function in value position.
        if isNullaryFunction(fn) then
          // Auto-wrap nullary function with unit argument to ensure it's called
          fn match
            case ref: Ref =>
              val unitLit  = LiteralUnit(fn.span)
              val unitExpr = Expr(fn.span, List(unitLit))
              val app      = App(fn.span, ref, unitExpr, typeAsc = None, typeSpec = None)
              (Expr(fn.span, List(app)), terms).asRight
            case _ =>
              // This shouldn't happen since isNullaryFunction checks for Ref
              (Expr(fn.span, List(fn)), terms).asRight
        else
          // Not a nullary function, just return the function reference
          (Expr(fn.span, List(fn)), terms).asRight
      case t :: _ if isOperator(t) =>
        // An operator ends the application chain.
        // Check if this is a nullary function that should be auto-wrapped
        if isNullaryFunction(fn) then
          fn match
            case ref: Ref =>
              val unitLit  = LiteralUnit(fn.span)
              val unitExpr = Expr(fn.span, List(unitLit))
              val app      = App(fn.span, ref, unitExpr, typeAsc = None, typeSpec = None)
              (Expr(fn.span, List(app)), terms).asRight
            case _ =>
              (Expr(fn.span, List(fn)), terms).asRight
        else (Expr(fn.span, List(fn)), terms).asRight
      case _ =>
        // The next terms are arguments. Parse one argument expression.
        // `rewriteAtom` will correctly parse a simple term, a group, or a nested application.
        rewriteAtom(terms, span).flatMap { case (argExpr, remainingTerms) =>
          // Build the application of the current function to the parsed argument.
          buildSingleApp(fn, argExpr, span).flatMap { app =>
            // Continue building the chain with the new application as the function.
            // This creates the left-associative structure.
            buildAppChain(app, remainingTerms, span)
          }
        }

  /** Build a single application node
    */
  private def buildSingleApp(
    fn:   Term,
    arg:  Expr,
    span: SrcSpan
  ): Either[NEL[SemanticError], Term] =
    fn match
      case ref: Ref => App(span, ref, arg, typeAsc = None, typeSpec = None).asRight
      case app: App => App(span, app, arg, typeAsc = None, typeSpec = None).asRight
      case _ =>
        // Term that's not a function or application can't be applied to
        NEL
          .one(
            SemanticError.DanglingTerms(
              arg.terms,
              "These terms cannot be applied to a non-function",
              phaseName
            )
          )
          .asLeft

  /** Check if an expression can have arguments applied to it
    */
  private def canBeApplied(expr: Expr): Boolean =
    expr.terms.lastOption.exists {
      case _: Ref => true
      case _: App => true
      case _ => false
    }

  /** Rewrite a term by recursively processing inner expressions
    */
  private def rewriteTerm(term: Term): Either[NEL[SemanticError], Term] =
    term match
      case inv: InvalidExpression =>
        // Report that we found an invalid expression from an earlier phase
        NEL.one(SemanticError.InvalidExpressionFound(inv, phaseName)).asLeft
      case e: Expr =>
        rewriteExpr(e)
      case c: Cond =>
        for
          newCond <- rewriteExpr(c.cond)
          newIfTrue <- rewriteExpr(c.ifTrue)
          newIfFalse <- rewriteExpr(c.ifFalse)
        yield c.copy(cond = newCond, ifTrue = newIfTrue, ifFalse = newIfFalse)
      case t: Tuple =>
        t.elements.traverse(rewriteExpr).map(newElems => t.copy(elements = newElems))
      case tg: TermGroup =>
        rewriteExpr(tg.inner).map(newInner => tg.copy(inner = newInner))
      case other =>
        other.asRight

  /** Check if a term is an operator
    */
  private def isOperator(term: Term): Boolean =
    term match
      case IsOpRef(_, _, _, _) => true
      case _ => false

package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** ExpressionRewriter handles expression transformations, including function applications and
  * operator precedence. It treats function application as an implicit high-precedence operator
  * (juxtaposition).
  */
object ExpressionRewriter:

  val MinPrecedence: Int = 1
  val AppPrecedence: Int = 100 // Function application has highest precedence

  /** Rewrite a module, handling all expression transformations in a single pass
    */
  def rewriteModule(module: Module): Either[List[SemanticError], Module] =
    val rewrittenMembers: List[Either[List[SemanticError], Member]] = module.members.map {
      case bnd: Bnd =>
        rewriteExpr(bnd.value).map(updatedExpr => bnd.copy(value = updatedExpr))
      case fn: FnDef =>
        rewriteExpr(fn.body).map(updatedExpr => fn.copy(body = updatedExpr))
      case op: BinOpDef =>
        rewriteExpr(op.body).map(updatedExpr => op.copy(body = updatedExpr))
      case op: UnaryOpDef =>
        rewriteExpr(op.body).map(updatedExpr => op.copy(body = updatedExpr))
      case other =>
        other.asRight
    }

    val errors = rewrittenMembers.collect { case Left(errs) => errs }.flatten
    if errors.nonEmpty then errors.asLeft
    else module.copy(members = rewrittenMembers.collect { case Right(member) => member }).asRight

  /** Rewrite an expression using precedence climbing for both operators and function application
    */
  def rewriteExpr(expr: Expr): Either[List[SemanticError], Expr] =
    parsePrecedenceExpr(expr.terms, MinPrecedence, expr.span).flatMap { case (result, remaining) =>
      if remaining.isEmpty then
        // All terms processed successfully
        result.asRight
      else
        // Remaining terms after expression - this means they're dangling
        List(
          SemanticError.DanglingTerms(
            remaining,
            "Unexpected terms outside expression context"
          )
        ).asLeft
    }

  /** Parse an expression using precedence climbing
    */
  private def parsePrecedenceExpr(
    terms:   List[Term],
    minPrec: Int,
    span:    SrcSpan
  ): Either[List[SemanticError], (Expr, List[Term])] =
    for
      // First, rewrite any inner expressions in each term
      rewrittenTerms <- terms.traverse(rewriteTerm)

      // Parse the first atom in the expression
      (lhs, restAfterAtom) <- parseAtom(rewrittenTerms, span)

      // Parse any operations with sufficient precedence
      result <- parseOps(lhs, restAfterAtom, minPrec, span)
    yield result

  /** Parse the first atom in a list of terms
    */
  private def parseAtom(
    terms: List[Term],
    span:  SrcSpan
  ): Either[List[SemanticError], (Expr, List[Term])] =
    terms match
      case Nil =>
        List(
          SemanticError.InvalidExpression(
            Expr(span, Nil),
            "Expected an expression, got empty terms"
          )
        ).asLeft

      case (g: TermGroup) :: rest =>
        // Process a parenthesized group - treat as a bounded expression
        // We process the full inner expression and ignore any remaining terms inside the group
        parsePrecedenceExpr(g.inner.terms, MinPrecedence, g.span).map { case (innerExpr, _) =>
          (Expr(g.span, List(innerExpr)), rest)
        }

      case IsPrefixOpRef(ref, opDef, prec, assoc) :: rest =>
        // Prefix unary operator (like +, -, etc.)
        val resolvedRef = ref.copy(resolvedAs = opDef.some)
        val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec

        parsePrecedenceExpr(rest, nextMinPrec, span).map { case (operand, remaining) =>
          (Expr(span, List(resolvedRef, operand)), remaining)
        }

      case IsFnRef(ref, fnDef) :: rest =>
        // Function reference - handle as potential function application
        val resolvedRef = ref.copy(resolvedAs = fnDef.some)

        // In ML languages, collect all juxtaposed terms until finding an operator
        // These will be treated as a chain of function applications
        val nonOpArgs = rest.takeWhile(term => !isOperator(term))
        val opTerms   = rest.drop(nonOpArgs.length)

        if nonOpArgs.isEmpty then
          // No arguments, just return the function reference
          (Expr(ref.span, List(resolvedRef)), opTerms).asRight
        else
          // Build a chain of function applications with all arguments
          buildAppChain(resolvedRef, nonOpArgs, span).map { appChain =>
            (Expr(span, List(appChain)), opTerms)
          }

      case IsAtom(atom) :: rest =>
        // Simple atom (literal, variable, etc.)
        (Expr(atom.span, List(atom)), rest).asRight

      case terms =>
        // Invalid expression structure
        List(
          SemanticError.InvalidExpression(Expr(span, terms), "Invalid expression structure")
        ).asLeft

  /** Process operations using precedence climbing
    */
  private def parseOps(
    lhs:     Expr,
    terms:   List[Term],
    minPrec: Int,
    span:    SrcSpan
  ): Either[List[SemanticError], (Expr, List[Term])] =
    terms match
      case IsBinOpRef(ref, opDef, prec, assoc) :: rest if prec >= minPrec =>
        // Binary operator with sufficient precedence
        val resolvedRef = ref.copy(resolvedAs = opDef.some)
        val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec

        parsePrecedenceExpr(rest, nextMinPrec, span).flatMap { case (rhs, remaining) =>
          val combined = Expr(span, List(lhs, resolvedRef, rhs))
          parseOps(combined, remaining, minPrec, span)
        }

      case IsPostfixOpRef(ref, opDef, prec, _) :: rest if prec >= minPrec =>
        // Postfix unary operator with sufficient precedence
        val resolvedRef = ref.copy(resolvedAs = opDef.some)
        val combined    = Expr(span, List(lhs, resolvedRef))
        parseOps(combined, rest, minPrec, span)

      case (g: TermGroup) :: rest =>
        // A group after a complete expression is invalid - expected operator
        // For example: expr (term) is invalid without an operator between them
        List(
          SemanticError.DanglingTerms(
            List(g),
            "Unexpected group after expression - expected an operator"
          )
        ).asLeft

      case term :: rest if !isOperator(term) && !canBeApplied(lhs) =>
        // Non-operator term after an expression that can't be applied to
        // For example: literal juxtaposed with other term, like 1 2
        List(
          SemanticError.DanglingTerms(
            List(term),
            "Unexpected term after expression - expected an operator"
          )
        ).asLeft

      case _ =>
        // No more operations with sufficient precedence
        (lhs, terms).asRight

  /** Build a chain of function applications
    */
  private def buildAppChain(
    fn:   Term,
    args: List[Term],
    span: SrcSpan
  ): Either[List[SemanticError], Term] =
    args.foldLeftM(fn) { (accFn, argTerm) =>
      // First rewrite the argument
      rewriteTerm(argTerm).flatMap { rewrittenArg =>
        // Then convert to an expression if needed
        termToExpr(rewrittenArg).flatMap { argExpr =>
          // Then build the application node
          buildSingleApp(accFn, argExpr, span)
        }
      }
    }

  /** Build a single application node
    */
  private def buildSingleApp(
    fn:   Term,
    arg:  Expr,
    span: SrcSpan
  ): Either[List[SemanticError], Term] =
    fn match
      case r: Ref => App(span, r, arg, typeAsc = None, typeSpec = None).asRight
      case a: App => App(span, a, arg, typeAsc = None, typeSpec = None).asRight
      case _ =>
        // Term that's not a function or application can't be applied to
        List(
          SemanticError.DanglingTerms(
            arg.terms,
            "These terms cannot be applied to a non-function"
          )
        ).asLeft

  /** Check if an expression can have arguments applied to it
    */
  private def canBeApplied(expr: Expr): Boolean =
    expr.terms.lastOption.exists {
      case _: Ref => true
      case _: App => true
      case _ => false
    }

  /** Convert a term to an expression
    */
  private def termToExpr(term: Term): Either[List[SemanticError], Expr] =
    term match
      case e: Expr => e.asRight
      case _ => rewriteTerm(term).map(t => Expr(t.span, List(t)))

  /** Rewrite a term by recursively processing inner expressions
    */
  private def rewriteTerm(term: Term): Either[List[SemanticError], Term] =
    term match
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

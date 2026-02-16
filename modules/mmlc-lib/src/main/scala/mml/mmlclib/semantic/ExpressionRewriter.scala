package mml.mmlclib.semantic

import cats.data.NonEmptyList as NEL
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** ExpressionRewriter handles expression transformations, including function applications and
  * operator precedence. It treats function application as an implicit high-precedence operator
  * (juxtaposition).
  */
object ExpressionRewriter:

  private val phaseName = "mml.mmlclib.semantic.ExpressionRewriter"

  private val MinPrecedence: Int = 1

  // --- Eta-expansion helpers for partial application ---

  /** Get the root Ref from an App chain */
  private def getRootRef(term: Term): Option[Ref] =
    term match
      case ref: Ref => Some(ref)
      case app: App => getRootRef(app.fn)
      case _ => None

  /** Get arity and params from a resolved callable (Bnd with Lambda).
    *
    * Uses transformedBindings to look up already-transformed Bnds (for chained partial
    * application), falling back to module index for not-yet-transformed bindings.
    */
  private def getArityAndParams(
    fn:                  Term,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Option[(Int, List[FnParam])] =
    getRootRef(fn).flatMap { ref =>
      // First check transformed bindings (for chained partial application)
      val bndOpt = transformedBindings
        .get(ref.name)
        .orElse(
          ref.resolvedId.flatMap(resolvables.lookup).collect { case bnd: Bnd => bnd }
        )
      bndOpt.flatMap { bnd =>
        bnd.value.terms.headOption.collect { case lambda: Lambda =>
          (lambda.params.length, lambda.params)
        }
      }
    }

  /** Count how many args have been applied in an App chain */
  private def countAppliedArgs(fn: Term): Int =
    fn match
      case _:   Ref => 0
      case app: App => 1 + countAppliedArgs(app.fn)
      case _ => 0

  /** Wrap undersaturated application in a Lambda (eta-expansion).
    *
    * If `fn` is an undersaturated application (fewer args than arity), or a bare function
    * reference, wrap it in a Lambda with synthetic parameters for the remaining args.
    */
  private def wrapIfUndersaturated(
    fn:                  Term,
    span:                SrcSpan,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Either[NEL[SemanticError], Expr] =
    // Only wrap if fn is Ref or App (the only valid function positions)
    val fnAsCallable: Option[Ref | App] = fn match
      case r: Ref => Some(r)
      case a: App => Some(a)
      case _ => None

    fnAsCallable
      .flatMap { callable =>
        getArityAndParams(fn, transformedBindings, resolvables).flatMap { case (arity, params) =>
          val appliedCount = countAppliedArgs(fn)
          if appliedCount < arity then
            val remainingParams = params.drop(appliedCount)
            // Ban partial application when any remaining param is consuming
            remainingParams.find(_.consuming) match
              case Some(consumingParam) =>
                Some(
                  NEL
                    .one(
                      SemanticError
                        .PartialApplicationWithConsuming(fn, consumingParam, phaseName)
                    )
                    .asLeft
                )
              case None =>
                val syntheticParams = remainingParams.zipWithIndex.map { (p, i) =>
                  FnParam(
                    SourceOrigin.Synth,
                    Name.synth(s"$$p$i"),
                    typeAsc  = p.typeAsc,
                    typeSpec = p.typeSpec
                  )
                }
                // Pre-resolve synthetic Refs to their FnParams (RefResolver already ran)
                val syntheticRefs = syntheticParams.map { param =>
                  Ref(
                    SourceOrigin.Synth,
                    param.name,
                    resolvedId   = param.id,
                    candidateIds = param.id.toList
                  )
                }
                // Build App chain with synthetic args
                val fullApp = syntheticRefs.foldLeft[Ref | App](callable) { (acc, ref) =>
                  App(span, acc, Expr(span, List(ref)))
                }
                val lambda =
                  Lambda(span, syntheticParams, Expr(span, List(fullApp)), captures = Nil)
                Some(Expr(span, List(lambda)).asRight)
          else None
        }
      }
      .getOrElse(Expr(fn.span, List(fn)).asRight)

  /** Rewrite a module, accumulating errors in the state. */
  def rewriteModule(state: CompilerState): CompilerState =
    val (errors, members, _, updatedResolvables) = state.module.members.foldLeft(
      (
        List.empty[SemanticError],
        List.empty[Member],
        Map.empty[String, Bnd],
        state.module.resolvables
      )
    ) { case ((accErrors, accMembers, transformedBindings, resolvables), member) =>
      member match
        case bnd: Bnd =>
          rewriteExpr(bnd.value, transformedBindings, resolvables) match
            case Right(updatedExpr) =>
              val updatedBnd = bnd.copy(value = updatedExpr)
              (
                accErrors,
                accMembers :+ updatedBnd,
                transformedBindings + (bnd.name -> updatedBnd),
                resolvables.updated(updatedBnd)
              )
            case Left(errs) =>
              (accErrors ++ errs.toList, accMembers :+ bnd, transformedBindings, resolvables)
        case other =>
          (accErrors, accMembers :+ other, transformedBindings, resolvables)
    }
    state
      .addErrors(errors)
      .withModule(state.module.copy(members = members, resolvables = updatedResolvables))

  /** Rewrite an expression using precedence climbing for both operators and function application
    */
  private def rewriteExpr(
    expr:                Expr,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Either[NEL[SemanticError], Expr] =
    rewritePrecedenceExpr(expr.terms, MinPrecedence, expr.span, transformedBindings, resolvables)
      .flatMap { case (result, remaining) =>
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
    terms:               List[Term],
    minPrec:             Int,
    span:                SrcSpan,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Either[NEL[SemanticError], (Expr, List[Term])] =
    for
      // First, rewrite any inner expressions in each term
      rewrittenTerms <- terms.traverse(rewriteTerm(_, transformedBindings, resolvables))

      // Rewrite the first atom in the expression
      (lhs, restAfterAtom) <- rewriteAtom(rewrittenTerms, span, transformedBindings, resolvables)

      // Rewrite any operations with sufficient precedence
      result <- rewriteOps(lhs, restAfterAtom, minPrec, span, transformedBindings, resolvables)
    yield result

  /** Rewrite the first atom in a list of terms
    */
  private def rewriteAtom(
    terms:               List[Term],
    span:                SrcSpan,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
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
        rewriteGroupAtom(g, transformedBindings, resolvables).flatMap { term =>
          buildAppChain(term, rest, g.span, transformedBindings, resolvables)
        }

      case (head :: rest) =>
        matchPrefixOpRef(head, resolvables) match
          case Some((ref, bnd, prec, assoc)) =>
            // Prefix unary operator (like +, -, etc.)
            val resolvedRef = ref.copy(resolvedId = bnd.id)
            val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec

            rewritePrecedenceExpr(rest, nextMinPrec, span, transformedBindings, resolvables).map {
              case (operand, remaining) =>
                // Transform prefix operator to function application
                val opApp = App(span, resolvedRef, operand)
                (Expr(span, List(opApp)), remaining)
            }
          case None =>
            // Not a prefix operator - check for ref, atom, or error
            head match
              case ref: Ref =>
                // Any reference - handle as potential function application
                buildAppChain(ref, rest, span, transformedBindings, resolvables)
              case IsAtom(atom) =>
                // Simple atom (literal, hole, etc.)
                (Expr(atom.span, List(atom)), rest).asRight
              case _ =>
                // Invalid expression structure
                NEL
                  .one(
                    SemanticError.InvalidExpression(
                      Expr(span, head :: rest),
                      "Invalid expression structure",
                      phaseName
                    )
                  )
                  .asLeft

  /** Process operations using precedence climbing
    */
  private def rewriteOps(
    lhs:                 Expr,
    terms:               List[Term],
    minPrec:             Int,
    span:                SrcSpan,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Either[NEL[SemanticError], (Expr, List[Term])] =
    terms match
      case head :: rest =>
        // Check for binary operator with sufficient precedence
        matchBinOpRef(head, resolvables).filter { case (_, _, prec, _) => prec >= minPrec } match
          case Some((ref, bnd, prec, assoc)) =>
            val resolvedRef = ref.copy(resolvedId = bnd.id)
            val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec

            rewritePrecedenceExpr(rest, nextMinPrec, span, transformedBindings, resolvables)
              .flatMap { case (rhs, remaining) =>
                // Transform operator expression to function application
                val opApp    = App(span, App(span, resolvedRef, lhs), rhs)
                val combined = Expr(span, List(opApp))
                rewriteOps(combined, remaining, minPrec, span, transformedBindings, resolvables)
              }
          case None =>
            // Check for postfix operator with sufficient precedence
            matchPostfixOpRef(head, resolvables)
              .filter { case (_, _, prec, _) => prec >= minPrec } match
              case Some((ref, bnd, _, _)) =>
                val resolvedRef = ref.copy(resolvedId = bnd.id)
                // Transform postfix operator to function application
                val opApp    = App(span, resolvedRef, lhs)
                val combined = Expr(span, List(opApp))
                rewriteOps(combined, rest, minPrec, span, transformedBindings, resolvables)
              case None =>
                // Neither binary nor postfix op with sufficient precedence
                rewriteOpsRemainder(head, lhs, terms, resolvables)
      case Nil =>
        // No more operations
        (lhs, terms).asRight

  /** Handle remaining cases in rewriteOps when no binary/postfix op matched */
  private def rewriteOpsRemainder(
    head:        Term,
    lhs:         Expr,
    terms:       List[Term],
    resolvables: ResolvablesIndex
  ): Either[NEL[SemanticError], (Expr, List[Term])] =
    head match
      case g: TermGroup =>
        NEL
          .one(
            SemanticError.DanglingTerms(
              List(g),
              "Unexpected group after expression - expected an operator",
              phaseName
            )
          )
          .asLeft
      case term if !isOperator(term, resolvables) && !canBeApplied(lhs) =>
        // Non-operator term after an expression that can't be applied to
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

  /** Build a chain of function applications recursively.
    *
    * This function handles the left-associativity of application: `f x y` -> `((f x) y)`. Each term
    * after the function becomes a single argument, applied left-to-right.
    *
    * Groups are processed as sub-expressions: `f (g x) y` -> `((f (g x)) y)`
    */
  private def buildAppChain(
    fn:                  Term,
    terms:               List[Term],
    span:                SrcSpan,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Either[NEL[SemanticError], (Expr, List[Term])] =
    terms match
      case Nil =>
        // No more arguments; wrap if undersaturated (partial application).
        wrapIfUndersaturated(fn, span, transformedBindings, resolvables).map((_, terms))
      case t :: _ if isOperator(t, resolvables) =>
        // An operator ends the application chain; wrap if undersaturated.
        wrapIfUndersaturated(fn, span, transformedBindings, resolvables).map((_, terms))
      case (g: TermGroup) :: restTerms =>
        // Groups are processed as sub-expressions (may contain nested applications)
        rewriteGroupAtom(g, transformedBindings, resolvables).flatMap { term =>
          val argExpr = Expr(g.span, List(term))
          buildSingleApp(fn, argExpr, span).flatMap { app =>
            buildAppChain(app, restTerms, span, transformedBindings, resolvables)
          }
        }
      case (ref: Ref) :: restTerms =>
        // Plain Ref as argument - don't start a new app chain, just use as argument
        val argExpr = Expr(ref.span, List(ref))
        buildSingleApp(fn, argExpr, span).flatMap { app =>
          buildAppChain(app, restTerms, span, transformedBindings, resolvables)
        }
      case IsAtom(atom) :: restTerms =>
        // Literal or other atom as argument
        val argExpr = Expr(atom.span, List(atom))
        buildSingleApp(fn, argExpr, span).flatMap { app =>
          buildAppChain(app, restTerms, span, transformedBindings, resolvables)
        }
      case other =>
        // Unexpected term - let rewriteAtom handle/report error
        rewriteAtom(other, span, transformedBindings, resolvables).flatMap {
          case (argExpr, remainingTerms) =>
            buildSingleApp(fn, argExpr, span).flatMap { app =>
              buildAppChain(app, remainingTerms, span, transformedBindings, resolvables)
            }
        }

  private def rewriteGroupAtom(
    group:               TermGroup,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Either[NEL[SemanticError], Term] =
    rewritePrecedenceExpr(
      group.inner.terms,
      MinPrecedence,
      group.span,
      transformedBindings,
      resolvables
    )
      .flatMap {
        case (_, remainingInGroup) if remainingInGroup.nonEmpty =>
          NEL
            .one(
              SemanticError.DanglingTerms(
                remainingInGroup,
                "Unexpected terms inside group",
                phaseName
              )
            )
            .asLeft
        case (innerExpr, _) if innerExpr.terms.length != 1 =>
          NEL
            .one(
              SemanticError
                .InvalidExpression(
                  innerExpr,
                  "Group must resolve to a single expression",
                  phaseName
                )
            )
            .asLeft
        case (innerExpr, _) =>
          innerExpr.terms.head.asRight
      }

  /** Build a single application node
    */
  private def buildSingleApp(
    fn:   Term,
    arg:  Expr,
    span: SrcSpan
  ): Either[NEL[SemanticError], Term] =
    fn match
      case ref:    Ref => App(span, ref, arg, typeAsc = None, typeSpec = None).asRight
      case app:    App => App(span, app, arg, typeAsc = None, typeSpec = None).asRight
      case lambda: Lambda => App(span, lambda, arg, typeAsc = None, typeSpec = None).asRight
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
      case _: Lambda => true
      case _ => false
    }

  /** Rewrite a term by recursively processing inner expressions
    */
  private def rewriteTerm(
    term:                Term,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Either[NEL[SemanticError], Term] =
    term match
      case app: App =>
        for
          newFn <- rewriteAppFn(app.fn, transformedBindings, resolvables)
          newArg <- rewriteExpr(app.arg, transformedBindings, resolvables)
        yield app.copy(fn = newFn, arg = newArg)
      case ref: Ref =>
        ref.qualifier match
          case Some(qualifier) =>
            rewriteTerm(qualifier, transformedBindings, resolvables)
              .map(updatedQualifier => ref.copy(qualifier = Some(updatedQualifier)))
          case None =>
            ref.asRight
      case inv: InvalidExpression =>
        // Report that we found an invalid expression from an earlier phase
        NEL.one(SemanticError.InvalidExpressionFound(inv, phaseName)).asLeft
      case e: Expr =>
        rewriteExpr(e, transformedBindings, resolvables)
      case lambda: Lambda =>
        // Process lambda body to rewrite operators and function applications
        rewriteExpr(lambda.body, transformedBindings, resolvables).map(newBody =>
          lambda.copy(body = newBody)
        )
      case c: Cond =>
        for
          newCond <- rewriteExpr(c.cond, transformedBindings, resolvables)
          newIfTrue <- rewriteExpr(c.ifTrue, transformedBindings, resolvables)
          newIfFalse <- rewriteExpr(c.ifFalse, transformedBindings, resolvables)
        yield c.copy(cond = newCond, ifTrue = newIfTrue, ifFalse = newIfFalse)
      case t: Tuple =>
        t.elements
          .traverse(rewriteExpr(_, transformedBindings, resolvables))
          .map(newElems => t.copy(elements = newElems))
      case tg: TermGroup =>
        rewriteExpr(tg.inner, transformedBindings, resolvables).map(newInner =>
          tg.copy(inner = newInner)
        )
      case other =>
        other.asRight

  private def rewriteAppFn(
    fn:                  Ref | App | Lambda,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Either[NEL[SemanticError], Ref | App | Lambda] =
    fn match
      case ref: Ref => rewriteTerm(ref, transformedBindings, resolvables).map(_.asInstanceOf[Ref])
      case app: App => rewriteTerm(app, transformedBindings, resolvables).map(_.asInstanceOf[App])
      case lambda: Lambda =>
        rewriteTerm(lambda, transformedBindings, resolvables).map(_.asInstanceOf[Lambda])

  /** Check if a term is an operator
    */
  private def isOperator(term: Term, resolvables: ResolvablesIndex): Boolean =
    matchOpRef(term, resolvables).isDefined

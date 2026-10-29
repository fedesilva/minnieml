package mml.mmlclib.semantic

import cats.data.{EitherT, NonEmptyList as NEL, State}
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

/** ExpressionRewriter handles expression transformations, including function applications and
  * operator precedence. It treats function application as an implicit high-precedence operator
  * (juxtaposition).
  */
object ExpressionRewriter:

  /** Rewrite a module, accumulating errors in the state. */
  def rewriteModule(state: CompilerState): CompilerState =
    val rewritten = state.module.members.traverse {
      case binding: Bnd =>
        rewriteExpr(binding.value, state.module.resolvables)
          .map(value => binding.copy(value = value): Member)
      case other => other.pure[Accumulating]
    }
    val (diagnostics, members) = rewritten.run(state.diagnostics).value
    val module                 = ResolvablesIndexer.refresh(state.module.copy(members = members))
    state.copy(diagnostics = diagnostics).withModule(module)

  private type Accumulating[A] = State[DiagnosticStore, A]
  private type Rewrite[A]      = EitherT[Accumulating, NEL[SemanticError], A]

  private def report(
    error:  SemanticError,
    causes: Vector[DiagnosticId] = Vector.empty
  ): Accumulating[Vector[DiagnosticId]] = DiagnosticStore.report(error, causes)

  /** A refused operator grouping depends on the conflict between those exact declaration IDs. Other
    * malformed terms remain independent errors, even inside the same source expression.
    */
  private def reportRewriteError(error: SemanticError): Accumulating[Vector[DiagnosticId]] =
    error match

      case SemanticError.ProtocolErrorFound(
            ProtocolError.OperatorGroupingConflict(first, other),
            _
          ) =>
        State.get[DiagnosticStore].flatMap { diagnostics =>
          val declarations = (first.id.toList ++ other.id).toSet
          val occurrences = diagnostics.records
            .filter { record =>
              record.error match
                case SemanticError.ProtocolErrorFound(
                      ProtocolError.OperatorGroupingConflict(previousFirst, previousOther),
                      _
                    ) =>
                  declarations.size == 2 &&
                  (previousFirst.id.toList ++ previousOther.id).toSet == declarations
                case _ => false
            }
            .map(_.id)
          report(error, occurrences)
        }

      case _ => report(error)

  private def failedExpr(expr: Expr, causes: Vector[DiagnosticId]): Expr =
    expr.copy(terms =
      List(
        InvalidExpression(
          expr.source,
          expr,
          typeSpec = expr.typeSpec,
          typeAsc  = expr.typeAsc,
          causes   = causes
        )
      )
    )

  @scala.annotation.tailrec
  private def directFailure(term: Term): Option[Vector[DiagnosticId]] = term match
    case invalid: InvalidExpression => invalid.causes.some
    case error:   ParseTermError => error.causes.some
    case group:   TermGroup =>
      group.inner.terms match
        case term :: Nil => directFailure(term)
        case _ => none
    case expr: Expr =>
      expr.terms match
        case term :: Nil => directFailure(term)
        case _ => none
    case _ => none

  extension [A](value: A) private def accepted: Rewrite[A] = EitherT.rightT(value)

  extension (errors: NEL[SemanticError]) private def rejected[A]: Rewrite[A] = EitherT.leftT(errors)

  private val phaseName = "mml.mmlclib.semantic.ExpressionRewriter"

  private val MinPrecedence: Int = 1

  /** Rewrite an expression using precedence climbing for both operators and function application
    */
  private def rewriteExpr(
    expr:        Expr,
    resolvables: ResolvablesIndex
  ): Accumulating[Expr] =
    expr.terms.traverse(rewriteTerm(_, resolvables)).flatMap { terms =>
      val rewritten = expr.copy(terms = terms)
      val failures  = terms.flatMap(directFailure)
      if failures.nonEmpty then
        val roots =
          if failures.forall(_.nonEmpty) then failures.flatten.distinct.toVector
          else Vector.empty
        val retained = terms match
          case (_: InvalidExpression) :: Nil => rewritten
          case _ => failedExpr(rewritten, roots)
        retained.pure[Accumulating]
      else
        val parsed = rewritePrecedenceExpr(
          terms,
          MinPrecedence,
          expr.source,
          resolvables
        ).flatMap { case (result, remaining) =>
          if remaining.isEmpty then result.accepted
          else
            NEL
              .one(
                SemanticError.DanglingTerms(
                  remaining,
                  "Unexpected terms outside expression context",
                  phaseName
                )
              )
              .rejected
        }
        parsed.value.flatMap {
          case Right(result) =>
            val contents = if result.typeAsc.isDefined then List(result) else result.terms
            expr.copy(terms = contents).pure[Accumulating]
          case Left(errors) =>
            errors.toList.traverse(reportRewriteError).map { roots =>
              failedExpr(rewritten, roots.flatten.distinct.toVector)
            }
        }
    }

  /** Rewrite an expression using precedence climbing
    */
  private def rewritePrecedenceExpr(
    terms:       List[Term],
    minPrec:     Int,
    source:      SourceOrigin,
    resolvables: ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    for
      (lhs, restAfterAtom) <-
        rewriteAtom(terms, source, resolvables)

      // Rewrite any operations with sufficient precedence
      result <- rewriteOps(
        lhs,
        restAfterAtom,
        minPrec,
        source,
        resolvables
      )
    yield result

  /** Rewrite the first atom in a list of terms
    */
  private def rewriteAtom(
    terms:       List[Term],
    source:      SourceOrigin,
    resolvables: ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    terms match
      case Nil =>
        NEL
          .one(
            SemanticError.InvalidExpression(
              Expr(source, Nil),
              "Expected an expression, got empty terms",
              phaseName
            )
          )
          .rejected

      case (g: TermGroup) :: rest =>
        rewriteGroupAtom(g).flatMap { term =>
          buildHeadAppChain(term, rest, g.source, resolvables)
        }

      case (head :: rest) =>
        val prefix = OperatorGrouping.prefix(head, resolvables).filter(_ => rest.nonEmpty)
        val conflict = Option
          .when(rest.nonEmpty)(
            OperatorGrouping.conflict(head, CallableArity.Unary, resolvables)
          )
          .flatten
        (prefix, conflict) match
          case (Some(grouped), _) =>
            // Prefix unary operator (like +, -, etc.)
            val nextMinPrec =
              if grouped.associativity == Associativity.Left then grouped.precedence + 1
              else grouped.precedence

            rewritePrecedenceExpr(
              rest,
              nextMinPrec,
              source,
              resolvables
            ).map { case (operand, remaining) =>
              (Expr(source, List(App(source, grouped.ref, operand))), remaining)
            }
          case (None, Some(error)) =>
            NEL.one(SemanticError.ProtocolErrorFound(error, phaseName)).rejected

          case (None, None) =>
            // Not a prefix operator - check for ref, atom, or error
            head match
              case callable @ (_: Ref | _: Lambda) =>
                buildHeadAppChain(
                  callable,
                  rest,
                  source,
                  resolvables
                )
              case IsAtom(atom) =>
                // Simple atom (literal, hole, etc.)
                (Expr(atom.source, List(atom)), rest).accepted
              case _ =>
                // Invalid expression structure
                NEL
                  .one(
                    SemanticError.InvalidExpression(
                      Expr(source, head :: rest),
                      "Invalid expression structure",
                      phaseName
                    )
                  )
                  .rejected

  /** Process operations using precedence climbing
    */
  private def rewriteOps(
    lhs:         Expr,
    terms:       List[Term],
    minPrec:     Int,
    source:      SourceOrigin,
    resolvables: ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    terms match
      case head :: rest =>
        // Check for binary operator with sufficient precedence
        val binary = OperatorGrouping.binary(head, resolvables).filter { grouped =>
          grouped.precedence >= minPrec &&
          (OperatorGrouping.postfix(head, resolvables).isEmpty ||
            rest.headOption.exists(canStartOperand(_, resolvables)))
        }
        binary match
          case Some(grouped) =>
            val nextMinPrec =
              if grouped.associativity == Associativity.Left then grouped.precedence + 1
              else grouped.precedence

            rewritePrecedenceExpr(
              rest,
              nextMinPrec,
              source,
              resolvables
            ).flatMap { case (rhs, remaining) =>
              // Transform operator expression to function application
              val opApp    = App(source, App(source, grouped.ref, lhs), rhs)
              val combined = Expr(source, List(opApp))
              rewriteOps(
                combined,
                remaining,
                minPrec,
                source,
                resolvables
              )
            }
          case None =>
            // Check for postfix operator with sufficient precedence
            OperatorGrouping
              .postfix(head, resolvables)
              .filter(_.precedence >= minPrec) match
              case Some(grouped) =>
                // Transform postfix operator to function application
                val opApp    = App(source, grouped.ref, lhs)
                val combined = Expr(source, List(opApp))
                rewriteOps(combined, rest, minPrec, source, resolvables)
              case None =>
                // Neither binary nor postfix op with sufficient precedence
                rewriteOpsRemainder(head, lhs, terms, resolvables)
      case Nil =>
        // No more operations
        (lhs, terms).accepted

  /** In 1 ! * 2, ! can be postfix when * cannot begin its right operand. An operator that also has
    * prefix syntax can begin an operand, so 1 ! -2 keeps the binary interpretation of !.
    */
  private def canStartOperand(term: Term, index: ResolvablesIndex): Boolean =
    !OperatorGrouping.isOperator(term, index) || OperatorGrouping.prefix(term, index).nonEmpty

  /** Handle remaining cases in rewriteOps when no binary/postfix op matched */
  private def rewriteOpsRemainder(
    head:        Term,
    lhs:         Expr,
    terms:       List[Term],
    resolvables: ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
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
          .rejected
      case term if !OperatorGrouping.isOperator(term, resolvables) && !canBeApplied(lhs) =>
        // Non-operator term after an expression that can't be applied to
        NEL
          .one(
            SemanticError.DanglingTerms(
              List(term),
              "Unexpected term after expression - expected an operator",
              phaseName
            )
          )
          .rejected
      case ref: Ref =>
        val conflict = OperatorGrouping
          .conflict(ref, CallableArity.Binary, resolvables)
          .orElse(OperatorGrouping.conflict(ref, CallableArity.Unary, resolvables))
        conflict match
          case Some(error) =>
            NEL.one(SemanticError.ProtocolErrorFound(error, phaseName)).rejected
          case None => (lhs, terms).accepted
      case _ =>
        // No more operations with sufficient precedence
        (lhs, terms).accepted

  /** Normalize a source callee once before appending its applications. */
  private def buildHeadAppChain(
    fn:          Term,
    terms:       List[Term],
    source:      SourceOrigin,
    resolvables: ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    callableValue(fn) match
      case (lambda: Lambda, Nil) =>
        buildDirectLambdaAppChain(lambda, terms, source, resolvables)
      case _ =>
        buildAppChain(fn, terms, source, resolvables)

  /** Build a chain of function applications recursively.
    *
    * This function handles the left-associativity of application: `f x y` -> `((f x) y)`. Each term
    * after the function becomes a single argument, applied left-to-right.
    *
    * Groups are processed as sub-expressions: `f (g x) y` -> `((f (g x)) y)`
    */
  private def buildAppChain(
    fn:          Term,
    terms:       List[Term],
    source:      SourceOrigin,
    resolvables: ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    terms match
      case Nil =>
        (Expr(fn.source, List(fn)), terms).accepted
      case t :: _ if OperatorGrouping.isOperator(t, resolvables) =>
        (Expr(fn.source, List(fn)), terms).accepted
      case (g: TermGroup) :: restTerms =>
        // Groups are processed as sub-expressions (may contain nested applications)
        rewriteGroupAtom(g).flatMap { term =>
          val argExpr = Expr(g.source, List(term))
          buildSingleApp(fn, argExpr, source).flatMap { app =>
            buildAppChain(app, restTerms, source, resolvables)
          }
        }
      case (callable @ (_: Ref | _: Lambda)) :: restTerms =>
        val argExpr = Expr(callable.source, List(callable))
        buildSingleApp(fn, argExpr, source).flatMap { app =>
          buildAppChain(app, restTerms, source, resolvables)
        }
      case IsAtom(atom) :: restTerms =>
        // Literal or other atom as argument
        val argExpr = Expr(atom.source, List(atom))
        buildSingleApp(fn, argExpr, source).flatMap { app =>
          buildAppChain(app, restTerms, source, resolvables)
        }
      case other =>
        // Unexpected term - let rewriteAtom handle/report error
        rewriteAtom(other, source, resolvables).flatMap { case (argExpr, remainingTerms) =>
          buildSingleApp(fn, argExpr, source).flatMap { app =>
            buildAppChain(app, remainingTerms, source, resolvables)
          }
        }

  private def buildDirectLambdaAppChain(
    lambda:      Lambda,
    terms:       List[Term],
    source:      SourceOrigin,
    resolvables: ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    consumeDirectLambdaArgs(
      lambda.params.length,
      terms,
      resolvables
    ).flatMap { case (args, remainingTerms) =>
      val lowered = DirectLambdaApplication.lower(lambda, args, source)
      remainingTerms match
        case Nil =>
          (Expr(source, List(lowered)), remainingTerms).accepted
        case head :: _ if OperatorGrouping.isOperator(head, resolvables) =>
          (Expr(source, List(lowered)), remainingTerms).accepted
        case _ =>
          buildAppChain(
            lowered,
            remainingTerms,
            source,
            resolvables
          )
    }

  private def consumeDirectLambdaArgs(
    remainingParamCount: Int,
    terms:               List[Term],
    resolvables:         ResolvablesIndex
  ): Rewrite[(List[Expr], List[Term])] =
    if remainingParamCount == 0 then (Nil, terms).accepted
    else
      terms match
        case Nil =>
          (Nil, terms).accepted
        case head :: _ if OperatorGrouping.isOperator(head, resolvables) =>
          (Nil, terms).accepted
        case _ =>
          rewriteDirectLambdaArg(terms, resolvables).flatMap { case (arg, rest) =>
            consumeDirectLambdaArgs(
              remainingParamCount - 1,
              rest,
              resolvables
            ).map { case (args, remainingTerms) =>
              (arg :: args, remainingTerms)
            }
          }

  private def rewriteDirectLambdaArg(
    terms:       List[Term],
    resolvables: ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    terms match
      case (group: TermGroup) :: restTerms =>
        rewriteGroupAtom(group)
          .map(term => (Expr(group.source, List(term)), restTerms))
      case (callable @ (_: Ref | _: Lambda)) :: restTerms =>
        (Expr(callable.source, List(callable)), restTerms).accepted
      case IsAtom(atom) :: restTerms =>
        (Expr(atom.source, List(atom)), restTerms).accepted
      case other =>
        rewriteAtom(other, other.head.source, resolvables)

  private def rewriteGroupAtom(group: TermGroup): Rewrite[Term] =
    group.inner.terms match
      case term :: Nil =>
        val inner = if group.inner.typeAsc.isDefined then group.inner else term
        group.typeAsc
          .fold(inner) { annotation =>
            Expr(group.source, List(inner), typeAsc = annotation.some, typeSpec = group.typeSpec)
          }
          .accepted
      case _ =>
        NEL
          .one(
            SemanticError.InvalidExpression(
              group.inner,
              "Group must resolve to a single expression",
              phaseName
            )
          )
          .rejected

  /** Build a single application node
    */
  private def buildSingleApp(
    fn:     Term,
    arg:    Expr,
    source: SourceOrigin
  ): Rewrite[Term] =
    callableValue(fn) match
      case (callee: Callee, annotations) =>
        App(source, callee, arg, calleeAscriptions = annotations).accepted
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
          .rejected

  /** Value annotations become constraints on the callee at the next application boundary. */
  private def callableValue(term: Term): (Term, List[Type]) = term match
    case expression: Expr =>
      expression.terms match
        case List(inner) =>
          val (value, annotations) = callableValue(inner)
          (value, annotations ++ expression.typeAsc)
        case _ => (expression, Nil)
    case other => (other, Nil)

  /** Check if an expression can have arguments applied to it. */
  private def canBeApplied(expr: Expr): Boolean =
    callableValue(expr)._1.isInstanceOf[Callee]

  /** Rewrite a term by recursively processing inner expressions
    */
  private def rewriteTerm(
    term:        Term,
    resolvables: ResolvablesIndex
  ): Accumulating[Term] =
    term match
      case app: App => rewriteApp(app, resolvables).widen

      case ref: Ref => rewriteRef(ref, resolvables).widen

      case invalid: InvalidExpression =>
        for
          terms <- invalid.originalExpr.terms.traverse(
            rewriteTerm(_, resolvables)
          )
          roots <- report(SemanticError.InvalidExpressionFound(invalid, phaseName), invalid.causes)
        yield invalid.copy(originalExpr = invalid.originalExpr.copy(terms = terms), causes = roots)

      case expr: Expr => rewriteExpr(expr, resolvables).widen

      case lambda: Lambda => rewriteLambda(lambda, resolvables).widen

      case cond: Cond =>
        for
          predicate <- rewriteExpr(cond.cond, resolvables)
          ifTrue <- rewriteExpr(cond.ifTrue, resolvables)
          ifFalse <- rewriteExpr(cond.ifFalse, resolvables)
        yield cond.copy(cond = predicate, ifTrue = ifTrue, ifFalse = ifFalse)

      case tuple: Tuple =>
        tuple.elements
          .traverse(rewriteExpr(_, resolvables))
          .map(elements => tuple.copy(elements = elements))

      case group: TermGroup =>
        rewriteExpr(group.inner, resolvables)
          .map(inner => group.copy(inner = inner))

      case other => other.pure[Accumulating]

  private def rewriteRef(
    ref:         Ref,
    resolvables: ResolvablesIndex
  ): Accumulating[Ref] =
    ref.qualifier
      .traverse(rewriteTerm(_, resolvables))
      .map(qualifier => ref.copy(qualifier = qualifier))

  private def rewriteApp(
    app:         App,
    resolvables: ResolvablesIndex
  ): Accumulating[App] =
    for
      fn <- rewriteAppFn(app.fn, resolvables)
      arg <- rewriteExpr(app.arg, resolvables)
    yield app.copy(fn = fn, arg = arg)

  private def rewriteLambda(
    lambda:      Lambda,
    resolvables: ResolvablesIndex
  ): Accumulating[Lambda] =
    rewriteExpr(lambda.body, resolvables).map(body => lambda.copy(body = body))

  private def rewriteAppFn(
    fn:          Callee,
    resolvables: ResolvablesIndex
  ): Accumulating[Callee] =
    fn match
      case ref:    Ref => rewriteRef(ref, resolvables).widen
      case app:    App => rewriteApp(app, resolvables).widen
      case lambda: Lambda => rewriteLambda(lambda, resolvables).widen

package mml.mmlclib.semantic

import cats.data.{EitherT, NonEmptyList as NEL, State}
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

import BindingIds.Allocation

/** ExpressionRewriter handles expression transformations, including function applications and
  * operator precedence. It treats function application as an implicit high-precedence operator
  * (juxtaposition).
  */
object ExpressionRewriter:

  private case class RewriteState(bindingIds: BindingIdSupply, diagnostics: DiagnosticStore)

  private type Accumulating[A] = State[RewriteState, A]
  private type Rewrite[A]      = EitherT[Accumulating, NEL[SemanticError], A]

  private def allocate[A](operation: Allocation[A]): Accumulating[A] = State { current =>
    val (bindingIds, result) = operation.run(current.bindingIds).value
    (current.copy(bindingIds = bindingIds), result)
  }

  private def report(
    error:  SemanticError,
    causes: Vector[DiagnosticId] = Vector.empty
  ): Accumulating[Vector[DiagnosticId]] = State { current =>
    val (diagnostics, roots) = current.diagnostics.emit(error, causes)
    (current.copy(diagnostics = diagnostics), roots)
  }

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

  private case class RewrittenMembers(
    resolvables:         ResolvablesIndex,
    state:               RewriteState,
    members:             List[Member]     = Nil,
    transformedBindings: Map[String, Bnd] = Map.empty
  )

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
      val bndOpt = ref.resolvedId
        .flatMap(resolvables.lookup)
        .collect { case bnd: Bnd =>
          transformedBindings.get(bnd.name).filter(_.id == bnd.id).getOrElse(bnd)
        }
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
    source:              SourceOrigin,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[Expr] =
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
            if appliedCount > 0 then Some(Expr(source, List(fn)).accepted)
            else Some(etaExpand(callable, remainingParams, source, owner))
          else None
        }
      }
      .getOrElse(Expr(fn.source, List(fn)).accepted)

  private def etaExpand(
    callable: Ref | App,
    params:   List[FnParam],
    source:   SourceOrigin,
    owner:    BindingOwner
  ): Rewrite[Expr] =
    val allocation = params.zipWithIndex.traverse { (param, ordinal) =>
      val template = param.copy(source = SourceOrigin.Synth, nameNode = Name.synth(s"$$p$ordinal"))
      LocalBindings.fresh(template, owner, "eta")
    }
    EitherT.liftF(allocate(allocation)).map { syntheticLocals =>
      val syntheticParams = syntheticLocals.map(_.param)
      val syntheticRefs   = syntheticLocals.map(_.ref)
      // Build App chain with synthetic args
      val fullApp = syntheticRefs.foldLeft[Ref | App](callable) { (acc, ref) =>
        App(source, acc, Expr(source, List(ref)))
      }
      val lambda =
        Lambda(source, syntheticParams, Expr(source, List(fullApp)), captures = Nil)
      Expr(source, List(lambda))
    }

  /** Rewrite a module, accumulating errors in the state. */
  def rewriteModule(state: CompilerState): CompilerState =
    val initial = RewrittenMembers(
      state.module.resolvables,
      RewriteState(state.bindingIds.include(state.module), state.diagnostics)
    )
    val result = state.module.members.foldLeft(initial) { (current, member) =>
      member match
        case binding: Bnd =>
          val owner = BindingOwner.binding(state.module.name, binding.name)
          val (nextState, value) =
            rewriteExpr(binding.value, owner, current.transformedBindings, current.resolvables)
              .run(current.state)
              .value
          val updated = binding.copy(value = value)
          current.copy(
            members             = current.members :+ updated,
            transformedBindings = current.transformedBindings.updated(binding.name, updated),
            resolvables         = current.resolvables.updated(updated),
            state               = nextState
          )
        case other => current.copy(members = current.members :+ other)
    }
    val module = ResolvablesIndexer.refresh(state.module.copy(members = result.members))
    state
      .copy(
        bindingIds  = result.state.bindingIds,
        diagnostics = result.state.diagnostics
      )
      .withModule(module)

  /** Rewrite an expression using precedence climbing for both operators and function application
    */
  private def rewriteExpr(
    expr:                Expr,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Accumulating[Expr] =
    expr.terms.traverse(rewriteTerm(_, owner, transformedBindings, resolvables)).flatMap { terms =>
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
          owner,
          transformedBindings,
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
          case Right(result) => result.pure[Accumulating]
          case Left(errors) =>
            errors.toList.traverse(report(_)).map { roots =>
              failedExpr(rewritten, roots.flatten.distinct.toVector)
            }
        }
    }

  /** Rewrite an expression using precedence climbing
    */
  private def rewritePrecedenceExpr(
    terms:               List[Term],
    minPrec:             Int,
    source:              SourceOrigin,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    for
      (lhs, restAfterAtom) <-
        rewriteAtom(terms, source, owner, transformedBindings, resolvables)

      // Rewrite any operations with sufficient precedence
      result <- rewriteOps(
        lhs,
        restAfterAtom,
        minPrec,
        source,
        owner,
        transformedBindings,
        resolvables
      )
    yield result

  /** Rewrite the first atom in a list of terms
    */
  private def rewriteAtom(
    terms:               List[Term],
    source:              SourceOrigin,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
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
          buildHeadAppChain(term, rest, g.source, owner, transformedBindings, resolvables)
        }

      case (head :: rest) =>
        matchPrefixOpRef(head, resolvables) match
          case Some((ref, bnd, prec, assoc)) =>
            // Prefix unary operator (like +, -, etc.)
            val resolvedRef = ref.copy(resolvedId = bnd.id)
            val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec

            rewritePrecedenceExpr(
              rest,
              nextMinPrec,
              source,
              owner,
              transformedBindings,
              resolvables
            ).map { case (operand, remaining) =>
              // Transform prefix operator to function application
              val value = operand.terms match
                case (lit: LiteralInt) :: Nil
                    if bnd.id.contains(s"stdlib::bnd::${OpMangling.mangleOp("-", 1)}") &&
                      lit.value == 2147483648L =>
                  lit.copy(source = source, value = Int.MinValue.toLong)
                case _ => App(source, resolvedRef, operand)
              (Expr(source, List(value)), remaining)
            }
          case None =>
            // Not a prefix operator - check for ref, atom, or error
            head match
              case callable @ (_: Ref | _: Lambda) =>
                buildHeadAppChain(
                  callable,
                  rest,
                  source,
                  owner,
                  transformedBindings,
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
    lhs:                 Expr,
    terms:               List[Term],
    minPrec:             Int,
    source:              SourceOrigin,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    terms match
      case head :: rest =>
        // Check for binary operator with sufficient precedence
        matchBinOpRef(head, resolvables).filter { case (_, _, prec, _) => prec >= minPrec } match
          case Some((ref, bnd, prec, assoc)) =>
            val resolvedRef = ref.copy(resolvedId = bnd.id)
            val nextMinPrec = if assoc == Associativity.Left then prec + 1 else prec

            rewritePrecedenceExpr(
              rest,
              nextMinPrec,
              source,
              owner,
              transformedBindings,
              resolvables
            ).flatMap { case (rhs, remaining) =>
              // Transform operator expression to function application
              val opApp    = App(source, App(source, resolvedRef, lhs), rhs)
              val combined = Expr(source, List(opApp))
              rewriteOps(
                combined,
                remaining,
                minPrec,
                source,
                owner,
                transformedBindings,
                resolvables
              )
            }
          case None =>
            // Check for postfix operator with sufficient precedence
            matchPostfixOpRef(head, resolvables)
              .filter { case (_, _, prec, _) => prec >= minPrec } match
              case Some((ref, bnd, _, _)) =>
                val resolvedRef = ref.copy(resolvedId = bnd.id)
                // Transform postfix operator to function application
                val opApp    = App(source, resolvedRef, lhs)
                val combined = Expr(source, List(opApp))
                rewriteOps(combined, rest, minPrec, source, owner, transformedBindings, resolvables)
              case None =>
                // Neither binary nor postfix op with sufficient precedence
                rewriteOpsRemainder(head, lhs, terms, resolvables)
      case Nil =>
        // No more operations
        (lhs, terms).accepted

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
          .rejected
      case _ =>
        // No more operations with sufficient precedence
        (lhs, terms).accepted

  /** Normalize a source callee once before appending its applications. */
  private def buildHeadAppChain(
    fn:                  Term,
    terms:               List[Term],
    source:              SourceOrigin,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    fn match
      case lambda: Lambda =>
        buildDirectLambdaAppChain(lambda, terms, source, owner, transformedBindings, resolvables)
      case _ =>
        buildAppChain(fn, terms, source, owner, transformedBindings, resolvables)

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
    source:              SourceOrigin,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    terms match
      case Nil =>
        // No more arguments; wrap if undersaturated (partial application).
        wrapIfUndersaturated(fn, source, owner, transformedBindings, resolvables).map((_, terms))
      case t :: _ if isOperator(t, resolvables) =>
        // An operator ends the application chain; wrap if undersaturated.
        wrapIfUndersaturated(fn, source, owner, transformedBindings, resolvables).map((_, terms))
      case (g: TermGroup) :: restTerms =>
        // Groups are processed as sub-expressions (may contain nested applications)
        rewriteGroupAtom(g).flatMap { term =>
          val argExpr = Expr(g.source, List(term))
          buildSingleApp(fn, argExpr, source).flatMap { app =>
            buildAppChain(app, restTerms, source, owner, transformedBindings, resolvables)
          }
        }
      case (ref: Ref) :: restTerms =>
        // Bare callable refs in argument position still need eta-expansion so higher-order uses
        // like `apply inc` lower as first-class function values rather than raw symbols.
        wrapIfUndersaturated(ref, ref.source, owner, transformedBindings, resolvables).flatMap {
          argExpr =>
            buildSingleApp(fn, argExpr, source).flatMap { app =>
              buildAppChain(app, restTerms, source, owner, transformedBindings, resolvables)
            }
        }
      case (lambda: Lambda) :: restTerms =>
        val argExpr = Expr(lambda.source, List(lambda))
        buildSingleApp(fn, argExpr, source).flatMap { app =>
          buildAppChain(app, restTerms, source, owner, transformedBindings, resolvables)
        }
      case IsAtom(atom) :: restTerms =>
        // Literal or other atom as argument
        val argExpr = Expr(atom.source, List(atom))
        buildSingleApp(fn, argExpr, source).flatMap { app =>
          buildAppChain(app, restTerms, source, owner, transformedBindings, resolvables)
        }
      case other =>
        // Unexpected term - let rewriteAtom handle/report error
        rewriteAtom(other, source, owner, transformedBindings, resolvables).flatMap {
          case (argExpr, remainingTerms) =>
            buildSingleApp(fn, argExpr, source).flatMap { app =>
              buildAppChain(app, remainingTerms, source, owner, transformedBindings, resolvables)
            }
        }

  private def buildDirectLambdaAppChain(
    lambda:              Lambda,
    terms:               List[Term],
    source:              SourceOrigin,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    consumeDirectLambdaArgs(
      lambda.params.length,
      terms,
      owner,
      transformedBindings,
      resolvables
    ).flatMap { case (args, remainingTerms) =>
      val lowered = lowerDirectLambda(lambda, args, source)
      remainingTerms match
        case Nil =>
          (Expr(source, List(lowered)), remainingTerms).accepted
        case head :: _ if isOperator(head, resolvables) =>
          (Expr(source, List(lowered)), remainingTerms).accepted
        case _ =>
          buildAppChain(
            lowered,
            remainingTerms,
            source,
            owner,
            transformedBindings,
            resolvables
          )
    }

  private def consumeDirectLambdaArgs(
    remainingParamCount: Int,
    terms:               List[Term],
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[(List[Expr], List[Term])] =
    if remainingParamCount == 0 then (Nil, terms).accepted
    else
      terms match
        case Nil =>
          (Nil, terms).accepted
        case head :: _ if isOperator(head, resolvables) =>
          (Nil, terms).accepted
        case _ =>
          rewriteDirectLambdaArg(terms, owner, transformedBindings, resolvables).flatMap {
            case (arg, rest) =>
              consumeDirectLambdaArgs(
                remainingParamCount - 1,
                rest,
                owner,
                transformedBindings,
                resolvables
              ).map { case (args, remainingTerms) =>
                (arg :: args, remainingTerms)
              }
          }

  private def rewriteDirectLambdaArg(
    terms:               List[Term],
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[(Expr, List[Term])] =
    terms match
      case (group: TermGroup) :: restTerms =>
        rewriteGroupAtom(group)
          .map(term => (Expr(group.source, List(term)), restTerms))
      case (ref: Ref) :: restTerms =>
        wrapIfUndersaturated(ref, ref.source, owner, transformedBindings, resolvables)
          .map(argExpr => (argExpr, restTerms))
      case (lambda: Lambda) :: restTerms =>
        (Expr(lambda.source, List(lambda)), restTerms).accepted
      case IsAtom(atom) :: restTerms =>
        (Expr(atom.source, List(atom)), restTerms).accepted
      case other =>
        rewriteAtom(other, other.head.source, owner, transformedBindings, resolvables)

  private def lowerDirectLambda(
    lambda: Lambda,
    args:   List[Expr],
    source: SourceOrigin
  ): Term =
    val paramArgs       = lambda.params.zip(args)
    val remainingParams = lambda.params.drop(args.length)

    paramArgs match
      case Nil =>
        lambda
      case _ if remainingParams.nonEmpty =>
        val residualLambda = lambda.copy(
          params   = remainingParams,
          captures = Nil,
          typeSpec = None,
          meta = lambda.meta
            .getOrElse(LambdaMeta())
            .copy(
              isPartialApplication = true,
              transferredCaptures =
                paramArgs.collect { case (p, _) if p.consuming => p.id }.flatten.toSet,
              borrowedCaptures =
                paramArgs.collect { case (p, _) if !p.consuming => p.id }.flatten.toSet
            )
            .some
        )
        wrapDirectLambdaParamApps(paramArgs, residualLambda, source)
      case _ =>
        val outerParamArgs       = paramArgs.init
        val (lastParam, lastArg) = paramArgs.last
        val bodyLambda = lambda.copy(
          params   = List(lastParam),
          captures = Nil,
          typeSpec = None,
          meta     = lambda.meta.getOrElse(LambdaMeta()).copy(isInvocationParameter = true).some
        )
        val appliedBodyLambda = App(source, bodyLambda, lastArg)
        wrapDirectLambdaParamApps(outerParamArgs, appliedBodyLambda, source, invocation = true)

  private def wrapDirectLambdaParamApps(
    paramArgs:  List[(FnParam, Expr)],
    inner:      Term,
    source:     SourceOrigin,
    invocation: Boolean = false
  ): Term =
    paramArgs.foldRight(inner) { case ((param, arg), bodyTerm) =>
      val bodyExpr = Expr(source, List(bodyTerm), typeSpec = bodyTerm.typeSpec)
      val applied  = LocalBindings.bind(param, arg, bodyExpr, source)
      applied.fn match
        case scope: Lambda if invocation =>
          applied.copy(fn = scope.copy(meta = LambdaMeta(isInvocationParameter = true).some))
        case _ => applied
    }

  private def rewriteGroupAtom(group: TermGroup): Rewrite[Term] =
    group.inner.terms match
      case term :: Nil => term.accepted
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
    fn match
      case ref:    Ref => App(source, ref, arg, typeAsc = None, typeSpec = None).accepted
      case app:    App => App(source, app, arg, typeAsc = None, typeSpec = None).accepted
      case lambda: Lambda => App(source, lambda, arg, typeAsc = None, typeSpec = None).accepted
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
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Accumulating[Term] =
    term match
      case app: App => rewriteApp(app, owner, transformedBindings, resolvables).widen

      case ref: Ref => rewriteRef(ref, owner, transformedBindings, resolvables).widen

      case invalid: InvalidExpression =>
        for
          terms <- invalid.originalExpr.terms.traverse(
            rewriteTerm(_, owner, transformedBindings, resolvables)
          )
          roots <- report(SemanticError.InvalidExpressionFound(invalid, phaseName), invalid.causes)
        yield invalid.copy(originalExpr = invalid.originalExpr.copy(terms = terms), causes = roots)

      case expr: Expr => rewriteExpr(expr, owner, transformedBindings, resolvables).widen

      case lambda: Lambda => rewriteLambda(lambda, owner, transformedBindings, resolvables).widen

      case cond: Cond =>
        for
          predicate <- rewriteExpr(cond.cond, owner, transformedBindings, resolvables)
          ifTrue <- rewriteExpr(cond.ifTrue, owner, transformedBindings, resolvables)
          ifFalse <- rewriteExpr(cond.ifFalse, owner, transformedBindings, resolvables)
        yield cond.copy(cond = predicate, ifTrue = ifTrue, ifFalse = ifFalse)

      case tuple: Tuple =>
        tuple.elements
          .traverse(rewriteExpr(_, owner, transformedBindings, resolvables))
          .map(elements => tuple.copy(elements = elements))

      case group: TermGroup =>
        rewriteExpr(group.inner, owner, transformedBindings, resolvables)
          .map(inner => group.copy(inner = inner))

      case other => other.pure[Accumulating]

  private def rewriteRef(
    ref:                 Ref,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Accumulating[Ref] =
    ref.qualifier
      .traverse(rewriteTerm(_, owner, transformedBindings, resolvables))
      .map(qualifier => ref.copy(qualifier = qualifier))

  private def rewriteApp(
    app:                 App,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Accumulating[App] =
    for
      fn <- rewriteAppFn(app.fn, owner, transformedBindings, resolvables)
      arg <- rewriteExpr(app.arg, owner, transformedBindings, resolvables)
    yield app.copy(fn = fn, arg = arg)

  private def rewriteLambda(
    lambda:              Lambda,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Accumulating[Lambda] =
    for
      nested <- allocate(BindingIds.within(lambda.params, owner))
      body <- rewriteExpr(lambda.body, nested, transformedBindings, resolvables)
    yield lambda.copy(body = body)

  private def rewriteAppFn(
    fn:                  Callee,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Accumulating[Callee] =
    fn match
      case ref:    Ref => rewriteRef(ref, owner, transformedBindings, resolvables).widen
      case app:    App => rewriteApp(app, owner, transformedBindings, resolvables).widen
      case lambda: Lambda => rewriteLambda(lambda, owner, transformedBindings, resolvables).widen

  /** Check if a term is an operator
    */
  private def isOperator(term: Term, resolvables: ResolvablesIndex): Boolean =
    matchOpRef(term, resolvables).isDefined

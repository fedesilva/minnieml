package mml.mmlclib.semantic

import cats.data.{EitherT, NonEmptyList as NEL}
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import BindingIds.Allocation

/** ExpressionRewriter handles expression transformations, including function applications and
  * operator precedence. It treats function application as an implicit high-precedence operator
  * (juxtaposition).
  */
object ExpressionRewriter:

  private type Rewrite[A] = EitherT[Allocation, NEL[SemanticError], A]

  extension [A](value: A) private def accepted: Rewrite[A] = EitherT.rightT(value)

  extension (errors: NEL[SemanticError]) private def rejected[A]: Rewrite[A] = EitherT.leftT(errors)

  private case class RewrittenMembers(
    resolvables:         ResolvablesIndex,
    bindingIds:          BindingIdSupply,
    errors:              List[SemanticError] = Nil,
    members:             List[Member]        = Nil,
    transformedBindings: Map[String, Bnd]    = Map.empty
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
    EitherT.liftF(allocation).map { syntheticLocals =>
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
    val initial = RewrittenMembers(state.module.resolvables, state.bindingIds.include(state.module))
    val result = state.module.members.foldLeft(initial) { (current, member) =>
      member match
        case bnd: Bnd =>
          val owner = BindingOwner.binding(state.module.name, bnd.name)
          val (nextIds, rewritten) =
            rewriteExpr(bnd.value, owner, current.transformedBindings, current.resolvables).value
              .run(current.bindingIds)
              .value
          rewritten match
            case Right(updatedExpr) =>
              val updatedBnd = bnd.copy(value = updatedExpr)
              current.copy(
                members             = current.members :+ updatedBnd,
                transformedBindings = current.transformedBindings + (bnd.name -> updatedBnd),
                resolvables         = current.resolvables.updated(updatedBnd),
                bindingIds          = nextIds
              )
            case Left(errs) =>
              current.copy(
                errors     = current.errors ++ errs.toList,
                members    = current.members :+ bnd,
                bindingIds = nextIds
              )
        case other =>
          current.copy(members = current.members :+ other)
    }
    state
      .copy(bindingIds = result.bindingIds)
      .addErrors(result.errors)
      .withModule(state.module.copy(members = result.members, resolvables = result.resolvables))

  /** Rewrite an expression using precedence climbing for both operators and function application
    */
  private def rewriteExpr(
    expr:                Expr,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[Expr] =
    rewritePrecedenceExpr(
      expr.terms,
      MinPrecedence,
      expr.source,
      owner,
      transformedBindings,
      resolvables
    )
      .flatMap { case (result, remaining) =>
        if remaining.isEmpty then
          // All terms processed successfully
          result.accepted
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
            .rejected
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
      // First, rewrite any inner expressions in each term
      rewrittenTerms <- terms.traverse(rewriteTerm(_, owner, transformedBindings, resolvables))

      // Rewrite the first atom in the expression
      (lhs, restAfterAtom) <-
        rewriteAtom(rewrittenTerms, source, owner, transformedBindings, resolvables)

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
        rewriteGroupAtom(g, owner, transformedBindings, resolvables).flatMap { term =>
          buildAppChain(term, rest, g.source, owner, transformedBindings, resolvables)
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
              val opApp = App(source, resolvedRef, operand)
              (Expr(source, List(opApp)), remaining)
            }
          case None =>
            // Not a prefix operator - check for ref, atom, or error
            head match
              case ref: Ref =>
                // Any reference - handle as potential function application
                buildAppChain(ref, rest, source, owner, transformedBindings, resolvables)
              case lambda: Lambda =>
                // Direct lambda-head calls lower to nested unary immediate-lambda apps so the
                // regular application/type-checking path owns the remaining semantics.
                buildDirectLambdaAppChain(
                  lambda,
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
        rewriteGroupAtom(g, owner, transformedBindings, resolvables).flatMap { term =>
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
        rewriteGroupAtom(group, owner, transformedBindings, resolvables)
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
          typeSpec = None
        )
        val appliedBodyLambda = App(source, bodyLambda, lastArg)
        wrapDirectLambdaParamApps(outerParamArgs, appliedBodyLambda, source)

  private def wrapDirectLambdaParamApps(
    paramArgs: List[(FnParam, Expr)],
    inner:     Term,
    source:    SourceOrigin
  ): Term =
    paramArgs.foldRight(inner) { case ((param, arg), bodyTerm) =>
      val bodyExpr = Expr(source, List(bodyTerm), typeSpec = bodyTerm.typeSpec)
      LocalBindings.bind(param, arg, bodyExpr, source)
    }

  private def rewriteGroupAtom(
    group:               TermGroup,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[Term] =
    rewritePrecedenceExpr(
      group.inner.terms,
      MinPrecedence,
      group.source,
      owner,
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
            .rejected
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
            .rejected
        case (innerExpr, _) =>
          innerExpr.terms.head.accepted
      }

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
  ): Rewrite[Term] =
    term match
      case app: App =>
        for
          newFn <- rewriteAppFn(app.fn, owner, transformedBindings, resolvables)
          newArg <- rewriteExpr(app.arg, owner, transformedBindings, resolvables)
        yield app.copy(fn = newFn, arg = newArg)
      case ref: Ref =>
        ref.qualifier match
          case Some(qualifier) =>
            rewriteTerm(qualifier, owner, transformedBindings, resolvables)
              .map(updatedQualifier => ref.copy(qualifier = Some(updatedQualifier)))
          case None =>
            ref.accepted
      case inv: InvalidExpression =>
        // Report that we found an invalid expression from an earlier phase
        NEL.one(SemanticError.InvalidExpressionFound(inv, phaseName)).rejected
      case e: Expr =>
        rewriteExpr(e, owner, transformedBindings, resolvables).widen
      case lambda: Lambda =>
        // Process lambda body to rewrite operators and function applications
        EitherT.liftF(BindingIds.within(lambda.params, owner)).flatMap { nested =>
          rewriteExpr(lambda.body, nested, transformedBindings, resolvables)
            .map(newBody => lambda.copy(body = newBody))
        }
      case c: Cond =>
        for
          newCond <- rewriteExpr(c.cond, owner, transformedBindings, resolvables)
          newIfTrue <- rewriteExpr(c.ifTrue, owner, transformedBindings, resolvables)
          newIfFalse <- rewriteExpr(c.ifFalse, owner, transformedBindings, resolvables)
        yield c.copy(cond = newCond, ifTrue = newIfTrue, ifFalse = newIfFalse)
      case t: Tuple =>
        t.elements
          .traverse(rewriteExpr(_, owner, transformedBindings, resolvables))
          .map(newElems => t.copy(elements = newElems))
      case tg: TermGroup =>
        rewriteExpr(tg.inner, owner, transformedBindings, resolvables).map(newInner =>
          tg.copy(inner = newInner)
        )
      case other =>
        other.accepted

  private def rewriteAppFn(
    fn:                  Ref | App | Lambda,
    owner:               BindingOwner,
    transformedBindings: Map[String, Bnd],
    resolvables:         ResolvablesIndex
  ): Rewrite[Ref | App | Lambda] =
    fn match
      case ref: Ref =>
        rewriteTerm(ref, owner, transformedBindings, resolvables).map(_.asInstanceOf[Ref])
      case app: App =>
        rewriteTerm(app, owner, transformedBindings, resolvables).map(_.asInstanceOf[App])
      case lambda: Lambda =>
        rewriteTerm(lambda, owner, transformedBindings, resolvables).map(_.asInstanceOf[Lambda])

  /** Check if a term is an operator
    */
  private def isOperator(term: Term, resolvables: ResolvablesIndex): Boolean =
    matchOpRef(term, resolvables).isDefined

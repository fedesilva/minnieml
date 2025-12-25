package mml.mmlclib.semantic

import cats.implicits.*
import mml.mmlclib.ast.*

object TypeChecker:
  private val phaseName          = "mml.mmlclib.semantic.TypeChecker"
  private val statementParamName = "__stmt"

  private case class CheckResult[+A](value: A, errors: Vector[TypeError]):
    def map[B](f: A => B): CheckResult[B] =
      CheckResult(f(value), errors)

    def flatMap[B](f: A => CheckResult[B]): CheckResult[B] =
      val next = f(value)
      CheckResult(next.value, errors ++ next.errors)

    def addErrors(more: Vector[TypeError]): CheckResult[A] =
      if more.isEmpty then this else CheckResult(value, errors ++ more)

  private object CheckResult:
    def ok[A](value: A): CheckResult[A] = CheckResult(value, Vector.empty)

  /** Main entry point - process module and accumulate errors */
  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState =
    // First pass: lower type ascriptions to specs for all functions and operators
    val (signatureErrors, membersWithSignatures) = lowerAscriptionsToSpecs(state.module)

    // Create the module with lowered type specs
    val moduleWithSignatures = state.module.copy(members = membersWithSignatures)

    // Second pass: check all members using the updated module
    val initialState = (Vector.empty[TypeError], Vector.empty[Member])
    val (checkErrors, checkedMembers) = membersWithSignatures.foldLeft(initialState) {
      case ((accErrors, accMembers), member) =>
        // Build the current module state with already-checked members + remaining members
        val currentModule = moduleWithSignatures.copy(members =
          accMembers.toList ++ membersWithSignatures.dropWhile(_ != member)
        )
        val result = checkMember(member, currentModule)
        (accErrors ++ result.errors, accMembers :+ result.value)
    }

    val allErrors = (signatureErrors ++ checkErrors).map(SemanticError.TypeCheckingError.apply)
    state
      .addErrors(allErrors.toList)
      .withModule(moduleWithSignatures.copy(members = checkedMembers.toList))

  /** First pass: lower mandatory type ascriptions to type specs */
  private def lowerAscriptionsToSpecs(module: Module): (Vector[TypeError], List[Member]) =
    val (errors, members) =
      module.members.foldLeft((Vector.empty[TypeError], Vector.empty[Member])) {
        case ((accErrors, accMembers), member) =>
          member match
            // Handle Bnd with Lambda (functions and operators with meta)
            case bnd: Bnd if bnd.meta.isDefined =>
              bnd.value.terms match
                case (lambda: Lambda) :: rest =>
                  validateMandatoryAscriptions(bnd) match
                    case Nil =>
                      // Lower param type ascriptions to specs
                      val updatedParams = lambda.params.map(p => p.copy(typeSpec = p.typeAsc))
                      val returnType    = lambda.typeAsc.orElse(bnd.typeAsc)
                      val loweredTypeSpec =
                        returnType.flatMap(buildFunctionTypeSpec(bnd.span, updatedParams, _))
                      val updatedLambda = lambda.copy(params = updatedParams)
                      val updatedValue  = bnd.value.copy(terms = updatedLambda :: rest)
                      val updatedBnd    = bnd.copy(value = updatedValue, typeSpec = loweredTypeSpec)
                      (accErrors, accMembers :+ updatedBnd)
                    case errs => (accErrors ++ errs, accMembers :+ bnd)
                case _ =>
                  // Bnd with meta but no Lambda - shouldn't happen, pass through
                  (accErrors, accMembers :+ bnd)

            case other =>
              // Other members don't need ascription lowering
              (accErrors, accMembers :+ other)
      }
    (errors, members.toList)

  /** Check if an expression contains a NativeImpl */
  private def hasNativeImpl(expr: Expr): Boolean =
    expr.terms.exists {
      case _:          NativeImpl => true
      case nestedExpr: Expr => hasNativeImpl(nestedExpr)
      case _ => false
    }

  /** Validate and compute types for a member */
  private def checkMember(member: Member, module: Module): CheckResult[Member] =
    member match
      case bnd: Bnd =>
        // Handle Bnd with Lambda (functions/operators)
        bnd.value.terms match
          case (lambda: Lambda) :: rest if bnd.meta.isDefined =>
            // Build param context from lambda params
            val paramContext = lambda.params.map(p => p.name -> p).toMap
            val returnType   = lambda.typeAsc.orElse(bnd.typeAsc)
            val checkedBody  = checkExprWithContext(lambda.body, module, paramContext, returnType)
            val transformedErrors =
              if returnType.isDefined then checkedBody.errors
              else
                checkedBody.errors.map {
                  case TypeError.UnresolvableType(
                        _,
                        Some(UnresolvableTypeContext.NamedValue(name)),
                        _
                      ) if name == bnd.name =>
                    TypeError.RecursiveFunctionMissingReturnType(bnd, phaseName)
                  case other => other
                }

            val (finalReturnType, returnErrors) = returnType match
              case Some(explicitReturn) =>
                if hasNativeImpl(lambda.body) then (Some(explicitReturn), Vector.empty)
                else
                  checkedBody.value.typeSpec match
                    case Some(actualType)
                        if areTypesCompatible(explicitReturn, actualType, module) =>
                      (Some(explicitReturn), Vector.empty)
                    case Some(actualType) =>
                      val displayName = bnd.meta.map(_.originalName).getOrElse(bnd.name)
                      (
                        Some(explicitReturn),
                        Vector(
                          TypeError.TypeMismatch(
                            bnd,
                            explicitReturn,
                            actualType,
                            phaseName,
                            Some(displayName)
                          )
                        )
                      )
                    case None =>
                      val errors =
                        if checkedBody.errors.isEmpty then
                          Vector(TypeError.UnresolvableType(bnd, None, phaseName))
                        else Vector.empty
                      (Some(explicitReturn), errors)
              case None =>
                checkedBody.value.typeSpec match
                  case Some(inferredType) => (Some(inferredType), Vector.empty)
                  case None =>
                    val errors =
                      if checkedBody.errors.isEmpty then
                        Vector(TypeError.UnresolvableType(bnd, None, phaseName))
                      else Vector.empty
                    (None, errors)

            val updatedLambda = lambda.copy(body = checkedBody.value)
            val updatedValue  = bnd.value.copy(terms = updatedLambda :: rest)
            val updatedBnd = bnd.copy(
              value = updatedValue,
              typeSpec = finalReturnType.flatMap(
                buildFunctionTypeSpec(bnd.span, lambda.params, _)
              )
            )
            CheckResult(updatedBnd, transformedErrors ++ returnErrors)

          case _ =>
            // Regular Bnd (no meta/lambda) - simple value binding
            val checkedValue = checkExpr(bnd.value, module)
            val ascriptionErrors = validateTypeAscription(
              bnd.copy(typeSpec = checkedValue.value.typeSpec),
              module
            ).toVector
            CheckResult(
              bnd.copy(value = checkedValue.value, typeSpec = checkedValue.value.typeSpec),
              checkedValue.errors ++ ascriptionErrors
            )

      case _: TypeDef | _: TypeAlias =>
        // Type definitions and aliases are handled by TypeResolver, no checks needed here
        CheckResult.ok(member)

      case other =>
        // Other member types do not require type checking at this stage
        CheckResult.ok(other)

  /** Validate mandatory ascriptions for functions/operators
    *
    * Eventually, this will not be required; we will just generate type params, for the next type
    * checker phase.
    */
  private def validateMandatoryAscriptions(member: Member): List[TypeError] = member match
    // Handle Bnd with Lambda (functions and operators)
    case bnd: Bnd if bnd.meta.isDefined =>
      bnd.value.terms match
        case (lambda: Lambda) :: _ =>
          bnd.meta.get.origin match
            case BindingOrigin.Function =>
              // Functions: check all param type ascriptions
              lambda.params.collect {
                case param if param.typeAsc.isEmpty =>
                  TypeError.MissingParameterType(param, bnd, phaseName)
              }
            case BindingOrigin.Operator =>
              // Operators: check param type ascriptions
              lambda.params.collect {
                case param if param.typeAsc.isEmpty =>
                  TypeError.MissingOperatorParameterType(param, bnd, phaseName)
              }
        case _ => Nil

    case _ =>
      Nil

  /** Type check expressions using forward propagation with parameter context */
  private def checkExprWithContext(
    expr:         Expr,
    module:       Module,
    paramContext: Map[String, FnParam],
    expectedType: Option[TypeSpec] = None
  ): CheckResult[Expr] =
    expr.terms match
      case List(singleTerm) =>
        val checkedTerm = checkTermWithContext(singleTerm, module, paramContext, expectedType)
        val finalTerm   = normalizeCheckedTerm(checkedTerm.value)
        CheckResult(
          expr.copy(terms = List(finalTerm), typeSpec = finalTerm.typeSpec),
          checkedTerm.errors
        )
      case terms =>
        // Check all terms in the expression
        val (errors, checkedTerms) =
          terms.foldLeft((Vector.empty[TypeError], Vector.empty[Term])) {
            case ((accErrors, accTerms), term) =>
              val checkedTerm = term match
                case e: Expr => checkExprWithContext(e, module, paramContext)
                case t => checkTermWithContext(t, module, paramContext)
              val finalTerm = normalizeCheckedTerm(checkedTerm.value)
              (accErrors ++ checkedTerm.errors, accTerms :+ finalTerm)
          }
        // The type of the expression is the type of the last term
        val exprType = checkedTerms.lastOption.flatMap { case t: Typeable =>
          t.typeSpec
        }
        CheckResult(expr.copy(terms = checkedTerms.toList, typeSpec = exprType), errors)

  /** Type check expressions using forward propagation */
  private def checkExpr(
    expr:         Expr,
    module:       Module,
    expectedType: Option[TypeSpec] = None
  ): CheckResult[Expr] =
    checkExprWithContext(expr, module, Map.empty, expectedType)

  /** Type check individual terms with parameter context */
  private def checkTermWithContext(
    term:         Term,
    module:       Module,
    paramContext: Map[String, FnParam],
    expectedType: Option[TypeSpec] = None
  ): CheckResult[Term] =
    term match
      case ref: Ref =>
        // First check parameter context, then fall back to normal resolution
        paramContext.get(ref.name) match
          case Some(param) =>
            param.typeSpec match
              case Some(t) => CheckResult.ok(ref.copy(typeSpec = Some(t)))
              case None =>
                CheckResult(
                  ref,
                  Vector(
                    TypeError.UnresolvableType(
                      ref,
                      Some(UnresolvableTypeContext.NamedValue(ref.name)),
                      phaseName
                    )
                  )
                )
          case None =>
            checkRef(ref, module)

      case app: App =>
        checkApplicationWithContext(app, module, paramContext)

      case cond: Cond =>
        checkConditionalWithContext(cond, module, paramContext)

      case group: TermGroup =>
        checkExprWithContext(group.inner, module, paramContext, expectedType).map(checkedInner =>
          group.copy(inner = checkedInner)
        )

      case lambda: Lambda =>
        // Handle Lambda terms (e.g., from eta-expansion of partial applications)
        checkLambdaWithContext(lambda, module, paramContext)

      case other =>
        // For other terms, use the regular checkTerm
        checkTerm(other, module, expectedType)

  /** Type check individual terms */
  private def checkTerm(
    term:         Term,
    module:       Module,
    expectedType: Option[TypeSpec] = None
  ): CheckResult[Term] =
    term match
      case lit: LiteralValue =>
        // Literals have their types defined directly, so they are already "checked"
        CheckResult.ok(lit)

      case ref: Ref =>
        checkRef(ref, module)

      case app: App =>
        checkApplication(app, module)

      case cond: Cond =>
        checkConditional(cond, module)

      case group: TermGroup =>
        checkExpr(group.inner, module, expectedType).map(checkedInner =>
          group.copy(inner = checkedInner)
        )

      case hole: Hole =>
        expectedType match
          case Some(t) => CheckResult.ok(hole.copy(typeSpec = Some(t)))
          case None =>
            val dummyBnd = Bnd(
              visibility = MemberVisibility.Private,
              span       = hole.span,
              name       = "unknown",
              value      = Expr(hole.span, List(hole)),
              typeAsc    = None,
              typeSpec   = None,
              docComment = None
            )
            CheckResult(
              hole,
              Vector(TypeError.UntypedHoleInBinding(dummyBnd, phaseName))
            )

      case other =>
        // Other term types do not require type checking at this stage
        CheckResult.ok(other)

  /** Check ref using module lookups */
  private def checkRef(ref: Ref, module: Module): CheckResult[Ref] =
    // Look up the declaration in the current module to get the computed typeSpec
    ref.resolvedAs match
      case Some(param: FnParam) =>
        // For parameters, use their type spec (lowered from ascription)
        param.typeSpec match
          case Some(t) =>
            CheckResult.ok(ref.copy(typeSpec = Some(t)))
          case None =>
            CheckResult(
              ref,
              Vector(
                TypeError.UnresolvableType(
                  ref,
                  Some(UnresolvableTypeContext.NamedValue(ref.name)),
                  phaseName
                )
              )
            )
      case Some(decl: Decl) =>
        // Look up the declaration in the current module (which has lowered typeSpecs)
        val updatedDecl = module.members.find {
          case candidate: Bnd if decl.isInstanceOf[Bnd] =>
            candidate.name == decl.name
          case _ => false
        }

        updatedDecl match
          case Some(d: Decl) if d.typeSpec.isDefined =>
            CheckResult.ok(ref.copy(typeSpec = d.typeSpec))
          case _ =>
            // Fallback to the resolved declaration's typeSpec if available
            decl.typeSpec match
              case Some(t) => CheckResult.ok(ref.copy(typeSpec = Some(t)))
              case None =>
                CheckResult(
                  ref,
                  Vector(
                    TypeError.UnresolvableType(
                      ref,
                      Some(UnresolvableTypeContext.NamedValue(ref.name)),
                      phaseName
                    )
                  )
                )
      case _ =>
        CheckResult(
          ref,
          Vector(
            TypeError.UnresolvableType(
              ref,
              Some(UnresolvableTypeContext.NamedValue(ref.name)),
              phaseName
            )
          )
        )

  /** Check function applications with parameter context */
  private def checkApplicationWithContext(
    app:          App,
    module:       Module,
    paramContext: Map[String, FnParam]
  ): CheckResult[App] =
    // Special case: immediately-applied lambda (from let-expression desugaring)
    // Must check arg first to infer lambda param type
    app.fn match
      case lambda: Lambda if lambda.params.nonEmpty =>
        checkImmediatelyAppliedLambda(app, lambda, module, paramContext)
      case _ =>
        checkNormalApplication(app, module, paramContext)

  /** Check immediately-applied lambda: App(Lambda([param], body), arg)
    *
    * For let-expressions like `let a = 1; a + 1`, the parser desugars to
    * `App(Lambda([a], a + 1), 1)`. The param has no type annotation, so we must check the arg first
    * to infer the param type, then check the body.
    */
  private def checkImmediatelyAppliedLambda(
    app:          App,
    lambda:       Lambda,
    module:       Module,
    paramContext: Map[String, FnParam]
  ): CheckResult[App] =
    // Step 1: Check arg first to get its type
    val checkedArg = checkExprWithContext(app.arg, module, paramContext)
    val argType    = checkedArg.value.typeSpec

    // Step 2: Assign inferred type to param (use typeAsc if present, else inferred)
    val param = lambda.params.head
    val paramErrors =
      (param.typeAsc, argType) match
        case (Some(expectedType), Some(actualType))
            if !areTypesCompatible(expectedType, actualType, module) =>
          val expectedBy =
            if param.name == statementParamName then Some("statement") else Some(param.name)
          Vector(TypeError.TypeMismatch(app.arg, expectedType, actualType, phaseName, expectedBy))
        case _ => Vector.empty

    val typedParamType = param.typeAsc.orElse(argType)
    val typedParam     = param.copy(typeSpec = typedParamType)

    // Step 3: Build param context with typed param
    val lambdaParamContext = paramContext + (typedParam.name -> typedParam)

    // Step 4: Check lambda body with typed param
    val checkedBody = checkExprWithContext(lambda.body, module, lambdaParamContext)
    val bodyType    = checkedBody.value.typeSpec
    val bodyErrors =
      if bodyType.isEmpty && checkedBody.errors.isEmpty then
        Vector(TypeError.UnresolvableType(lambda.body, None, phaseName))
      else Vector.empty

    // Step 5: Build result
    val lambdaTypeSpec = for
      paramType <- typedParam.typeSpec
      returnType <- bodyType
    yield TypeFn(lambda.span, List(paramType), returnType)

    val checkedLambda = lambda.copy(
      params   = List(typedParam),
      body     = checkedBody.value,
      typeSpec = lambdaTypeSpec
    )

    val argErrors =
      if argType.isEmpty && checkedArg.errors.isEmpty then
        Vector(
          TypeError.UnresolvableType(
            app.arg,
            Some(UnresolvableTypeContext.Argument),
            phaseName
          )
        )
      else Vector.empty

    CheckResult(
      app.copy(fn = checkedLambda, arg = checkedArg.value, typeSpec = bodyType),
      checkedArg.errors ++ paramErrors ++ checkedBody.errors ++ argErrors ++ bodyErrors
    )

  /** Check normal application (non-lambda function) */
  private def checkNormalApplication(
    app:          App,
    module:       Module,
    paramContext: Map[String, FnParam]
  ): CheckResult[App] =
    // Phase 1: Recursively check and type all sub-nodes
    val checkedFnEither = app.fn match
      case innerApp: App => checkApplicationWithContext(innerApp, module, paramContext)
      case other => checkTermWithContext(other, module, paramContext)

    val checkedArgEither = checkExprWithContext(app.arg, module, paramContext)

    // Phase 2: Validate function shape, determine application type, and build result
    val checkedFn  = checkedFnEither.value
    val checkedArg = checkedArgEither.value
    val normalizedFn: Ref | App | Lambda = checkedFn match
      case ref:      Ref => ref
      case innerApp: App => innerApp
      case lambda:   Lambda => lambda
      case _ => app.fn

    val fnErrors =
      checkedFn match
        case _: Ref | _: App | _: Lambda => Vector.empty
        case _ =>
          Vector(
            TypeError.InvalidApplication(
              app,
              TypeRef(app.span, "invalid-fn"),
              TypeRef(app.span, "unknown-arg"),
              phaseName
            )
          )

    val appTypeResult =
      if fnErrors.isEmpty then
        determineApplicationType(
          app.copy(fn = normalizedFn, arg = checkedArg),
          normalizedFn,
          module
        )
      else CheckResult.ok(None)

    val typeErrors =
      filterDuplicateUnresolvable(
        appTypeResult.errors,
        checkedFnEither.errors,
        checkedArgEither.errors
      )

    CheckResult(
      app.copy(fn = normalizedFn, arg = checkedArg, typeSpec = appTypeResult.value),
      checkedFnEither.errors ++ checkedArgEither.errors ++ fnErrors ++ typeErrors
    )

  /** Determine the type of an application based on its function and argument with validation */
  private def determineApplicationType(
    app:       App,
    checkedFn: Term,
    module:    Module
  ): CheckResult[Option[TypeSpec]] =
    checkedFn match
      case ref: Ref =>
        determineRefApplicationType(app, ref, module)

      case innerApp: App if innerApp.typeSpec.isDefined =>
        innerApp.typeSpec match
          case Some(fnType: TypeFn) =>
            applyTypeFnToArgument(app, fnType, app.arg.typeSpec, module, None)
          case Some(other) =>
            app.arg.typeSpec match
              case Some(argType) =>
                CheckResult(
                  None,
                  Vector(TypeError.InvalidApplication(app, other, argType, phaseName))
                )
              case None =>
                CheckResult(
                  None,
                  Vector(
                    TypeError.UnresolvableType(
                      app.arg,
                      Some(UnresolvableTypeContext.Argument),
                      phaseName
                    )
                  )
                )
          case None =>
            CheckResult(
              None,
              Vector(
                TypeError.UnresolvableType(
                  app,
                  Some(UnresolvableTypeContext.Function),
                  phaseName
                )
              )
            )

      case _ =>
        val fnType  = checkedFn.typeSpec
        val argType = app.arg.typeSpec
        (fnType, argType) match
          case (Some(actualFnType), Some(actualArgType)) =>
            CheckResult(
              None,
              Vector(TypeError.InvalidApplication(app, actualFnType, actualArgType, phaseName))
            )
          case (Some(_), None) =>
            CheckResult(
              None,
              Vector(
                TypeError.UnresolvableType(
                  app.arg,
                  Some(UnresolvableTypeContext.Argument),
                  phaseName
                )
              )
            )
          case (None, _) =>
            CheckResult(
              None,
              Vector(
                TypeError.UnresolvableType(
                  app,
                  Some(UnresolvableTypeContext.Function),
                  phaseName
                )
              )
            )

  private def determineRefApplicationType(
    app:    App,
    ref:    Ref,
    module: Module
  ): CheckResult[Option[TypeSpec]] =
    ref.resolvedAs match
      case None =>
        CheckResult(
          None,
          Vector(
            TypeError.UnresolvableType(
              ref,
              Some(UnresolvableTypeContext.NamedValue(ref.name)),
              phaseName
            )
          )
        )
      case Some(_) =>
        extractTypeFnFromRef(ref, module) match
          case Some(fnType) =>
            applyTypeFnToArgument(app, fnType, app.arg.typeSpec, module, Some(ref.name))
          case None =>
            val refType = ref.typeSpec
            app.arg.typeSpec match
              case Some(argType) =>
                refType match
                  case Some(actualRefType) =>
                    CheckResult(
                      None,
                      Vector(TypeError.InvalidApplication(app, actualRefType, argType, phaseName))
                    )
                  case None =>
                    CheckResult(
                      None,
                      Vector(
                        TypeError.UnresolvableType(
                          ref,
                          Some(UnresolvableTypeContext.NamedValue(ref.name)),
                          phaseName
                        )
                      )
                    )
              case None =>
                CheckResult(
                  None,
                  Vector(
                    TypeError.UnresolvableType(
                      app.arg,
                      Some(UnresolvableTypeContext.Argument),
                      phaseName
                    )
                  )
                )

  private def extractTypeFnFromRef(ref: Ref, module: Module): Option[TypeFn] =
    val directType = ref.typeSpec.flatMap(extractTypeFn)
    directType.orElse {
      ref.resolvedAs.flatMap {
        case decl:  Decl => findTypeFnForDecl(decl, module)
        case param: FnParam => param.typeSpec.flatMap(extractTypeFn)
        case _ => None
      }
    }

  private def extractTypeFn(typeSpec: TypeSpec): Option[TypeFn] =
    unwrapTypeGroup(typeSpec) match
      case tf: TypeFn => Some(tf)
      case TypeScheme(_, _, bodyType) => extractTypeFn(bodyType)
      case _ => None

  private def findTypeFnForDecl(decl: Decl, module: Module): Option[TypeFn] =
    val updatedDecl = module.members.collectFirst {
      case candidate: Bnd if decl.isInstanceOf[Bnd] && candidate.name == decl.name => candidate
    }

    updatedDecl
      .collect { case d: Decl => d }
      .orElse(Some(decl))
      .flatMap(_.typeSpec.flatMap(extractTypeFn))

  private def applyTypeFnToArgument(
    app:     App,
    fnType:  TypeFn,
    argType: Option[TypeSpec],
    module:  Module,
    fnLabel: Option[String]
  ): CheckResult[Option[TypeSpec]] =
    argType match
      case Some(actualArgType) =>
        fnType.paramTypes match
          case headParam :: tailParams =>
            val returnType =
              buildRemainingFunctionTypeFromTypes(tailParams, fnType.returnType, app.span)
            if areTypesCompatible(headParam, actualArgType, module) then
              CheckResult.ok(Some(returnType))
            else
              CheckResult(
                Some(returnType),
                Vector(
                  TypeError.TypeMismatch(
                    app.arg,
                    headParam,
                    actualArgType,
                    phaseName,
                    fnLabel
                  )
                )
              )
          case Nil =>
            val returnType = fnType.returnType
            actualArgType match
              case TypeUnit(_) | TypeRef(_, "Unit", _) => CheckResult.ok(Some(returnType))
              case other =>
                CheckResult(
                  Some(returnType),
                  Vector(
                    TypeError.InvalidApplication(
                      app,
                      fnType.returnType,
                      other,
                      phaseName
                    )
                  )
                )
      case None =>
        CheckResult(
          None,
          Vector(
            TypeError.UnresolvableType(
              app.arg,
              Some(UnresolvableTypeContext.Argument),
              phaseName
            )
          )
        )

  /** Check function applications by collecting all arguments in a chain */
  private def checkApplication(app: App, module: Module): CheckResult[App] =
    checkApplicationWithContext(app, module, Map.empty)

  private def buildFunctionTypeSpec(
    span:       SrcSpan,
    params:     List[FnParam],
    returnType: TypeSpec
  ): Option[TypeSpec] =
    params
      .traverse(_.typeSpec)
      .map(paramTypes => TypeFn(span, paramTypes, returnType))

  private def buildRemainingFunctionTypeFromTypes(
    remainingTypes: List[TypeSpec],
    returnType:     TypeSpec,
    span:           SrcSpan
  ): TypeSpec =
    if remainingTypes.isEmpty then returnType
    else TypeFn(span, remainingTypes, returnType)

  /** Validate type ascription against computed type */
  private def validateTypeAscription(node: Typeable, module: Module): List[TypeError] =
    (node.typeAsc, node.typeSpec) match
      case (Some(ascribed), Some(computed)) =>
        if areTypesCompatible(ascribed, computed, module) then Nil
        else List(TypeError.TypeMismatch(node, ascribed, computed, phaseName, None))
      case _ => Nil

  /** Check type compatibility (handles aliases, etc.) */
  private def areTypesCompatible(t1: TypeSpec, t2: TypeSpec, module: Module): Boolean =
    val resolved1 = unwrapTypeGroup(resolveAliasChain(t1, module))
    val resolved2 = unwrapTypeGroup(resolveAliasChain(t2, module))
    (resolved1, resolved2) match
      case (NativePrimitive(_, n1), NativePrimitive(_, n2)) => n1 == n2
      case (TypeRef(_, name1, _), TypeRef(_, name2, _)) => name1 == name2
      case (TypeFn(_, p1, r1), TypeFn(_, p2, r2)) =>
        p1.length == p2.length &&
        p1.zip(p2).forall { case (pt1, pt2) => areTypesCompatible(pt1, pt2, module) } &&
        areTypesCompatible(r1, r2, module)
      case (TypeTuple(_, e1), TypeTuple(_, e2)) =>
        e1.length == e2.length &&
        e1.zip(e2).forall { case (et1, et2) => areTypesCompatible(et1, et2, module) }
      case (TypeUnit(_), TypeUnit(_)) => true
      case (TypeUnit(_), TypeRef(_, "Unit", _)) => true
      case (TypeRef(_, "Unit", _), TypeUnit(_)) => true
      case _ => false

  private def unwrapTypeGroup(typeSpec: TypeSpec): TypeSpec =
    typeSpec match
      case TypeGroup(_, types) if types.size == 1 => unwrapTypeGroup(types.head)
      case other => other

  /** Follow alias chain to concrete type and update typeSpec along the way */
  private def resolveAliasChain(typeSpec: TypeSpec, module: Module): TypeSpec = typeSpec match
    case tr @ TypeRef(_, name, Some(ta: TypeAlias)) =>
      ta.typeSpec match
        case Some(resolvedSpec) => resolvedSpec
        case None => resolveAliasChain(ta.typeRef, module)
    case tr @ TypeRef(_, name, None) =>
      // If TypeRef doesn't have resolvedAs, look it up in the module
      module.members
        .collectFirst {
          case ta: TypeAlias if ta.name == name =>
            ta.typeSpec match
              case Some(resolvedSpec) => resolvedSpec
              case None => resolveAliasChain(ta.typeRef, module)
          case td: TypeDef if td.name == name =>
            // Return TypeRef to the TypeDef, not its native typeSpec
            // This ensures type aliases resolve to MML types rather than native representations
            TypeRef(tr.span, td.name, Some(td))
        }
        .getOrElse(tr)
    case other => other

  /** Check Lambda terms (e.g., from eta-expansion of partial applications) */
  private def checkLambdaWithContext(
    lambda:       Lambda,
    module:       Module,
    paramContext: Map[String, FnParam]
  ): CheckResult[Lambda] =
    // Lower param type ascriptions to specs if not already done
    val paramsWithSpecs = lambda.params.map { p =>
      if p.typeSpec.isDefined then p
      else p.copy(typeSpec = p.typeAsc)
    }
    // Build param context from lambda params (merged with outer context)
    val lambdaParamContext = paramContext ++ paramsWithSpecs.map(p => p.name -> p).toMap
    val checkedBody        = checkExprWithContext(lambda.body, module, lambdaParamContext)
    val bodyType           = checkedBody.value.typeSpec
    val bodyErrors =
      if bodyType.isEmpty && checkedBody.errors.isEmpty then
        Vector(TypeError.UnresolvableType(lambda, None, phaseName))
      else Vector.empty
    val lambdaTypeSpec = bodyType.flatMap(buildFunctionTypeSpec(lambda.span, paramsWithSpecs, _))
    CheckResult(
      lambda.copy(
        params   = paramsWithSpecs,
        body     = checkedBody.value,
        typeSpec = lambdaTypeSpec
      ),
      checkedBody.errors ++ bodyErrors
    )

  /** Check conditional with parameter context */
  private def checkConditionalWithContext(
    cond:         Cond,
    module:       Module,
    paramContext: Map[String, FnParam]
  ): CheckResult[Cond] =
    val checkedCond  = checkExprWithContext(cond.cond, module, paramContext)
    val checkedTrue  = checkExprWithContext(cond.ifTrue, module, paramContext)
    val checkedFalse = checkExprWithContext(cond.ifFalse, module, paramContext)

    val condErrors =
      checkedCond.value.typeSpec match
        case Some(TypeRef(_, "Bool", _)) => Vector.empty
        case Some(other) if checkedCond.errors.isEmpty =>
          Vector(
            TypeError.TypeMismatch(
              checkedCond.value,
              TypeRef(cond.span, "Bool"),
              other,
              phaseName,
              None
            )
          )
        case None if checkedCond.errors.isEmpty =>
          Vector(
            TypeError.UnresolvableType(checkedCond.value, None, phaseName)
          )
        case _ => Vector.empty

    val trueType  = checkedTrue.value.typeSpec
    val falseType = checkedFalse.value.typeSpec

    val branchErrors =
      (trueType, falseType) match
        case (Some(t), Some(f)) if !areTypesCompatible(t, f, module) =>
          Vector(TypeError.ConditionalBranchTypeMismatch(cond, t, f, phaseName))
        case (None, _) if checkedTrue.errors.isEmpty =>
          Vector(TypeError.ConditionalBranchTypeUnknown(cond, phaseName))
        case (_, None) if checkedFalse.errors.isEmpty =>
          Vector(TypeError.ConditionalBranchTypeUnknown(cond, phaseName))
        case _ => Vector.empty

    val condTypeSpec =
      (trueType, falseType) match
        case (Some(t), Some(f)) if areTypesCompatible(t, f, module) => Some(t)
        case _ => None

    CheckResult(
      cond.copy(
        cond     = checkedCond.value,
        ifTrue   = checkedTrue.value,
        ifFalse  = checkedFalse.value,
        typeSpec = condTypeSpec
      ),
      checkedCond.errors ++ checkedTrue.errors ++ checkedFalse.errors ++ condErrors ++ branchErrors
    )

  /** Check conditional expressions (both branches must match) */
  private def checkConditional(cond: Cond, module: Module): CheckResult[Cond] =
    checkConditionalWithContext(cond, module, Map.empty)

  private def normalizeCheckedTerm(term: Term): Term =
    term match
      case app: App if app.typeSpec.isEmpty && app.typeAsc.isDefined =>
        // This is the error condition. If we are here, it means checkApplicationWithContext
        // is returning a malformed App node. We correct it immediately.
        app.copy(typeSpec = app.typeAsc, typeAsc = None)
      case other => other

  private def filterDuplicateUnresolvable(
    errors:           Vector[TypeError],
    checkedFnErrors:  Vector[TypeError],
    checkedArgErrors: Vector[TypeError]
  ): Vector[TypeError] =
    errors.filterNot {
      case TypeError.UnresolvableType(_, Some(UnresolvableTypeContext.Argument), _) =>
        checkedArgErrors.nonEmpty
      case TypeError.UnresolvableType(_, Some(UnresolvableTypeContext.Function), _) =>
        checkedFnErrors.nonEmpty
      case _ => false
    }

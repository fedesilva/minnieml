package mml.mmlclib.semantic

import cats.implicits.*
import mml.mmlclib.ast.*

object TypeChecker:
  private val phaseName = "mml.mmlclib.semantic.TypeChecker"

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
        checkMember(member, currentModule) match
          case Left(errs) => (accErrors ++ errs, accMembers :+ member)
          case Right(newMember) => (accErrors, accMembers :+ newMember)
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
            case fnDef: FnDef =>
              validateMandatoryAscriptions(fnDef) match
                case Nil =>
                  // Lower return type ascription to spec
                  // Lower param type ascriptions to specs
                  val updatedParams = fnDef.params.map(p => p.copy(typeSpec = p.typeAsc))
                  val updatedFn     = fnDef.copy(typeSpec = fnDef.typeAsc, params = updatedParams)
                  (accErrors, accMembers :+ updatedFn)
                case errs => (accErrors ++ errs, accMembers :+ fnDef)

            case opDef: OpDef =>
              validateMandatoryAscriptions(opDef) match
                case Nil =>
                  // Lower return type ascription to spec
                  // Lower param type ascriptions to specs
                  val newOp = opDef match
                    case b: BinOpDef =>
                      val updatedParam1 = b.param1.copy(typeSpec = b.param1.typeAsc)
                      val updatedParam2 = b.param2.copy(typeSpec = b.param2.typeAsc)
                      b.copy(typeSpec = b.typeAsc, param1 = updatedParam1, param2 = updatedParam2)
                    case u: UnaryOpDef =>
                      val updatedParam = u.param.copy(typeSpec = u.param.typeAsc)
                      u.copy(typeSpec = u.typeAsc, param = updatedParam)
                  (accErrors, accMembers :+ newOp)
                case errs => (accErrors ++ errs, accMembers :+ opDef)

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
  private def checkMember(member: Member, module: Module): Either[List[TypeError], Member] =
    member match
      case fnDef: FnDef =>
        // Type spec already lowered in first pass, just check the body
        // Pass the function's parameters as context for parameter lookups
        val paramContext = fnDef.params.map(p => p.name -> p).toMap
        for
          checkedBody <- checkExprWithContext(fnDef.body, module, paramContext, fnDef.typeSpec)
          finalTypeSpec <- fnDef.typeSpec match {
            case Some(explicitType) =>
              // Check if this is a native implementation - can't validate those
              if hasNativeImpl(fnDef.body) then
                // For native implementations, trust the explicit type since we can't validate
                Right(explicitType)
              else
                // For regular functions, validate explicit type matches body type
                checkedBody.typeSpec match
                  case Some(actualType) if areTypesCompatible(explicitType, actualType, module) =>
                    Right(explicitType)
                  case Some(actualType) =>
                    Left(List(TypeError.TypeMismatch(fnDef, explicitType, actualType, phaseName)))
                  case None =>
                    Left(
                      List(
                        TypeError.UnresolvableType(TypeRef(fnDef.span, "body"), fnDef, phaseName)
                      )
                    )
            case None =>
              // No explicit return type - infer from body
              checkedBody.typeSpec match {
                case Some(inferredType) => Right(inferredType)
                case None =>
                  Left(
                    List(TypeError.UnresolvableType(TypeRef(fnDef.span, "body"), fnDef, phaseName))
                  )
              }
          }
        yield fnDef.copy(body = checkedBody, typeSpec = Some(finalTypeSpec))

      case opDef: OpDef =>
        // Type spec already lowered in first pass, just check the body
        // typeSpec is now the return type, not a TypeFn
        // Build parameter context based on operator type
        val paramContext = opDef match
          case b: BinOpDef => Map(b.param1.name -> b.param1, b.param2.name -> b.param2)
          case u: UnaryOpDef => Map(u.param.name -> u.param)
        for
          checkedBody <- checkExprWithContext(opDef.body, module, paramContext, opDef.typeSpec)
          finalTypeSpec <- opDef.typeSpec match {
            case Some(explicitType) =>
              // Check if this is a native implementation - can't validate those
              if hasNativeImpl(opDef.body) then
                // For native implementations, trust the explicit type since we can't validate
                Right(explicitType)
              else
                // For regular operators, validate explicit type matches body type
                checkedBody.typeSpec match
                  case Some(actualType) if areTypesCompatible(explicitType, actualType, module) =>
                    Right(explicitType)
                  case Some(actualType) =>
                    Left(List(TypeError.TypeMismatch(opDef, explicitType, actualType, phaseName)))
                  case None =>
                    Left(
                      List(
                        TypeError.UnresolvableType(TypeRef(opDef.span, "body"), opDef, phaseName)
                      )
                    )
            case None =>
              // No explicit return type - infer from body
              checkedBody.typeSpec match {
                case Some(inferredType) => Right(inferredType)
                case None =>
                  Left(
                    List(TypeError.UnresolvableType(TypeRef(opDef.span, "body"), opDef, phaseName))
                  )
              }
          }
        yield opDef match
          case b: BinOpDef => b.copy(body = checkedBody, typeSpec = Some(finalTypeSpec))
          case u: UnaryOpDef => u.copy(body = checkedBody, typeSpec = Some(finalTypeSpec))

      case bnd: Bnd =>
        for {
          checkedValue <- checkExpr(bnd.value, module)
          _ <- validateTypeAscription(bnd.copy(typeSpec = checkedValue.typeSpec), module) match {
            case Nil => Right(())
            case errors => Left(errors)
          }
        } yield bnd.copy(value = checkedValue, typeSpec = checkedValue.typeSpec)

      case _: TypeDef | _: TypeAlias =>
        // Type definitions and aliases are handled by TypeResolver, no checks needed here
        Right(member)

      case other =>
        // Other member types do not require type checking at this stage
        Right(other)

  /** Validate mandatory ascriptions for functions/operators */
  private def validateMandatoryAscriptions(member: Member): List[TypeError] = member match
    case fnDef: FnDef =>
      val paramErrors = fnDef.params.collect {
        case param if param.typeAsc.isEmpty =>
          TypeError.MissingParameterType(param, fnDef, phaseName)
      }
      // Return type inference: no longer require explicit return types
      paramErrors

    case opDef: BinOpDef =>
      val param1Error = opDef.param1.typeAsc match
        case None => List(TypeError.MissingOperatorParameterType(opDef.param1, opDef, phaseName))
        case Some(_) => Nil
      val param2Error = opDef.param2.typeAsc match
        case None => List(TypeError.MissingOperatorParameterType(opDef.param2, opDef, phaseName))
        case Some(_) => Nil
      // Return type inference: no longer require explicit return types
      param1Error ++ param2Error

    case opDef: UnaryOpDef =>
      val paramError = opDef.param.typeAsc match
        case None => List(TypeError.MissingOperatorParameterType(opDef.param, opDef, phaseName))
        case Some(_) => Nil
      // Return type inference: no longer require explicit return types
      paramError

    case _ =>
      Nil

  /** Type check expressions using forward propagation with parameter context */
  private def checkExprWithContext(
    expr:         Expr,
    module:       Module,
    paramContext: Map[String, FnParam],
    expectedType: Option[TypeSpec] = None
  ): Either[List[TypeError], Expr] =
    expr.terms match
      case List(singleTerm) =>
        checkTermWithContext(singleTerm, module, paramContext, expectedType).map { checkedTerm =>
          val finalTerm = checkedTerm match {
            case app: App if app.typeSpec.isEmpty && app.typeAsc.isDefined =>
              // This is the error condition. If we are here, it means checkApplicationWithContext
              // is returning a malformed App node. We correct it immediately.
              app.copy(typeSpec = app.typeAsc, typeAsc = None)
            case other => other
          }
          expr.copy(terms = List(finalTerm), typeSpec = finalTerm.typeSpec)
        }
      case terms =>
        // Check all terms in the expression
        val checkedTermsEither = terms.traverse {
          case e: Expr => checkExprWithContext(e, module, paramContext)
          case t => checkTermWithContext(t, module, paramContext)
        }
        checkedTermsEither.map { checkedTerms =>
          // The type of the expression is the type of the last term
          val exprType = checkedTerms.lastOption.flatMap { case t: Typeable =>
            t.typeSpec
          }
          expr.copy(terms = checkedTerms, typeSpec = exprType)
        }

  /** Type check expressions using forward propagation */
  private def checkExpr(
    expr:         Expr,
    module:       Module,
    expectedType: Option[TypeSpec] = None
  ): Either[List[TypeError], Expr] =
    checkExprWithContext(expr, module, Map.empty, expectedType)

  /** Type check individual terms with parameter context */
  private def checkTermWithContext(
    term:         Term,
    module:       Module,
    paramContext: Map[String, FnParam],
    expectedType: Option[TypeSpec] = None
  ): Either[List[TypeError], Term] =
    term match
      case ref: Ref =>
        // First check parameter context, then fall back to normal resolution
        paramContext.get(ref.name) match
          case Some(param) =>
            param.typeSpec match
              case Some(t) => Right(ref.copy(typeSpec = Some(t)))
              case None =>
                Left(List(TypeError.UnresolvableType(TypeRef(ref.span, ref.name), ref, phaseName)))
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

      case other =>
        // For other terms, use the regular checkTerm
        checkTerm(other, module, expectedType)

  /** Type check individual terms */
  private def checkTerm(
    term:         Term,
    module:       Module,
    expectedType: Option[TypeSpec] = None
  ): Either[List[TypeError], Term] =
    term match
      case lit: LiteralValue =>
        // Literals have their types defined directly, so they are already "checked"
        Right(lit)

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
          case Some(t) => Right(hole.copy(typeSpec = Some(t)))
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
            Left(List(TypeError.UntypedHoleInBinding(dummyBnd, phaseName)))

      case other =>
        // Other term types do not require type checking at this stage
        Right(other)

  /** Check ref using module lookups */
  private def checkRef(ref: Ref, module: Module): Either[List[TypeError], Ref] =
    // Look up the declaration in the current module to get the computed typeSpec
    ref.resolvedAs match
      case Some(param: FnParam) =>
        // For parameters, use their type spec (lowered from ascription)
        param.typeSpec match
          case Some(t) =>
            Right(ref.copy(typeSpec = Some(t)))
          case None =>
            Left(List(TypeError.UnresolvableType(TypeRef(ref.span, ref.name), ref, phaseName)))
      case Some(decl: Decl) =>
        // Look up the declaration in the current module (which has lowered typeSpecs)
        val updatedDecl = module.members.find {
          case candidate: UnaryOpDef if decl.isInstanceOf[UnaryOpDef] =>
            candidate.name == decl.name
          case candidate: BinOpDef if decl.isInstanceOf[BinOpDef] =>
            candidate.name == decl.name
          case candidate: FnDef if decl.isInstanceOf[FnDef] =>
            candidate.name == decl.name
          case candidate: Bnd if decl.isInstanceOf[Bnd] =>
            candidate.name == decl.name
          case _ => false
        }

        updatedDecl match
          case Some(d: Decl) if d.typeSpec.isDefined =>
            Right(ref.copy(typeSpec = d.typeSpec))
          case _ =>
            // Fallback to the resolved declaration's typeSpec if available
            decl.typeSpec match
              case Some(t) => Right(ref.copy(typeSpec = Some(t)))
              case None =>
                Left(List(TypeError.UnresolvableType(TypeRef(ref.span, ref.name), ref, phaseName)))
      case _ => Left(List(TypeError.UnresolvableType(TypeRef(ref.span, ref.name), ref, phaseName)))

  /** Check function applications with parameter context */
  private def checkApplicationWithContext(
    app:          App,
    module:       Module,
    paramContext: Map[String, FnParam]
  ): Either[List[TypeError], App] =
    // Two-phase approach:
    // Phase 1: Recursively check and type all sub-nodes
    val checkedFnEither = app.fn match
      case innerApp: App => checkApplicationWithContext(innerApp, module, paramContext)
      case other => checkTermWithContext(other, module, paramContext)

    val checkedArgEither = checkExprWithContext(app.arg, module, paramContext)

    val resultEither = for
      checkedFn <- checkedFnEither
      checkedArg <- checkedArgEither
      // Phase 2: Determine the type of this specific application
      appType <- determineApplicationType(app, checkedFn, module)
      // Ensure checkedFn is the right type for App.fn
      validFn <- checkedFn match
        case ref:      Ref => Right(ref)
        case innerApp: App => Right(innerApp)
        case other =>
          Left(
            List(
              TypeError.InvalidApplication(
                app,
                TypeRef(app.span, "invalid-fn"),
                TypeRef(app.span, "unknown"),
                phaseName
              )
            )
          )
    yield app.copy(fn = validFn, arg = checkedArg, typeSpec = Some(appType))

    resultEither

  /** Determine the type of an application based on its function and argument */
  private def determineApplicationType(
    app:       App,
    checkedFn: Term,
    module:    Module
  ): Either[List[TypeError], TypeSpec] =
    checkedFn match
      case ref: Ref if ref.resolvedAs.isDefined =>
        ref.resolvedAs.get match
          case fnDef: FnDef =>
            // Look up the function in the module to get its lowered typeSpec
            val updatedFn = module.members.collectFirst {
              case fn: FnDef if fn.name == fnDef.name => fn
            }
            val fnTypeSpec = updatedFn.flatMap(_.typeSpec).orElse(fnDef.typeSpec)

            // Functions always return their result type, regardless of partial application
            fnTypeSpec.toRight(
              List(TypeError.UnresolvableType(TypeRef(app.span, fnDef.name), app, phaseName))
            )

          case binOp: BinOpDef =>
            // Look up the operator in the module to get its lowered typeSpec
            val updatedOp = module.members.collectFirst {
              case op: BinOpDef if op.name == binOp.name => op
            }
            val opTypeSpec = updatedOp.flatMap(_.typeSpec).orElse(binOp.typeSpec)

            // Binary operators always return their result type, regardless of partial application
            opTypeSpec.toRight(
              List(TypeError.UnresolvableType(TypeRef(app.span, binOp.name), app, phaseName))
            )

          case unaryOp: UnaryOpDef =>
            // Look up the operator in the module to get its lowered typeSpec
            val updatedOp = module.members.collectFirst {
              case op: UnaryOpDef if op.name == unaryOp.name => op
            }
            val opTypeSpec = updatedOp.flatMap(_.typeSpec).orElse(unaryOp.typeSpec)

            // Unary operators always return their result type
            opTypeSpec.toRight(
              List(TypeError.UnresolvableType(TypeRef(app.span, unaryOp.name), app, phaseName))
            )

          case _ =>
            Left(
              List(
                TypeError.InvalidApplication(
                  app,
                  TypeRef(app.span, "unknown"),
                  TypeRef(app.span, "unknown"),
                  phaseName
                )
              )
            )

      case innerApp: App if innerApp.typeSpec.isDefined =>
        // The inner application already has a type (from partial application)
        Right(innerApp.typeSpec.get)

      case _ =>
        Left(
          List(
            TypeError.InvalidApplication(
              app,
              TypeRef(app.span, "unknown"),
              TypeRef(app.span, "unknown"),
              phaseName
            )
          )
        )

  /** Check function applications by collecting all arguments in a chain */
  private def checkApplication(app: App, module: Module): Either[List[TypeError], App] =
    checkApplicationWithContext(app, module, Map.empty)

  /** Validate type ascription against computed type */
  private def validateTypeAscription(node: Typeable, module: Module): List[TypeError] =
    (node.typeAsc, node.typeSpec) match
      case (Some(ascribed), Some(computed)) =>
        if areTypesCompatible(ascribed, computed, module) then Nil
        else List(TypeError.TypeMismatch(node, ascribed, computed, phaseName))
      case _ => Nil

  /** Check type compatibility (handles aliases, etc.) */
  private def areTypesCompatible(t1: TypeSpec, t2: TypeSpec, module: Module): Boolean =
    val resolved1 = resolveAliasChain(t1, module)
    val resolved2 = resolveAliasChain(t2, module)
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

  /** Check conditional with parameter context */
  private def checkConditionalWithContext(
    cond:         Cond,
    module:       Module,
    paramContext: Map[String, FnParam]
  ): Either[List[TypeError], Cond] =
    for
      checkedCond <- checkExprWithContext(cond.cond, module, paramContext)
      _ <- checkedCond.typeSpec match
        case Some(TypeRef(_, "Bool", _)) => Right(())
        case Some(other) =>
          Left(
            List(TypeError.TypeMismatch(checkedCond, TypeRef(cond.span, "Bool"), other, phaseName))
          )
        case None =>
          Left(List(TypeError.UnresolvableType(TypeRef(cond.span, "Bool"), checkedCond, phaseName)))
      checkedTrue <- checkExprWithContext(cond.ifTrue, module, paramContext)
      checkedFalse <- checkExprWithContext(cond.ifFalse, module, paramContext)
      trueType <- checkedTrue.typeSpec.toRight(
        List(TypeError.ConditionalBranchTypeUnknown(cond, phaseName))
      )
      falseType <- checkedFalse.typeSpec.toRight(
        List(TypeError.ConditionalBranchTypeUnknown(cond, phaseName))
      )
      _ <-
        if areTypesCompatible(trueType, falseType, module) then Right(())
        else
          Left(List(TypeError.ConditionalBranchTypeMismatch(cond, trueType, falseType, phaseName)))
    yield cond.copy(
      cond     = checkedCond,
      ifTrue   = checkedTrue,
      ifFalse  = checkedFalse,
      typeSpec = Some(trueType)
    )

  /** Check conditional expressions (both branches must match) */
  private def checkConditional(cond: Cond, module: Module): Either[List[TypeError], Cond] =
    checkConditionalWithContext(cond, module, Map.empty)

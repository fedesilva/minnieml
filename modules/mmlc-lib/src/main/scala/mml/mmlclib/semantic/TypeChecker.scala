package mml.mmlclib.semantic

import cats.implicits.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

object TypeChecker:
  private val phaseName = "mml.mmlclib.semantic.TypeChecker"

  /** Main entry point - process module and accumulate errors */
  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState =
    // First pass: compute signatures for all functions and operators
    val (signatureErrors, membersWithSignatures) = computeSignatures(state.module)
    
    // Second pass: check all members with signatures available
    val moduleWithSignatures = state.module.copy(members = membersWithSignatures)
    val initialState = (Vector.empty[TypeError], Vector.empty[Member])
    val (checkErrors, checkedMembers) = membersWithSignatures.foldLeft(initialState) {
      case ((accErrors, accMembers), member) =>
        val currentModule = moduleWithSignatures.copy(members = accMembers.toList ++ membersWithSignatures.dropWhile(_ != member))
        checkMember(member, currentModule) match
          case Left(errs) => (accErrors ++ errs, accMembers :+ member)
          case Right(newMember) => (accErrors, accMembers :+ newMember)
    }

    val allErrors = (signatureErrors ++ checkErrors).map(SemanticError.TypeCheckingError.apply)
    state.addErrors(allErrors.toList).withModule(state.module.copy(members = checkedMembers.toList))
    
  /** First pass: compute type signatures for all declarations */
  private def computeSignatures(module: Module): (Vector[TypeError], List[Member]) =
    val (errors, members) = module.members.foldLeft((Vector.empty[TypeError], Vector.empty[Member])) {
      case ((accErrors, accMembers), member) =>
        member match
          case fnDef: FnDef =>
            validateMandatoryAscriptions(fnDef) match
              case Nil =>
                computeFunctionType(fnDef) match
                  case Right(fnType) => (accErrors, accMembers :+ fnDef.copy(typeSpec = Some(fnType)))
                  case Left(errs) => (accErrors ++ errs, accMembers :+ fnDef)
              case errs => (accErrors ++ errs, accMembers :+ fnDef)
              
          case opDef: OpDef =>
            validateMandatoryAscriptions(opDef) match
              case Nil =>
                computeOperatorType(opDef) match
                  case Right(opType) =>
                    val newOp = opDef match
                      case b: BinOpDef => b.copy(typeSpec = Some(opType))
                      case u: UnaryOpDef => u.copy(typeSpec = Some(opType))
                    (accErrors, accMembers :+ newOp)
                  case Left(errs) => (accErrors ++ errs, accMembers :+ opDef)
              case errs => (accErrors ++ errs, accMembers :+ opDef)
              
          case other =>
            // Other members don't need signature computation
            (accErrors, accMembers :+ other)
    }
    (errors, members.toList)

  /** Validate and compute types for a member */
  private def checkMember(member: Member, module: Module): Either[List[TypeError], Member] = member match
    case fnDef: FnDef =>
      // Type signature already computed in first pass, just check the body
      for
        checkedBody <- checkExpr(fnDef.body, module, fnDef.typeAsc)
        _ <- (fnDef.typeAsc, checkedBody.typeSpec) match
          case (Some(expected), Some(actual)) if areTypesCompatible(expected, actual, module) => Right(())
          case (Some(expected), Some(actual)) => Left(List(TypeError.TypeMismatch(fnDef, expected, actual, phaseName)))
          case _ => Right(()) // Should be caught by other checks
      yield fnDef.copy(body = checkedBody)

    case opDef: OpDef =>
      // Type signature already computed in first pass, just check the body
      for
        checkedBody <- checkExpr(opDef.body, module, opDef.typeAsc)
        _ <- (opDef.typeAsc, checkedBody.typeSpec) match
          case (Some(expected), Some(actual)) if areTypesCompatible(expected, actual, module) => Right(())
          case (Some(expected), Some(actual)) => Left(List(TypeError.TypeMismatch(opDef, expected, actual, phaseName)))
          case _ => Right(()) // Should be caught by other checks
      yield opDef match
        case b: BinOpDef => b.copy(body = checkedBody)
        case u: UnaryOpDef => u.copy(body = checkedBody)

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
      val returnError = fnDef.typeAsc match
        case None => List(TypeError.MissingReturnType(fnDef, phaseName))
        case Some(_) => Nil
      paramErrors ++ returnError

    case opDef: BinOpDef =>
      val param1Error = opDef.param1.typeAsc match
        case None => List(TypeError.MissingOperatorParameterType(opDef.param1, opDef, phaseName))
        case Some(_) => Nil
      val param2Error = opDef.param2.typeAsc match
        case None => List(TypeError.MissingOperatorParameterType(opDef.param2, opDef, phaseName))
        case Some(_) => Nil
      val returnError = opDef.typeAsc match
        case None => List(TypeError.MissingOperatorReturnType(opDef, phaseName))
        case Some(_) => Nil
      param1Error ++ param2Error ++ returnError

    case opDef: UnaryOpDef =>
      val paramError = opDef.param.typeAsc match
        case None => List(TypeError.MissingOperatorParameterType(opDef.param, opDef, phaseName))
        case Some(_) => Nil
      val returnError = opDef.typeAsc match
        case None => List(TypeError.MissingOperatorReturnType(opDef, phaseName))
        case Some(_) => Nil
      paramError ++ returnError

    case _ =>
      Nil

  /** Compute typeSpec for functions from their ascriptions */
  private def computeFunctionType(fnDef: FnDef): Either[List[TypeError], TypeSpec] =
    val paramTypes = fnDef.params.flatMap(_.typeAsc)
    (fnDef.typeAsc, paramTypes) match
      case (Some(returnType), pt) if pt.length == fnDef.params.length =>
        Right(TypeFn(fnDef.span, pt, returnType))
      case _ =>
        // This case should be prevented by validateMandatoryAscriptions, but as a safeguard:
        val errors = validateMandatoryAscriptions(fnDef)
        Left(errors)

  private def computeOperatorType(opDef: OpDef): Either[List[TypeError], TypeSpec] =
    opDef match
      case op: BinOpDef =>
        (op.param1.typeAsc, op.param2.typeAsc, op.typeAsc) match
          case (Some(p1), Some(p2), Some(ret)) => Right(TypeFn(op.span, List(p1, p2), ret))
          case _ => Left(validateMandatoryAscriptions(op))
      case op: UnaryOpDef =>
        (op.param.typeAsc, op.typeAsc) match
          case (Some(p1), Some(ret)) => Right(TypeFn(op.span, List(p1), ret))
          case _ => Left(validateMandatoryAscriptions(op))

  /** Type check expressions using forward propagation */
  private def checkExpr(expr: Expr, module: Module, expectedType: Option[TypeSpec] = None): Either[List[TypeError], Expr] =
    expr.terms match
      case List(singleTerm) =>
        checkTerm(singleTerm, module, expectedType).map { checkedTerm =>
          expr.copy(terms = List(checkedTerm), typeSpec = checkedTerm.typeSpec)
        }
      case terms =>
        // This is an application chain. The `App` nodes are checked recursively.
        // We only need to check the root of the application.
        checkTerm(terms.last, module, expectedType).map { checkedApp =>
          expr.copy(terms = terms.dropRight(1) :+ checkedApp, typeSpec = checkedApp.typeSpec)
        }

  /** Type check individual terms */
  private def checkTerm(term: Term, module: Module, expectedType: Option[TypeSpec] = None): Either[List[TypeError], Term] = term match
    case lit: LiteralValue =>
      // Literals have their types defined directly, so they are already "checked"
      Right(lit)

    case ref: Ref =>
      // Look up the declaration in the current module to get the computed typeSpec
      ref.resolvedAs match
        case Some(param: FnParam) =>
          // For parameters, use their type ascription directly
          param.typeAsc match
            case Some(t) => Right(ref.copy(typeSpec = Some(t)))
            case None => Left(List(TypeError.UnresolvableType(TypeRef(ref.span, ref.name), ref, phaseName)))
        case Some(decl: Decl) => 
          // Find the member in the current module which has the computed typeSpec
          module.members.find(m => m.isInstanceOf[Decl] && m.asInstanceOf[Decl].name == decl.name) match
            case Some(updatedDecl: Decl) => 
              updatedDecl.typeSpec match
                case Some(t) => Right(ref.copy(typeSpec = Some(t)))
                case None => Left(List(TypeError.UnresolvableType(TypeRef(ref.span, "unknown"), ref, phaseName)))
            case _ => Left(List(TypeError.UnresolvableType(TypeRef(ref.span, ref.name), ref, phaseName)))
        case _ => Left(List(TypeError.UnresolvableType(TypeRef(ref.span, ref.name), ref, phaseName)))

    case app: App =>
      checkApplication(app, module)

    case cond: Cond =>
      checkConditional(cond, module)

    case group: TermGroup =>
      checkExpr(group.inner, module, expectedType).map(checkedInner => group.copy(inner = checkedInner))

    case hole: Hole =>
      expectedType match
        case Some(t) => Right(hole.copy(typeSpec = Some(t)))
        case None =>
          val dummyBnd = Bnd(
            visibility = MemberVisibility.Private,
            span = hole.span,
            name = "unknown",
            value = Expr(hole.span, List(hole)),
            typeAsc = None,
            typeSpec = None,
            docComment = None
          )
          Left(List(TypeError.UntypedHoleInBinding(dummyBnd, phaseName)))

    case other =>
      // Other term types do not require type checking at this stage
      Right(other)

  /** Check function applications by collecting all arguments in a chain */
  private def checkApplication(app: App, module: Module): Either[List[TypeError], App] =

    // 1. Collect all arguments and the root function from the App chain
    val (fnTerm, args) = collectArgsAndFunction(app)

    // 2. Check the root function and all arguments individually
    val checkedFnEither = checkTerm(fnTerm, module)
    val checkedArgsEither = args.traverse(checkExpr(_, module))

    for
      checkedFn <- checkedFnEither
      checkedArgs <- checkedArgsEither
      fnType <- checkedFn.typeSpec.toRight(List(TypeError.UnresolvableType(TypeRef(checkedFn.span, "unknown"), checkedFn, phaseName)))
      
      // 3. Get parameter types from the function's signature
      paramTypes = getParameterTypes(fnType)

      // 4. Validate argument count
      _ <- if paramTypes.length == checkedArgs.length then Right(())
           else if paramTypes.length < checkedArgs.length then Left(List(TypeError.OversaturatedApplication(app, paramTypes.length, checkedArgs.length, phaseName)))
           else Left(List(TypeError.UndersaturatedApplication(app, paramTypes.length, checkedArgs.length, phaseName)))

      // 5. Validate argument types
      _ <- paramTypes.zip(checkedArgs).traverse {
        case (expected, actual) =>
          actual.typeSpec match
            case Some(actualType) if areTypesCompatible(expected, actualType, module) => Right(())
            case Some(actualType) => Left(List(TypeError.TypeMismatch(actual, expected, actualType, phaseName)))
            case None => Left(List(TypeError.UnresolvableType(TypeRef(actual.span, "unknown"), actual, phaseName)))
      }

      // 6. Get the return type
      returnType <- getReturnType(fnType).toRight(List(TypeError.InvalidApplication(app, fnType, TypeRef(app.span, "unknown"), phaseName)))

    yield app.copy(typeSpec = Some(returnType))

  /** Helper to recursively collect arguments from a nested App chain */
  private def collectArgsAndFunction(app: App): (Term, List[Expr]) =
    def go(current: App, acc: List[Expr]): (Term, List[Expr]) =
      current.fn match
        case nextApp: App => go(nextApp, current.arg :: acc)
        case fn => (fn, current.arg :: acc)
    go(app, Nil)

  /** Validate type ascription against computed type */
  private def validateTypeAscription(node: Typeable, module: Module): List[TypeError] =
    (node.typeAsc, node.typeSpec) match
      case (Some(ascribed), Some(computed)) =>
        if areTypesCompatible(ascribed, computed, module) then Nil
        else List(TypeError.TypeMismatch(node, ascribed, computed, phaseName))
      case _ => Nil

  /** Check type compatibility (handles aliases, etc.) */
  private def areTypesCompatible(t1: TypeSpec, t2: TypeSpec, module: Module): Boolean =
    (resolveAliasChain(t1, module), resolveAliasChain(t2, module)) match
      case (TypeRef(_, name1, _), TypeRef(_, name2, _)) => name1 == name2
      case (TypeFn(_, p1, r1), TypeFn(_, p2, r2)) =>
        p1.length == p2.length &&
        p1.zip(p2).forall { case (pt1, pt2) => areTypesCompatible(pt1, pt2, module) } &&
        areTypesCompatible(r1, r2, module)
      case (TypeTuple(_, e1), TypeTuple(_, e2)) =>
        e1.length == e2.length &&
        e1.zip(e2).forall { case (et1, et2) => areTypesCompatible(et1, et2, module) }
      case (TypeUnit(_), TypeUnit(_)) => true
      case _ => false

  /** Follow alias chain to concrete type and update typeSpec along the way */
  private def resolveAliasChain(typeSpec: TypeSpec, module: Module): TypeSpec = typeSpec match
    case tr @ TypeRef(_, _, Some(ta: TypeAlias)) => resolveAliasChain(ta.typeRef, module)
    case other => other

  /** Check conditional expressions (both branches must match) */
  private def checkConditional(cond: Cond, module: Module): Either[List[TypeError], Cond] =
    for
      checkedCond <- checkExpr(cond.cond, module)
      _ <- checkedCond.typeSpec match
        case Some(TypeRef(_, "Bool", _)) => Right(())
        case Some(other) => Left(List(TypeError.TypeMismatch(checkedCond, TypeRef(cond.span, "Bool"), other, phaseName)))
        case None => Left(List(TypeError.UnresolvableType(TypeRef(cond.span, "Bool"), checkedCond, phaseName)))
      checkedTrue <- checkExpr(cond.ifTrue, module)
      checkedFalse <- checkExpr(cond.ifFalse, module)
      trueType <- checkedTrue.typeSpec.toRight(List(TypeError.ConditionalBranchTypeUnknown(cond, phaseName)))
      falseType <- checkedFalse.typeSpec.toRight(List(TypeError.ConditionalBranchTypeUnknown(cond, phaseName)))
      _ <- if areTypesCompatible(trueType, falseType, module) then Right(())
           else Left(List(TypeError.ConditionalBranchTypeMismatch(cond, trueType, falseType, phaseName)))
    yield cond.copy(
      cond = checkedCond,
      ifTrue = checkedTrue,
      ifFalse = checkedFalse,
      typeSpec = Some(trueType)
    )

  /** Extract return type from function type */
  private def getReturnType(fnType: TypeSpec): Option[TypeSpec] = fnType match
    case TypeFn(_, _, returnType) => Some(returnType)
    case _ => None

  /** Extract parameter types from function type */
  private def getParameterTypes(fnType: TypeSpec): List[TypeSpec] = fnType match
    case TypeFn(_, paramTypes, _) => paramTypes
    case _ => Nil

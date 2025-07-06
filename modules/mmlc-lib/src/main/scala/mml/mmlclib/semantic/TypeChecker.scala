package mml.mmlclib.semantic

import cats.implicits.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

object TypeChecker:
  private val phaseName = "mml.mmlclib.semantic.TypeChecker"

  /** Main entry point - process module and accumulate errors */
  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState =
    val initialState = (Vector.empty[TypeError], Vector.empty[Member])
    val (errors, newMembers) = state.module.members.foldLeft(initialState) {
      case ((accErrors, accMembers), member) =>
        val currentModule = state.module.copy(members = accMembers.toList)
        checkMember(member, currentModule) match
          case Left(errs) => (accErrors ++ errs, accMembers :+ member)
          case Right(newMember) => (accErrors, accMembers :+ newMember)
    }

    val allErrors = errors.map(SemanticError.TypeCheckingError.apply)
    state.addErrors(allErrors.toList).withModule(state.module.copy(members = newMembers.toList))

  /** Validate and compute types for a member */
  private def checkMember(member: Member, module: Module): Either[List[TypeError], Member] = member match
    case fnDef: FnDef =>
      validateMandatoryAscriptions(fnDef) match
        case Nil =>
          for
            fnType <- computeFunctionType(fnDef)
            checkedBody <- checkExpr(fnDef.body, module, fnDef.typeAsc)
            _ <- (fnDef.typeAsc, checkedBody.typeSpec) match
              case (Some(expected), Some(actual)) if areTypesCompatible(expected, actual, module) => Right(())
              case (Some(expected), Some(actual)) => Left(List(TypeError.TypeMismatch(fnDef, expected, actual, phaseName)))
              case _ => Right(()) // Should be caught by other checks
          yield fnDef.copy(body = checkedBody, typeSpec = Some(fnType))
        case errors => Left(errors)

    case opDef: OpDef =>
      validateMandatoryAscriptions(opDef) match
        case Nil =>
          for
            opType <- computeOperatorType(opDef)
            checkedBody <- checkExpr(opDef.body, module, opDef.typeAsc)
            _ <- (opDef.typeAsc, checkedBody.typeSpec) match
              case (Some(expected), Some(actual)) if areTypesCompatible(expected, actual, module) => Right(())
              case (Some(expected), Some(actual)) => Left(List(TypeError.TypeMismatch(opDef, expected, actual, phaseName)))
              case _ => Right(()) // Should be caught by other checks
          yield opDef match
            case b: BinOpDef => b.copy(body = checkedBody, typeSpec = Some(opType))
            case u: UnaryOpDef => u.copy(body = checkedBody, typeSpec = Some(opType))
        case errors => Left(errors)

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
    val checkedTermsEither = expr.terms.traverse(checkTerm(_, module, expectedType))

    checkedTermsEither.flatMap { checkedTerms =>
      // After checking individual terms, handle application chains
      // For now, we just pass the type of the last term up.
      // A more sophisticated check for application chains will be added.
      val newTypeSpec = checkedTerms.lastOption.flatMap(_.typeSpec)
      Right(expr.copy(terms = checkedTerms, typeSpec = newTypeSpec))
    }

  /** Type check individual terms */
  private def checkTerm(term: Term, module: Module, expectedType: Option[TypeSpec] = None): Either[List[TypeError], Term] = term match
    case lit: LiteralValue =>
      // Literals have their types defined directly, so they are already "checked"
      Right(lit)

    case ref: Ref =>
      // Resolve the reference and get its type
      ref.resolvedAs match
        case Some(decl: Decl) => Right(ref.copy(typeSpec = decl.typeSpec))
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

  /** Check function applications */
  private def checkApplication(app: App, module: Module): Either[List[TypeError], App] =
    checkTerm(app.fn, module).flatMap {
      case checkedFn: (Ref | App) =>
        for
          checkedArg <- checkExpr(app.arg, module)
          fnType <- checkedFn.typeSpec.toRight(List(TypeError.UnresolvableType(TypeRef(checkedFn.span, "unknown"), checkedFn, phaseName)))
          argType <- checkedArg.typeSpec.toRight(List(TypeError.UnresolvableType(TypeRef(checkedArg.span, "unknown"), checkedArg, phaseName)))
          returnType <- getReturnType(fnType).toRight(List(TypeError.InvalidApplication(app, fnType, argType, phaseName)))
        yield app.copy(fn = checkedFn, arg = checkedArg, typeSpec = Some(returnType))
      case other =>
        Left(List(TypeError.InvalidApplication(app, TypeRef(other.span, "Invalid function"), other.typeSpec.get, phaseName)))
    }

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

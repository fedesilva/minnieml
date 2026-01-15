package mml.mmlclib.codegen.emitter.expression

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.abis.lowerNativeArgs
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenState,
  CompileResult,
  emitCall,
  getLlvmType,
  getMmlTypeName
}

/** Collects all arguments from nested App nodes (handles curried applications).
  *
  * For example, `mult 2 2` is represented as App(App(Ref(mult), 2), 2).
  */
def collectArgsAndFunction(
  app:  App,
  args: List[Expr] = List.empty
): (Ref | Lambda, List[Expr]) =
  app.fn match
    case ref:       Ref => (ref, app.arg :: args)
    case nestedApp: App => collectArgsAndFunction(nestedApp, app.arg :: args)
    case lambda:    Lambda => (lambda, app.arg :: args)

/** Compiles an immediate lambda application (from let-expression desugaring).
  *
  * `let x = E; body` desugars to `App(Lambda([x], body), E)`.
  */
def compileLambdaApp(
  lambda:        Lambda,
  allArgs:       List[Expr],
  state:         CodeGenState,
  functionScope: Map[String, (Int, String)],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  if lambda.params.size == 1 && allArgs.size == 1 then
    val param = lambda.params.head
    val arg   = allArgs.head
    for
      argRes <- compileExpr(arg, state, functionScope)
      // If arg is a literal, materialize it into a register
      // (functionScope lookups assume values are in registers)
      (bindingReg, stateAfterBinding) =
        if argRes.isLiteral then
          val reg = argRes.state.nextRegister
          val newState = argRes.state
            .emit(s"  %$reg = add i64 0, ${argRes.register}")
            .withRegister(reg + 1)
          (reg, newState)
        else (argRes.register, argRes.state)
      // Extend function scope with the binding (register, typeName)
      extendedScope = functionScope + (param.name -> (bindingReg, argRes.typeName))
      // Compile lambda body with extended scope
      bodyRes <- compileExpr(lambda.body, stateAfterBinding, extendedScope)
    // Preserve exit block from argument if body doesn't have one
    // (needed when arg contains a conditional like `let x = if cond then a else b end`)
    yield bodyRes.copy(exitBlock = bodyRes.exitBlock.orElse(argRes.exitBlock))
  else
    Left(
      CodeGenError(
        "Immediate lambda application with multiple params/args not yet supported",
        Some(lambda)
      )
    )

/** Compiles a native operator application using its template.
  *
  * Handles both binary and unary native operators.
  */
def compileNativeOp(
  fnRef:         Ref,
  tpl:           String,
  allArgs:       List[Expr],
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, (Int, String)],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  allArgs match
    case List(leftArg, rightArg) =>
      compileBinaryNativeOp(fnRef, tpl, leftArg, rightArg, state, functionScope, compileExpr)
    case List(operandArg) =>
      compileUnaryNativeOp(fnRef, tpl, operandArg, state, functionScope, compileExpr)
    case _ =>
      Left(
        CodeGenError(
          s"Native operator called with wrong number of arguments: ${allArgs.length}",
          Some(app)
        )
      )

private def compileBinaryNativeOp(
  fnRef:         Ref,
  tpl:           String,
  leftArg:       Expr,
  rightArg:      Expr,
  state:         CodeGenState,
  functionScope: Map[String, (Int, String)],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  for
    leftRes <- compileExpr(leftArg, state, functionScope)
    rightRes <- compileExpr(rightArg, leftRes.state, functionScope)

    resultReg = rightRes.state.nextRegister
    leftOp    = if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
    rightOp   = if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"

    llvmType <- leftArg.typeSpec match
      case Some(typeSpec) => getLlvmType(typeSpec, rightRes.state)
      case None =>
        Left(CodeGenError(s"Missing type information for binary operator operand", Some(leftArg)))

    instruction = substituteTemplate(tpl, llvmType, List(leftOp, rightOp))
    line        = s"  %$resultReg = $instruction"
    finalState  = rightRes.state.withRegister(resultReg + 1).emit(line)

    typeName <- getMmlTypeForOp(fnRef) match
      case Some(t) => Right(t)
      case None =>
        Left(
          CodeGenError(
            s"Could not determine return type for binary operator '${fnRef.name}'",
            Some(fnRef)
          )
        )
  yield CompileResult(resultReg, finalState, false, typeName)

private def compileUnaryNativeOp(
  fnRef:         Ref,
  tpl:           String,
  operandArg:    Expr,
  state:         CodeGenState,
  functionScope: Map[String, (Int, String)],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  for
    operandRes <- compileExpr(operandArg, state, functionScope)

    resultReg = operandRes.state.nextRegister
    operandOp =
      if operandRes.isLiteral then operandRes.register.toString else s"%${operandRes.register}"

    llvmType <- operandArg.typeSpec match
      case Some(typeSpec) => getLlvmType(typeSpec, operandRes.state)
      case None =>
        Left(
          CodeGenError(s"Missing type information for unary operator operand", Some(operandArg))
        )

    instruction = substituteTemplate(tpl, llvmType, List(operandOp))
    line        = s"  %$resultReg = $instruction"
    finalState  = operandRes.state.withRegister(resultReg + 1).emit(line)

    typeName <- getMmlTypeForOp(fnRef) match
      case Some(t) => Right(t)
      case None =>
        Left(
          CodeGenError(
            s"Could not determine return type for unary operator '${fnRef.name}'",
            Some(fnRef)
          )
        )
  yield CompileResult(resultReg, finalState, false, typeName)

/** Checks if all arguments are unit literals. */
def allArgsAreUnitLiterals(allArgs: List[Expr]): Boolean =
  allArgs.forall { arg =>
    arg.terms match
      case List(_: LiteralUnit) => true
      case _ => false
  }

/** Checks if this is a nullary function call with unit arguments. */
def isNullaryWithUnitArgs(fnRef: Ref, allArgs: List[Expr], resolvables: ResolvablesIndex): Boolean =
  fnRef.resolvedId.flatMap(resolvables.lookup) match
    case Some(bnd: Bnd) if bnd.meta.exists(_.arity == CallableArity.Nullary) =>
      allArgsAreUnitLiterals(allArgs)
    case _ => false

/** Compiles a nullary function call (skips unit arguments). */
def compileNullaryCall(
  fnRef: Ref,
  app:   App,
  state: CodeGenState
): Either[CodeGenError, CompileResult] =
  val resultReg = state.nextRegister

  val fnReturnTypeResult = app.typeSpec match
    case Some(typeSpec) => getLlvmType(typeSpec, state)
    case None =>
      Left(
        CodeGenError(
          s"Missing return type information for function application '${fnRef.name}' - TypeChecker should have provided this",
          Some(app)
        )
      )

  fnReturnTypeResult.flatMap { fnReturnType =>
    val fnName = getResolvedName(fnRef, state)
    if fnReturnType == "void" then
      val callLine = emitCall(None, None, fnName, List.empty)
      Right(CompileResult(0, state.emit(callLine), false, "Unit"))
    else
      val callLine = emitCall(Some(resultReg), Some(fnReturnType), fnName, List.empty)
      app.typeSpec match
        case Some(ts) =>
          getMmlTypeName(ts) match
            case Some(typeName) =>
              Right(
                CompileResult(
                  resultReg,
                  state.withRegister(resultReg + 1).emit(callLine),
                  false,
                  typeName
                )
              )
            case None =>
              Left(
                CodeGenError(
                  s"Could not determine MML type name for function application result from spec: $ts",
                  Some(app)
                )
              )
        case None =>
          Left(
            CodeGenError(
              s"Missing return type information for function application '${fnRef.name}'",
              Some(app)
            )
          )
  }

/** Compiles a regular function call with arguments. */
def compileRegularCall(
  fnRef:         Ref,
  allArgs:       List[Expr],
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, (Int, String)],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  // Compile all arguments
  compileArgs(allArgs, state, functionScope, compileExpr).flatMap {
    case (compiledArgs, finalState) =>
      // Get function return type from the application's typeSpec
      val fnReturnTypeResult = app.typeSpec match
        case Some(typeSpec) => getLlvmType(typeSpec, finalState)
        case None =>
          Left(
            CodeGenError(
              s"Missing return type information for function application '${fnRef.name}' - TypeChecker should have provided this",
              Some(app)
            )
          )

      fnReturnTypeResult.flatMap { fnReturnType =>
        // Check for function template (for LLVM intrinsics like llvm.sqrt)
        getFunctionTemplate(fnRef.resolvedId.flatMap(finalState.resolvables.lookup)) match
          case Some(tpl) =>
            compileFunctionWithTemplate(fnRef, tpl, compiledArgs, app, finalState)
          case None =>
            compileStandardCall(fnRef, compiledArgs, fnReturnType, app, finalState)
      }
  }

/** Compiles all arguments to a function call. */
private def compileArgs(
  allArgs:       List[Expr],
  state:         CodeGenState,
  functionScope: Map[String, (Int, String)],
  compileExpr:   ExprCompiler
): Either[CodeGenError, (List[(String, String)], CodeGenState)] =
  allArgs.foldLeft((List.empty[(String, String)], state).asRight[CodeGenError]) {
    case (Right((compiledArgs, currentState)), arg) =>
      compileExpr(arg, currentState, functionScope).flatMap { argRes =>
        val argOp = if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"

        arg.typeSpec match
          case Some(typeSpec) =>
            getLlvmType(typeSpec, argRes.state) match
              case Right(llvmType) =>
                // Skip void/Unit args - they can't be passed in LLVM
                if llvmType == "void" then Right((compiledArgs, argRes.state))
                else Right((compiledArgs :+ (argOp, llvmType), argRes.state))
              case Left(err) => Left(err)
          case None =>
            Left(
              CodeGenError(
                s"Missing type information for function argument - TypeChecker should have provided this",
                Some(arg)
              )
            )
      }
    case (Left(err), _) => Left(err)
  }

/** Compiles a function with inline template (LLVM intrinsics like llvm.sqrt). */
private def compileFunctionWithTemplate(
  fnRef:        Ref,
  tpl:          String,
  compiledArgs: List[(String, String)],
  app:          App,
  state:        CodeGenState
): Either[CodeGenError, CompileResult] =
  val resultReg = state.nextRegister
  val instruction = compiledArgs match
    case List((argOp, argType)) =>
      // Single arg: use %operand (like unary operators)
      tpl.replace("%type", argType).replace("%operand", argOp)
    case args =>
      // Multiple args: use %operand1, %operand2, ... (like binary operators)
      args.zipWithIndex
        .foldLeft(tpl) { case (t, ((argOp, argType), i)) =>
          t.replace(s"%operand${i + 1}", argOp).replace(s"%type${i + 1}", argType)
        }
        .replace("%type", args.headOption.map(_._2).getOrElse(""))

  val line = s"  %$resultReg = $instruction"
  app.typeSpec.flatMap(getMmlTypeName) match
    case Some(typeName) =>
      Right(CompileResult(resultReg, state.withRegister(resultReg + 1).emit(line), false, typeName))
    case None =>
      Left(
        CodeGenError(s"Could not determine return type for function '${fnRef.name}'", Some(app))
      )

/** Compiles a standard function call (non-template). */
private def compileStandardCall(
  fnRef:        Ref,
  compiledArgs: List[(String, String)],
  fnReturnType: String,
  app:          App,
  state:        CodeGenState
): Either[CodeGenError, CompileResult] =
  val isNative = fnRef.resolvedId.flatMap(state.resolvables.lookup).exists {
    case bnd: Bnd => isNativeBinding(bnd)
    case _ => false
  }
  val (finalArgs, stateAfterSplit) =
    if isNative then lowerNativeArgs(compiledArgs, state)
    else (compiledArgs, state)
  val resultReg = stateAfterSplit.nextRegister
  val args      = finalArgs.map { case (value, typ) => (typ, value) }
  val fnName    = getResolvedName(fnRef, stateAfterSplit)

  if fnReturnType == "void" then
    val callLine = emitCall(None, None, fnName, args)
    Right(CompileResult(0, stateAfterSplit.emit(callLine), false, "Unit"))
  else
    val callLine = emitCall(Some(resultReg), Some(fnReturnType), fnName, args)
    app.typeSpec match
      case Some(ts) =>
        getMmlTypeName(ts) match
          case Some(typeName) =>
            Right(
              CompileResult(
                resultReg,
                stateAfterSplit.withRegister(resultReg + 1).emit(callLine),
                false,
                typeName
              )
            )
          case None =>
            Left(CodeGenError(s"Could not determine MML type name for result: $ts", Some(app)))
      case None =>
        Left(CodeGenError(s"Missing return type for function '${fnRef.name}'", Some(app)))

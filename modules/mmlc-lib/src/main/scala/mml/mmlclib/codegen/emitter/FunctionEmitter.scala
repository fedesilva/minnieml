package mml.mmlclib.codegen.emitter

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.compileExpr

/** Handles code generation for function definitions and applications. */

/** Compiles a binding (variable declaration).
  *
  * For literal initializations, emits a direct global assignment. For non-literal initializations,
  * emits a global initializer function.
  *
  * IMPORTANT: To avoid duplicating computation at the top level, if the binding is non-literal, we
  * discard the instructions produced by the first compile of the expression and recompile it within
  * the initializer function.
  *
  * @param bnd
  *   the binding to compile
  * @param state
  *   the current code generation state (before compiling the binding)
  * @return
  *   Either a CodeGenError or the updated CodeGenState.
  */
def compileBinding(bnd: Bnd, state: CodeGenState): Either[CodeGenError, CodeGenState] =
  val origState = state
  bnd.typeAsc.map(getLlvmType(_, state)) match
    case Some(Right(llvmType)) =>
      compileExpr(bnd.value, state).flatMap { compileRes =>
        if compileRes.isLiteral then
          Right(compileRes.state.emit(s"@${bnd.name} = global $llvmType ${compileRes.register}"))
        else
          // Discard the instructions from the initial compilation by using the original state.
          val initFnName = s"_init_global_${bnd.name}"
          val state2 = origState
            .emit(s"@${bnd.name} = global $llvmType 0")
            .emit(s"define internal void @$initFnName() {")
            .emit(s"entry:")
          compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
            compileRes2.state
              .emit(s"  store $llvmType %${compileRes2.register}, $llvmType* @${bnd.name}")
              .emit("  ret void")
              .emit("}")
              .emit("")
              .addInitializer(initFnName)
          }
      }
    case Some(Left(err)) => Left(err)
    case None => Left(CodeGenError(s"Type annotation missing for binding '${bnd.name}'", Some(bnd)))

/** Compiles a Bnd(Lambda) into LLVM IR function.
  *
  * Handles functions and operators that are represented as Bnd(Lambda) with BindingMeta.
  */
def compileBndLambda(
  bnd:                Bnd,
  lambda:             Lambda,
  state:              CodeGenState,
  returnType:         String,
  paramTypes:         List[String],
  overrideReturnType: Option[String] = None,
  overrideReturnLine: Option[String] = None
): Either[CodeGenError, CodeGenState] =
  // Filter out Unit params (void) - they can't be passed in LLVM
  // Keep params and types in sync by filtering together
  val filteredParamsWithTypes = lambda.params
    .zip(paramTypes)
    .filter { case (_, typ) => typ != "void" }

  val filteredParams     = filteredParamsWithTypes.map(_._1)
  val filteredParamTypes = filteredParamsWithTypes.map(_._2)

  // Generate function declaration with parameters
  val paramDecls = filteredParamTypes.zipWithIndex
    .map { case (typ, idx) => s"$typ %$idx" }
    .mkString(", ")

  val functionReturn = overrideReturnType.getOrElse(returnType)
  val functionDecl   = s"define $functionReturn @${bnd.name}($paramDecls) {"
  val entryLine      = "entry:"

  // Setup function body state with initial lines
  val bodyState = state
    .emit(functionDecl)
    .emit(entryLine)
    .withRegister(0) // Reset register counter for local function scope

  // Create a scope map for lambda parameters
  val paramScope = filteredParams
    .zip(filteredParamTypes)
    .zipWithIndex
    .map { case ((param, typ), idx) =>
      val regNum    = idx
      val allocLine = s"  %${param.name}_ptr = alloca $typ"
      val storeLine = s"  store $typ %$idx, $typ* %${param.name}_ptr"
      val loadLine  = s"  %${regNum} = load $typ, $typ* %${param.name}_ptr"

      // We'll emit the alloca/store/load sequence for each parameter
      bodyState.emit(allocLine).emit(storeLine).emit(loadLine)

      (param.name, regNum)
    }
    .toMap

  // Register count starts after parameter setup
  val updatedState = bodyState.withRegister(filteredParams.size)

  // Compile the lambda body with the parameter scope
  compileExpr(lambda.body, updatedState, paramScope).flatMap { bodyRes =>
    val returnTypeLine = overrideReturnType.getOrElse(returnType)

    val returnLine = overrideReturnLine.getOrElse {
      if returnTypeLine == "void" then "  ret void"
      else
        val returnOp =
          if bodyRes.isLiteral then bodyRes.register.toString else s"%${bodyRes.register}"
        s"  ret $returnTypeLine $returnOp"
    }

    // Close function and add empty line
    Right(
      bodyRes.state
        .emit(returnLine)
        .emit("}")
        .emit("")
    )
  }

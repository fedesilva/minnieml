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
  compileExpr(bnd.value, state).flatMap { compileRes =>
    if compileRes.isLiteral then
      Right(compileRes.state.emit(s"@${bnd.name} = global i32 ${compileRes.register}"))
    else
      // Discard the instructions from the initial compilation by using the original state.
      val initFnName = s"_init_global_${bnd.name}"
      val state2 = origState
        .emit(s"@${bnd.name} = global i32 0")
        .emit(s"define internal void @$initFnName() {")
        .emit(s"entry:")
      compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
        compileRes2.state
          .emit(s"  store i32 %${compileRes2.register}, i32* @${bnd.name}")
          .emit("  ret void")
          .emit("}")
          .emit("")
          .addInitializer(initFnName)
      }
  }

/** Compiles a function definition into LLVM IR.
  *
  * Creates a proper function definition with parameters, compiles the body, and adds a return
  * instruction.
  *
  * @param fn
  *   the function definition to compile
  * @param state
  *   the current code generation state
  * @return
  *   Either a CodeGenError or the updated CodeGenState.
  */
def compileFnDef(fn: FnDef, state: CodeGenState): Either[CodeGenError, CodeGenState] =
  // For now, we'll assume i32 return type and i32 parameters for simplicity
  // In a more complete implementation, we would derive types from typeSpec/typeAsc

  // Generate function declaration with parameters
  val paramDecls = fn.params.zipWithIndex
    .map { case (param, idx) =>
      s"i32 %${idx}"
    }
    .mkString(", ")

  val functionDecl = s"define i32 @${fn.name}($paramDecls) {"
  val entryLine    = "entry:"

  // Setup function body state with initial lines
  val bodyState = state
    .emit(functionDecl)
    .emit(entryLine)
    .withRegister(0) // Reset register counter for local function scope

  // Create a scope map for function parameters
  val paramScope = fn.params.zipWithIndex.map { case (param, idx) =>
    val regNum    = idx
    val allocLine = s"  %${param.name}_ptr = alloca i32"
    val storeLine = s"  store i32 %${idx}, i32* %${param.name}_ptr"
    val loadLine  = s"  %${regNum} = load i32, i32* %${param.name}_ptr"

    // We'll emit the alloca/store/load sequence for each parameter
    bodyState.emit(allocLine).emit(storeLine).emit(loadLine)

    (param.name, regNum)
  }.toMap

  // Register count starts after parameter setup
  val updatedState = bodyState.withRegister(fn.params.size)

  // Compile the function body with the parameter scope
  compileExpr(fn.body, updatedState, paramScope).flatMap { bodyRes =>
    // Add return instruction with the result of the function body
    val returnOp =
      if bodyRes.isLiteral then bodyRes.register.toString
      else s"%${bodyRes.register}"

    val returnLine = s"  ret i32 ${returnOp}"

    // Close function and add empty line
    Right(
      bodyRes.state
        .emit(returnLine)
        .emit("}")
        .emit("")
    )
  }

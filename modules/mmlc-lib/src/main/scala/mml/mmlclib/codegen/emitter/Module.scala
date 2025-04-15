package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.{Bnd, FnDef, Module, NativeImpl}

/** Helper for string escaping */
private def escapeString(str: String): String = {
  str.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c => c.toString
  }
}

/** Main entry point for LLVM IR emission.
  *
  * Provides module-level code emission functionality.
  */
def emitModule(module: Module): Either[CodeGenError, String] = {
  // Setup the initial state with the module header and native type definition
  // We don't emit these directly - instead we add them to our state tracking
  val initialState = CodeGenState()
    .withModuleHeader(module.name)
    .withNativeType("String")
  // Function declarations will come from @native functions in the module

  // Process all members using the initialState that tracks declarations
  val processedState = module.members
    .foldLeft(initialState.asRight[CodeGenError]) { (stateE, member) =>
      stateE.flatMap { state =>
        member match {
          case bnd: Bnd => emitBinding(bnd, state)
          case fn:  FnDef => emitFnDef(fn, state)
          case _ => state.asRight
        }
      }
    }

  // Construct the final output with all components in the proper order
  processedState.map { finalState =>
    // Assemble the full output in the correct order
    val output = new StringBuilder()

    // 1. Module header
    finalState.moduleHeader.foreach(output.append)

    // 2. Type definitions
    if finalState.nativeTypes.nonEmpty then
      output.append("; Native type definitions\n")
      finalState.nativeTypes.values.foreach(typeDef => output.append(typeDef).append('\n'))
      output.append('\n')

    // 3. String constants
    if finalState.stringConstants.nonEmpty then
      output.append("; String constants\n")
      finalState.stringConstants.foreach { case (name, content) =>
        val escaped = escapeString(content)
        output.append(
          s"@$name = private constant [${content.length} x i8] c\"$escaped\", align 1\n"
        )
      }
      output.append('\n')

    // 4. Function declarations
    if finalState.functionDeclarations.nonEmpty then
      output.append("; External functions\n")
      finalState.functionDeclarations.values.foreach(decl => output.append(decl).append('\n'))
      output.append('\n')

    // 5. Function definitions and other code
    output.append(finalState.output.reverse.mkString("\n"))

    // 6. Global initializers
    if finalState.initializers.nonEmpty then
      val initSize = finalState.initializers.size
      output.append(
        s"\n@llvm.global_ctors = appending global [$initSize x { i32, void ()*, i8* }] [\n"
      )

      val initStrings = finalState.initializers.reverse.map { fnName =>
        s"  { i32, void ()*, i8* } { i32 65535, void ()* @$fnName, i8* null }"
      }

      output.append(initStrings.mkString(",\n"))
      output.append("\n]\n")

    // No more regex fixes needed as all LLVM IR is generated correctly
    output.toString()
  }
}

/** Emits a binding (variable declaration).
  *
  * For literal initializations, emits a direct global assignment. For non-literal initializations,
  * emits a global initializer function.
  *
  * IMPORTANT: To avoid duplicating computation at the top level, if the binding is non-literal, we
  * discard the instructions produced by the first emit of the expression and reemit it within the
  * initializer function.
  *
  * @param bnd
  *   the binding to emit
  * @param state
  *   the current code generation state (before emitting the binding)
  * @return
  *   Either a CodeGenError or the updated CodeGenState.
  */
private def emitBinding(bnd: Bnd, state: CodeGenState): Either[CodeGenError, CodeGenState] = {
  val origState = state
  compileExpr(bnd.value, state).flatMap { compileRes =>
    if compileRes.isLiteral then {
      // Check for string literals
      if compileRes.typeName == "String" then {
        val stringType = state.llvmTypeForNative("String")
        val stringData = compileRes.register.toString
        Right(compileRes.state.emit(emitGlobalVariable(bnd.name, stringType, stringData)))
      } else
        Right(
          compileRes.state.emit(emitGlobalVariable(bnd.name, "i32", compileRes.register.toString))
        )
    } else {
      // Discard the instructions from the initial compilation by using the original state.
      val initFnName = s"_init_global_${bnd.name}"
      val state2 = origState
        .emit(emitGlobalVariable(bnd.name, "i32", "0"))
        .emit(s"define internal void @$initFnName() {")
        .emit(s"entry:")
      compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
        compileRes2.state
          .emit(emitStore(s"%${compileRes2.register}", "i32", s"@${bnd.name}"))
          .emit("  ret void")
          .emit("}")
          .emit("")
          .addInitializer(initFnName)
      }
    }
  }
}

/** Emits a function definition into LLVM IR.
  *
  * Creates a proper function definition with parameters, emits the body, and adds a return
  * instruction.
  *
  * @param fn
  *   the function definition to emit
  * @param state
  *   the current code generation state
  * @return
  *   Either a CodeGenError or the updated CodeGenState.
  */
private def emitFnDef(fn: FnDef, state: CodeGenState): Either[CodeGenError, CodeGenState] = {
  // Check if this is a native function implementation
  fn.body.terms match {
    case List(NativeImpl(_, _, _)) => {
      // Determine parameter and return types
      val returnType = fn.typeSpec
        .map(t => getNativeType(t.toString(), state))
        .orElse(fn.typeAsc.map(t => getNativeType(t.toString(), state)))
        .getOrElse("i32")

      val paramTypes = fn.params.map { param =>
        param.typeSpec
          .map(t => getNativeType(t.toString(), state))
          .orElse(param.typeAsc.map(t => getNativeType(t.toString(), state)))
          .getOrElse("i32")
      }

      // Add the function declaration to state rather than emitting it directly
      // This ensures each declaration happens exactly once
      Right(state.withFunctionDeclaration(fn.name, returnType, paramTypes))
    }

    case _ => {
      // For normal functions, emit the full definition
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

      // Emit the function body with the parameter scope
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
    }
  }
}

/** Helper to get the LLVM IR type from a native type name */
private def getNativeType(typeName: String, state: CodeGenState): String = {
  // Extract the actual type name from things like "String" or "String => String"
  val baseType = typeName.trim.split("[ =]").head
  state.llvmTypeForNative(baseType)
}

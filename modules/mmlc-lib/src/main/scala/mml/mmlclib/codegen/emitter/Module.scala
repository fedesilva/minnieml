package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.{Bnd, FnDef, Module, NativeImpl, NativeStruct, NativeType, TypeDef}

/** Main entry point for LLVM IR emission.
  *
  * Provides module-level code emission functionality.
  */
def emitModule(module: Module): Either[CodeGenError, String] = {
  // Setup the initial state with the module header
  val initialState = CodeGenState()
    .withModuleHeader(module.name)

  // Collect all TypeDef members with native type specifications
  // Currently we only emit LLVM type definitions for native types
  // Future: Add handlers for enums, records, and other MML type constructs
  val typeDefs = module.members.collect {
    case td: TypeDef if td.typeSpec.exists(_.isInstanceOf[NativeType]) => td
  }

  // Generate LLVM type definitions for all native structs.
  // Primitives and pointers do not require forward declarations.
  val stateWithTypes = typeDefs.foldLeft(initialState.asRight[CodeGenError]) { (stateE, typeDef) =>
    stateE.flatMap { state =>
      typeDef.typeSpec match {
        // Only emit definitions for native structs
        case Some(nativeStruct: NativeStruct) =>
          nativeTypeToLlvmDef(typeDef.name, nativeStruct, state).map { llvmTypeDef =>
            state.copy(nativeTypes = state.nativeTypes + (typeDef.name -> llvmTypeDef))
          }
        // Other native types (primitives, pointers) are ignored here
        case _ => state.asRight
      }
    }
  }

  // Process all members using the state that includes type definitions
  val processedState = stateWithTypes.flatMap { stateWithTypeDefs =>
    module.members
      .foldLeft(stateWithTypeDefs.asRight[CodeGenError]) { (stateE, member) =>
        stateE.flatMap { state =>
          member match {
            case bnd: Bnd => emitBinding(bnd, state)
            case fn:  FnDef => emitFnDef(fn, state)
            case _ => state.asRight
          }
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
        // FIXME: harcoded use of string, should be resolved by the
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
  // Get the return type from the function's type annotation (required)
  val returnTypeE = fn.typeAsc
    .map(getLlvmType(_, state))
    .getOrElse(Left(CodeGenError(s"Missing return type annotation for function '${fn.name}'")))

  returnTypeE.flatMap { returnType =>
    // Get parameter types
    val paramTypesE = fn.params.traverse { param =>
      param.typeAsc
        .map(getLlvmType(_, state))
        .getOrElse(Left(CodeGenError(s"Missing type for param '${param.name}' in fn '${fn.name}'")))
    }

    paramTypesE.flatMap { paramTypes =>
      // Check if this is a native function implementation
      fn.body.terms match {
        case List(NativeImpl(_, _, _, _)) =>
          // Add the function declaration to state rather than emitting it directly
          // This ensures each declaration happens exactly once
          Right(state.withFunctionDeclaration(fn.name, returnType, paramTypes))

        case _ =>
          // For normal functions, delegate to the FunctionEmitter
          compileFnDef(fn, state, returnType, paramTypes)
      }
    }
  }
}

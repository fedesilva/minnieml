package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*

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
    case td: TypeDef if td.typeSpec.exists(_.isInstanceOf[NativeType]) =>
      td
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

/** Emits a binding (variable declaration) or function/operator.
  *
  * For Bnd with Lambda (functions/operators), delegates to function emission. For literal
  * initializations, emits a direct global assignment. For non-literal initializations, emits a
  * global initializer function.
  *
  * @param bnd
  *   the binding to emit
  * @param state
  *   the current code generation state (before emitting the binding)
  * @return
  *   Either a CodeGenError or the updated CodeGenState.
  */
private def emitBinding(bnd: Bnd, state: CodeGenState): Either[CodeGenError, CodeGenState] = {
  // Check if this is a function/operator (Bnd with Lambda)
  bnd.value.terms match {
    case List(lambda: Lambda) if bnd.meta.isDefined =>
      // This is a function or operator with meta - emit as function
      emitBndLambda(bnd, lambda, state)
    case List(lambda: Lambda) =>
      // Lambda without meta (e.g., from eta-expansion of partial application)
      // Emit as a function using the Bnd's name
      emitBndLambda(bnd, lambda, state)
    case _ =>
      // Regular value binding
      emitValueBinding(bnd, state)
  }
}

/** Emits a Bnd(Lambda) as a function definition.
  */
private def emitBndLambda(
  bnd:    Bnd,
  lambda: Lambda,
  state:  CodeGenState
): Either[CodeGenError, CodeGenState] = {
  val fnName = bnd.name
  val fnTypeE = bnd.typeSpec
    .collect { case t: TypeFn => t }
    .toRight(
      CodeGenError(s"Missing function type specification for '${fnName}'", Some(bnd))
    )

  fnTypeE.flatMap { fnType =>
    val returnTypeE = getLlvmType(fnType.returnType, state)
    val paramTypesE = fnType.paramTypes.traverse(getLlvmType(_, state))

    (returnTypeE, paramTypesE).tupled.flatMap { case (returnType, paramTypes) =>
      // Filter out void/Unit params - they can't be passed in LLVM
      val filteredParamTypes  = paramTypes.filter(_ != "void")
      val isMainUnit          = fnName == "main" && returnType == "void"
      val effectiveReturnType = if isMainUnit then "i64" else returnType
      val overrideReturnLine  = if isMainUnit then Some("  ret i64 0") else None

      // Check if this is a native function implementation
      lambda.body.terms match {
        case List(NativeImpl(_, _, _, _)) =>
          // Add the function declaration to state
          Right(state.withFunctionDeclaration(fnName, returnType, filteredParamTypes))

        case _ =>
          // For normal functions, compile the lambda body
          compileBndLambda(
            bnd,
            lambda,
            state,
            effectiveReturnType,
            filteredParamTypes,
            overrideReturnType = Some(effectiveReturnType).filter(_ => isMainUnit),
            overrideReturnLine = overrideReturnLine
          )
      }
    }
  }
}

/** Emits a regular value binding.
  */
private def emitValueBinding(bnd: Bnd, state: CodeGenState): Either[CodeGenError, CodeGenState] = {
  // Check if binding value is a direct literal at AST level
  bnd.value.terms match {
    case List(term) =>
      term match {
        case lit: LiteralString =>
          // Generate static String global: @a = global %String { i64 4, ptr @str.0 }
          val (newState, constName) = state.addStringConstant(lit.value)
          val llvmTypeE = bnd.typeSpec match {
            case Some(typeSpec) => getLlvmType(typeSpec, newState)
            case None =>
              Left(
                CodeGenError(
                  s"Missing type specification for string literal in binding '${bnd.name}'",
                  Some(bnd)
                )
              )
          }
          llvmTypeE.map { llvmType =>
            val staticValue = s"{ i64 ${lit.value.length}, ptr @$constName }"
            newState.emit(emitGlobalVariable(bnd.name, llvmType, staticValue))
          }

        case lit: LiteralInt =>
          // Generate static int global: @a = global i64 42
          val llvmTypeE = bnd.typeSpec match {
            case Some(typeSpec) => getLlvmType(typeSpec, state)
            case None =>
              Left(
                CodeGenError(
                  s"Missing type specification for int literal in binding '${bnd.name}'",
                  Some(bnd)
                )
              )
          }
          llvmTypeE.map { llvmType =>
            state.emit(emitGlobalVariable(bnd.name, llvmType, lit.value.toString))
          }

        case lit: LiteralBool =>
          // Generate static bool global: @a = global i1 true
          val llvmTypeE = bnd.typeSpec match {
            case Some(typeSpec) => getLlvmType(typeSpec, state)
            case None =>
              Left(
                CodeGenError(
                  s"Missing type specification for bool literal in binding '${bnd.name}'",
                  Some(bnd)
                )
              )
          }
          llvmTypeE.map { llvmType =>
            val staticValue = if lit.value then "true" else "false"
            state.emit(emitGlobalVariable(bnd.name, llvmType, staticValue))
          }

        case lit: LiteralUnit =>
          // Unit literals don't generate globals, they're compile-time only
          Right(state)

        case _ =>
          // Fall back to existing runtime initialization logic for complex expressions
          val origState = state
          compileExpr(bnd.value, state).flatMap { compileRes =>
            // For non-literal expressions, always use runtime initialization
            val initFnName = s"_init_global_${bnd.name}"
            // Get the binding's type specification for proper LLVM type
            val llvmTypeE = bnd.typeSpec match {
              case Some(typeSpec) => getLlvmType(typeSpec, origState)
              case None =>
                Left(
                  CodeGenError(s"Missing type specification for binding '${bnd.name}'", Some(bnd))
                )
            }

            llvmTypeE.flatMap { llvmType =>
              val initValue = "zeroinitializer" // Safe default for all types
              val state2 = origState
                .emit(emitGlobalVariable(bnd.name, llvmType, initValue))
                .emit(s"define internal void @$initFnName() {")
                .emit(s"entry:")
              compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
                compileRes2.state
                  .emit(emitStore(s"%${compileRes2.register}", llvmType, s"@${bnd.name}"))
                  .emit("  ret void")
                  .emit("}")
                  .emit("")
                  .addInitializer(initFnName)
              }
            }
          }
      }

    case _ =>
      // Multiple terms - not a simple literal, use runtime initialization
      val origState = state
      compileExpr(bnd.value, state).flatMap { compileRes =>
        val initFnName = s"_init_global_${bnd.name}"
        val llvmTypeE = bnd.typeSpec match {
          case Some(typeSpec) => getLlvmType(typeSpec, origState)
          case None =>
            Left(CodeGenError(s"Missing type specification for binding '${bnd.name}'", Some(bnd)))
        }

        llvmTypeE.flatMap { llvmType =>
          val initValue = "zeroinitializer"
          val state2 = origState
            .emit(emitGlobalVariable(bnd.name, llvmType, initValue))
            .emit(s"define internal void @$initFnName() {")
            .emit(s"entry:")
          compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
            compileRes2.state
              .emit(emitStore(s"%${compileRes2.register}", llvmType, s"@${bnd.name}"))
              .emit("  ret void")
              .emit("}")
              .emit("")
              .addInitializer(initFnName)
          }
        }
      }
  }
}

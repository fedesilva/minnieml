package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.TargetAbi
import mml.mmlclib.codegen.emitter.abis.lowerNativeParamTypes
import mml.mmlclib.codegen.emitter.alias.AliasScopeEmitter
import mml.mmlclib.codegen.emitter.expression.escapeString
import mml.mmlclib.codegen.emitter.tbaa.TbaaEmitter
import mml.mmlclib.errors.CompilerWarning

/** Main entry point for LLVM IR emission.
  *
  * Provides module-level code emission functionality.
  *
  * @param module
  *   the module to emit
  * @param entryPoint
  *   the mangled name of the entry point function (for binary mode)
  * @param targetTriple
  *   the target triple for code generation
  * @param targetAbi
  *   the ABI strategy for native lowering
  */
/** Result of LLVM IR emission: IR string and any accumulated warnings. */
case class EmitResult(ir: String, warnings: List[CompilerWarning])

def emitModule(
  module:          Module,
  entryPoint:      Option[String],
  targetTriple:    String,
  targetAbi:       TargetAbi,
  targetCpu:       Option[String],
  emitAliasScopes: Boolean
): Either[CodeGenError, EmitResult] = {
  // Setup the initial state with the module name, resolvables and header
  val initialState = CodeGenState(
    moduleName      = module.name,
    targetAbi       = targetAbi,
    resolvables     = module.resolvables,
    emitAliasScopes = emitAliasScopes
  ).withModuleHeader(module.name, targetTriple)

  // Collect all TypeDef members with native type specifications
  // Currently we emit LLVM type definitions for native types and struct declarations
  // Future: Add handlers for enums and other MML type constructs
  val typeDefs = module.members.collect {
    case td: TypeDef if td.typeSpec.exists(_.isInstanceOf[NativeType]) =>
      td
  }

  val typeStructs = module.members.collect { case ts: TypeStruct => ts }

  // Generate LLVM type definitions for all native structs.
  // Primitives and pointers do not require forward declarations.
  // Also register TBAA struct nodes so types like IntArray and StringArray
  // have distinct TBAA identities even though they share the same layout.
  val stateWithTypes = typeDefs.foldLeft(initialState.asRight[CodeGenError]) { (stateE, typeDef) =>
    stateE.flatMap { state =>
      typeDef.typeSpec match {
        // Only emit definitions for native structs
        case Some(nativeStruct: NativeStruct) =>
          nativeTypeToLlvmDef(typeDef.name, nativeStruct, state).flatMap { llvmTypeDef =>
            val stateWithType = state.copy(
              nativeTypes = state.nativeTypes + (s"struct.${typeDef.name}" -> llvmTypeDef)
            )
            // Register TBAA struct node for this type
            TbaaEmitter.ensureTbaaStructForTypeDef(typeDef, stateWithType)
          }
        // Other native types (primitives, pointers) are ignored here
        case _ => state.asRight
      }
    }
  }

  val stateWithStructTypes =
    typeStructs.foldLeft(stateWithTypes) { (stateE, typeStruct) =>
      stateE.flatMap { state =>
        val fieldResults = typeStruct.fields.toList.map { field =>
          getLlvmType(field.typeSpec, state).map((field.name, _))
        }
        val errors = fieldResults.collect { case Left(err) => err }
        if errors.nonEmpty then
          Left(
            CodeGenError(
              s"Failed to resolve struct fields: ${errors.map(_.message).mkString(", ")}"
            )
          )
        else
          val llvmFields  = fieldResults.collect { case Right((_, t)) => t }
          val llvmTypeDef = emitTypeDefinition(s"struct.${typeStruct.name}", llvmFields)
          val stateWithType = state.copy(
            nativeTypes = state.nativeTypes + (s"struct.${typeStruct.name}" -> llvmTypeDef)
          )
          TbaaEmitter.ensureTbaaStructForTypeStruct(typeStruct, stateWithType)
      }
    }

  // Process all members using the state that includes type definitions
  val processedState = stateWithStructTypes.flatMap { stateWithTypeDefs =>
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

  // Add synthesized main if entry point is provided
  val stateWithMain = processedState.map { state =>
    entryPoint match
      case Some(ep) => emitSynthesizedMain(ep, module, state)
      case None => state
  }

  // Construct the final output with all components in the proper order
  stateWithMain.map { finalState =>
    val cpuAttrValue = targetCpu.filter(_.nonEmpty)
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

    // 5. Attributes
    cpuAttrValue.foreach { cpu =>
      output.append(s"""\nattributes #0 = { "target-cpu"="$cpu" }\n""")
    }

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

    // 7. TBAA Metadata
    if finalState.aliasScopeOutput.nonEmpty then
      output.append("\n; Alias Scope Metadata\n")
      output.append(finalState.aliasScopeOutput.reverse.mkString("\n"))
      output.append('\n')

    if finalState.tbaaOutput.nonEmpty then
      output.append("\n; TBAA Metadata\n")
      output.append(finalState.tbaaOutput.reverse.mkString("\n"))
      output.append('\n')

    // Return IR and any accumulated warnings
    EmitResult(output.toString(), finalState.warnings.reverse)
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
      val filteredParamTypes = paramTypes.filter(_ != "void")

      // Check if this is a native function implementation
      lambda.body.terms match {
        case List(NativeImpl(_, _, _, _, _)) =>
          // Native functions: emit as declaration with original name (external symbol)
          // Split struct params for x86_64 ABI compliance
          val abiParamTypes = lowerNativeParamTypes(filteredParamTypes, state)
          Right(state.withFunctionDeclaration(fnName, returnType, abiParamTypes))

        case _ =>
          // User-defined functions: emit with mangled name (modulename_functionname)
          val emittedName = state.mangleName(fnName)
          compileBndLambda(
            bnd,
            lambda,
            state,
            returnType,
            filteredParamTypes,
            emittedName
          )
      }
    }
  }
}

/** Emits a regular value binding.
  */
private def emitValueBinding(bnd: Bnd, state: CodeGenState): Either[CodeGenError, CodeGenState] = {
  val mangledName = state.mangleName(bnd.name)

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
            newState.emit(emitGlobalVariable(mangledName, llvmType, staticValue))
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
            state.emit(emitGlobalVariable(mangledName, llvmType, lit.value.toString))
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
            state.emit(emitGlobalVariable(mangledName, llvmType, staticValue))
          }

        case _: LiteralUnit =>
          // Unit literals don't generate globals, they're compile-time only
          Right(state)

        case _ =>
          // Fall back to existing runtime initialization logic for complex expressions
          val origState  = state
          val initFnName = s"_init_global_$mangledName"
          compileExpr(bnd.value, state).flatMap { _ =>
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
                .emit(emitGlobalVariable(mangledName, llvmType, initValue))
                .emit(s"define internal void @$initFnName() {")
                .emit(s"entry:")
              compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
                val (stateWithAlias, aliasTag, noaliasTag) = bnd.typeSpec match
                  case Some(spec) => AliasScopeEmitter.getAliasScopeTags(spec, compileRes2.state)
                  case None => (compileRes2.state, None, None)
                val storeLine =
                  emitStore(
                    s"%${compileRes2.register}",
                    llvmType,
                    s"@$mangledName",
                    aliasScope = aliasTag,
                    noalias    = noaliasTag
                  )
                stateWithAlias
                  .emit(storeLine)
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
      val origState  = state
      val initFnName = s"_init_global_$mangledName"
      compileExpr(bnd.value, state).flatMap { _ =>
        val llvmTypeE = bnd.typeSpec match {
          case Some(typeSpec) => getLlvmType(typeSpec, origState)
          case None =>
            Left(CodeGenError(s"Missing type specification for binding '${bnd.name}'", Some(bnd)))
        }

        llvmTypeE.flatMap { llvmType =>
          val initValue = "zeroinitializer"
          val state2 = origState
            .emit(emitGlobalVariable(mangledName, llvmType, initValue))
            .emit(s"define internal void @$initFnName() {")
            .emit(s"entry:")
          compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
            val (stateWithAlias, aliasTag, noaliasTag) = bnd.typeSpec match
              case Some(spec) => AliasScopeEmitter.getAliasScopeTags(spec, compileRes2.state)
              case None => (compileRes2.state, None, None)
            val storeLine =
              emitStore(
                s"%${compileRes2.register}",
                llvmType,
                s"@$mangledName",
                aliasScope = aliasTag,
                noalias    = noaliasTag
              )
            stateWithAlias
              .emit(storeLine)
              .emit("  ret void")
              .emit("}")
              .emit("")
              .addInitializer(initFnName)
          }
        }
      }
  }
}

/** Emits a synthesized C-style main function that wraps the user's entry point.
  *
  * The synthesized main:
  *   1. Calls the user's entry point
  *   2. Calls mml_sys_flush() to flush println buffers
  *   3. Returns 0 for Unit-returning main, or propagates the return value for Int-returning main
  */
private def emitSynthesizedMain(
  entryPoint: String,
  module:     Module,
  state:      CodeGenState
): CodeGenState =
  val returnsInt = mainReturnsInt(module, state)

  // Ensure mml_sys_flush is declared
  val stateWithFlush = state.withFunctionDeclaration("mml_sys_flush", "void", List.empty)

  if returnsInt then
    // Int-returning main: capture return value, flush, truncate i64 -> i32, return
    stateWithFlush
      .emit("define i32 @main(i32 %0, ptr %1) #0 {")
      .emit("entry:")
      .emit(s"  %ret = call i64 @$entryPoint()")
      .emit("  call void @mml_sys_flush()")
      .emit("  %exitcode = trunc i64 %ret to i32")
      .emit("  ret i32 %exitcode")
      .emit("}")
      .emit("")
  else
    // Unit-returning main: call entry point, flush, return 0
    stateWithFlush
      .emit("define i32 @main(i32 %0, ptr %1) #0 {")
      .emit("entry:")
      .emit(s"  call void @$entryPoint()")
      .emit("  call void @mml_sys_flush()")
      .emit("  ret i32 0")
      .emit("}")
      .emit("")

/** Determines if the main function returns Int (true) or Unit (false). */
private def mainReturnsInt(module: Module, state: CodeGenState): Boolean =
  findMainFn(module) match
    case Some((bnd, _)) =>
      bnd.typeSpec match
        case Some(fnType: TypeFn) =>
          getLlvmType(fnType.returnType, state) match
            case Right("i64") => true
            case _ => false
        case Some(TypeScheme(_, _, bodyType: TypeFn)) =>
          getLlvmType(bodyType.returnType, state) match
            case Right("i64") => true
            case _ => false
        case _ => false
    case None => false

/** Finds the main function binding and its lambda in the module. */
private def findMainFn(module: Module): Option[(Bnd, Lambda)] =
  module.members.collectFirst {
    case bnd: Bnd
        if bnd.meta.exists(m => m.origin == BindingOrigin.Function && m.originalName == "main") =>
      bnd.value.terms.headOption.collect { case lambda: Lambda => (bnd, lambda) }
  }.flatten

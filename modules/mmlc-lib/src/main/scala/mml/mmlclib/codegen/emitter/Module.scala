package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.abis.NativeAbiPlan
import mml.mmlclib.codegen.emitter.alias.AliasScopeEmitter
import mml.mmlclib.codegen.emitter.expression.escapeString
import mml.mmlclib.codegen.emitter.tbaa.TbaaEmitter
import mml.mmlclib.codegen.{TargetAbi, TargetAttributes}
import mml.mmlclib.errors.CompilerWarning

/** Holds the emitted module text and warnings accumulated while emitting its members. */
case class EmitResult(ir: String, warnings: List[CompilerWarning])

/** Emits the final typed module after semantic transformations have resolved bindings and captures.
  * Local callable analysis runs before member emission because later uses determine whether an
  * earlier binding gets a plain entry. targetAbi controls native calls; targetAttributes supplies
  * the data layout and CPU attributes for the emitted module.
  *
  * @param entryPoint
  *   the mangled entry function name when producing a binary
  */
def emitModule(
  module:           Module,
  entryPoint:       Option[String],
  targetTriple:     String,
  targetAbi:        TargetAbi,
  targetAttributes: TargetAttributes,
  emitAliasScopes:  Boolean
): Either[CodeGenError, EmitResult] = {
  if targetAbi == TargetAbi.Default && targetTriple.nonEmpty then
    return CodeGenError(s"Unsupported target C ABI: $targetTriple").asLeft

  val targets = CallableTargetAnalysis.analyze(module)
  val locals  = LocalCallableAnalysis.analyze(module, targets)
  ClosureEnvironmentLayout.analyze(module, targets).flatMap { environments =>
    emitPlannedModule(
      module,
      entryPoint,
      targetTriple,
      targetAbi,
      targetAttributes,
      emitAliasScopes,
      targets,
      locals,
      environments
    )
  }
}

private def emitPlannedModule(
  module:           Module,
  entryPoint:       Option[String],
  targetTriple:     String,
  targetAbi:        TargetAbi,
  targetAttributes: TargetAttributes,
  emitAliasScopes:  Boolean,
  targets:          CallableTargetAnalysis,
  locals:           LocalCallableAnalysis,
  environments:     Map[String, ClosureEnvironmentLayout]
): Either[CodeGenError, EmitResult] = {
  // Setup the initial state with the module name, resolvables and header
  val plannedState = CodeGenState(
    callableTargetAnalysis = targets,
    closureEnvironments    = environments,
    nextAnonFnId           = targets.definitions.size,
    localCallableAnalysis  = locals,
    moduleName             = module.name,
    targetAbi              = targetAbi,
    layout                 = TargetLayout(targetAttributes.dataLayout),
    resolvables            = module.resolvables.updatedAllTypes(environments.values.map(_.layout)),
    emitAliasScopes        = emitAliasScopes
  ).withModuleHeader(module.name, targetTriple)

  val initialState =
    plannedState.copy(knownCallableEntries = KnownCallableEntry.allocate(plannedState))

  // Collect all TypeDef members with native type specifications
  // Currently we emit LLVM type definitions for native types and struct declarations
  // Future: Add handlers for enums and other MML type constructs
  val typeDefs = module.members.collect {
    case td: TypeDef if td.typeSpec.exists(_.isInstanceOf[NativeType]) =>
      td
  }

  val typeStructs = module.members.collect { case ts: TypeStruct =>
    ts.id.flatMap(environments.get).fold(ts)(_.layout)
  }

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
  val stateWithMain = processedState.flatMap { state =>
    entryPoint match
      case Some(ep) => emitSynthesizedMain(ep, module, state)
      case None => state.asRight
  }

  // Construct the final output with all components in the proper order
  stateWithMain.map { finalState =>
    val attributes = targetAttributes.llvm
    // Assemble the full output in the correct order
    val output = new StringBuilder()

    // 1. Module header
    finalState.moduleHeader.foreach(output.append)
    output.append(s"target datalayout = \"${targetAttributes.dataLayout}\"\n\n")

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

    // 5b. Deferred definitions (expression-position lambdas)
    if finalState.deferredDefinitions.nonEmpty then
      output.append("\n")
      finalState.deferredDefinitions.reverse.foreach(d => output.append(d).append("\n"))

    // 6. Attributes
    if attributes.nonEmpty then output.append(s"\nattributes #0 = { $attributes }")
    output.append(s"\nattributes #1 = { inlinehint $attributes }\n")

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
        case List(NativeImpl(_, _, _, _, memEffect, nativeSymbol)) =>
          NativeAbiPlan.classify(returnType, filteredParamTypes, state).map { plan =>
            val abiReturnType   = plan.returnType
            val finalParamTypes = plan.parameterTypes

            // Add noalias for functions that allocate and return a pointer type
            // Check both the NativeImpl memEffect (stdlib) and the return type's own
            // memEffect (user-defined types like @native[t=*i8, mem=heap])
            val returnNativeType = TypeUtils.resolveNativeType(fnType.returnType, state.resolvables)

            val isAllocatingPointerReturn = returnNativeType.exists { nativeType =>
              TypeUtils.isPointerNativeType(nativeType) &&
              (memEffect
                .contains(MemEffect.Alloc) || nativeType.memEffect.contains(MemEffect.Alloc))
            }

            val finalReturnType =
              if isAllocatingPointerReturn then s"noalias $abiReturnType"
              else abiReturnType

            val declaredName = nativeSymbol.getOrElse(fnName)
            state.withFunctionDeclaration(declaredName, finalReturnType, finalParamTypes)
          }

        case _ =>
          // User-defined functions: emit with mangled name (modulename_functionname)
          val emittedName = state.mangleName(fnName)
          val linkage     = if bnd.visibility == Visibility.Public then "" else "internal "
          compileBndLambda(
            bnd,
            lambda,
            state,
            returnType,
            paramTypes.toList,
            emittedName,
            linkage
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
          // Generate static String global: @a = global %String { i32 4, ptr @str.0 }
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
            val staticValue = s"{ i32 ${lit.value.length}, ptr @$constName }"
            newState.emit(emitGlobalVariable(mangledName, llvmType, staticValue))
          }

        case lit: LiteralInt =>
          // Generate static int global: @a = global i32 42
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
                .emit(s"define internal void @$initFnName() #0 {")
                .emit("entry:")
              compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
                val (stateWithAlias, aliasTag, noaliasTag) = bnd.typeSpec match
                  case Some(spec) => AliasScopeEmitter.getAliasScopeTags(spec, compileRes2.state)
                  case None => (compileRes2.state, None, None)
                val storeLine =
                  emitStore(
                    compileRes2.operandStr,
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
            .emit(s"define internal void @$initFnName() #0 {")
            .emit("entry:")
          compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
            val (stateWithAlias, aliasTag, noaliasTag) = bnd.typeSpec match
              case Some(spec) => AliasScopeEmitter.getAliasScopeTags(spec, compileRes2.state)
              case None => (compileRes2.state, None, None)
            val storeLine =
              emitStore(
                compileRes2.operandStr,
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
): Either[CodeGenError, CodeGenState] =
  val returnType     = mainReturnType(module, state)
  val returnsInt     = returnType != "void"
  val takesArgsArray = mainTakesStringArrayArg(module)
  for
    argsPlan <- NativeAbiPlan.classify("%struct.StringArray", List("i32", "ptr"), state)
    freePlan <- NativeAbiPlan.classify("void", List("%struct.StringArray"), state)
    flushPlan <- NativeAbiPlan.classify("void", Nil, state)
  yield
    val entry = flushPlan
      .declare("mml_sys_flush", state)
      .withRegister(2)
      .emit("define i32 @main(i32 %0, ptr %1) #0 {")
      .emit("entry:")
    val (userArgs, beforeCall) =
      if takesArgsArray then
        val (reg, emitted) = argsPlan.emitCall(
          "mml_args_to_array",
          List("%0", "%1"),
          argsPlan.declare("mml_args_to_array", entry)
        )
        (List(("%struct.StringArray", s"%$reg")), emitted)
      else (Nil, entry)
    val resultReg = beforeCall.nextRegister
    val called =
      if returnsInt then
        beforeCall
          .withRegister(resultReg + 1)
          .emit(emitCall(resultReg.some, returnType.some, entryPoint, userArgs))
      else beforeCall.emit(emitCall(none, none, entryPoint, userArgs))
    val flushed = flushPlan.emitCall("mml_sys_flush", Nil, called)._2
    val cleaned =
      if takesArgsArray then
        freePlan
          .emitCall(
            "__free_StringArray",
            userArgs.map(_._2),
            freePlan.declare("__free_StringArray", flushed)
          )
          ._2
      else flushed
    val exited =
      if returnType == "i64" then
        val exitReg = cleaned.nextRegister
        cleaned
          .withRegister(exitReg + 1)
          .emit(s"  %$exitReg = trunc i64 %$resultReg to i32")
          .emit(s"  ret i32 %$exitReg")
      else if returnsInt then cleaned.emit(s"  ret i32 %$resultReg")
      else cleaned.emit("  ret i32 0")
    exited.emit("}").emit("")

/** Returns the validated LLVM result type of the entry point. */
private def mainReturnType(module: Module, state: CodeGenState): String =
  findMainFn(module)
    .flatMap { (bnd, _) =>
      bnd.typeSpec.flatMap {
        case fnType: TypeFn => getLlvmType(fnType.returnType, state).toOption
        case TypeScheme(_, _, bodyType: TypeFn) =>
          getLlvmType(bodyType.returnType, state).toOption
        case _ => none
      }
    }
    .getOrElse("void")

private def mainTakesStringArrayArg(module: Module): Boolean =
  findMainFn(module) match
    case Some((_, lambda)) =>
      lambda.params match
        case param :: Nil =>
          !param.consuming && paramTypeIsStringArray(param)
        case _ => false
    case None => false

private def paramTypeIsStringArray(param: FnParam): Boolean =
  param.typeSpec.orElse(param.typeAsc) match
    case Some(TypeRef(_, name, _, _)) => name == "StringArray"
    case _ => false

/** Finds the main function binding and its lambda in the module. */
private def findMainFn(module: Module): Option[(Bnd, Lambda)] =
  module.members.collectFirst {
    case bnd: Bnd
        if bnd.meta.exists(m => m.origin == BindingOrigin.Function && m.originalName == "main") =>
      bnd.value.terms.headOption.collect { case lambda: Lambda => (bnd, lambda) }
  }.flatten

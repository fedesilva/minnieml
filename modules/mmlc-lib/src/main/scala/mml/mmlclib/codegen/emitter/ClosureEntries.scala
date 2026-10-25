package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.{CompiledArg, compileCallableCall}

/** Materializes an emitted callable as a resource-free closure value. */
private[emitter] def compileCallableValue(
  ref:   Ref,
  state: CodeGenState
): Either[CodeGenError, CompileResult] =
  val binding = ref.resolvedId.flatMap(state.resolvables.lookup).collect { case bnd: Bnd => bnd }

  for
    bnd <- binding.toRight(CodeGenError("Missing callable binding", ref.some))
    id <- bnd.id.toRight(CodeGenError("Missing callable binding identity", ref.some))
    entry <- state.callableEntries.get(id) match
      case Some(name) => (state, name).asRight[CodeGenError]
      case None =>
        bnd.value.terms match
          case List(lambda: Lambda) =>
            for
              signature <- bnd.typeSpec
                .flatMap(resolveToTypeFn(_, state.resolvables))
                .toRight(CodeGenError("Missing callable signature", ref.some))
              returnType <- getLlvmType(signature.returnType, state)
              paramTypes <- signature.paramTypes.traverse(getLlvmType(_, state))
              (allocated, name) = state.allocAnonFnName
              registered = allocated.copy(callableEntries =
                allocated.callableEntries + (id -> name)
              )
              emitted <- compileClosureEntry(
                lambda,
                registered,
                name,
                returnType,
                paramTypes.toList
              ) { (bodyState, args) =>
                compileCallableCall(ref, args, signature.returnType.some, bodyState)
              }
            yield (emitted, name)
          case _ => CodeGenError("Expected an emitted callable", ref.some).asLeft
    (nextState, name) = entry
  yield CompileResult(
    0,
    nextState,
    true,
    "Function",
    literalValue = s"{ ptr @$name, ptr null }".some
  )

/** Emits a closure entry that forwards its user operands while ignoring its environment. */
private[emitter] def compileClosureEntry(
  lambda:     Lambda,
  state:      CodeGenState,
  name:       String,
  returnType: String,
  paramTypes: List[String]
)(
  body: (CodeGenState, List[CompiledArg]) => Either[CodeGenError, CompileResult]
): Either[CodeGenError, CodeGenState] =
  ParameterLowering.from(lambda, paramTypes).flatMap { lowering =>
    val userParams = lowering.declarations(state.resolvables)
    val envIndex   = lowering.runtimeCount
    val declarations =
      if userParams.isEmpty then s"ptr %$envIndex"
      else s"$userParams, ptr %$envIndex"
    val bodyState = state.copy(
      output                  = Nil,
      entryPrologueOutput     = Nil,
      nextRegister            = envIndex + 1,
      insideLoopifiedFunction = false
    )

    body(bodyState, lowering.arguments).map { result =>
      val ret =
        if returnType == "void" then "  ret void"
        else s"  ret $returnType ${result.operandStr}"
      val completed  = result.state.emit(ret).emit("}")
      val header     = s"define internal $returnType @$name($declarations) #0 {"
      val definition = renderFunctionLines(header, completed).mkString("\n")
      mergeFunctionBodyState(state, completed).addDeferredDefinition(definition)
    }
  }

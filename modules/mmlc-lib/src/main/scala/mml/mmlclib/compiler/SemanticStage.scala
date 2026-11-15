package mml.mmlclib.compiler

import mml.mmlclib.ast.queries.DeclarationQueries
import mml.mmlclib.semantic.*
import mml.mmlclib.util.pipe.*

object SemanticStage:

  /** Publish phases that do not rebuild their own indexes. Allocating phases seed their supply. */
  private def indexed(phase: CompilerState => CompilerState)(state: CompilerState): CompilerState =
    ResolvablesIndexer.rewriteModule(phase(state))

  def rewrite(state: CompilerState): CompilerState =
    rewriteExecutable(state)

  private def rewriteExecutable(state: CompilerState): CompilerState =

    val withStdlib = CompilerState.timePhase("semantic", "inject-stdlib") { current =>
      val moduleWithTypes = injectBasicTypes(current.module)
      val moduleWithOps   = injectStandardOperators(moduleWithTypes)
      val moduleWithFns   = injectCommonFunctions(moduleWithOps)
      current.withModule(moduleWithFns)
    }(state)

    withStdlib
      |> CompilerState.timePhase("semantic", "duplicate-names")(DuplicateNameChecker.rewriteModule)
      |> CompilerState.timePhase("semantic", "id-assigner")(IdAssigner.rewriteModule)
      |> CompilerState.timePhase("semantic", "type-resolver")(indexed(TypeResolver.rewriteModule))
      |> CompilerState.timePhase("semantic", "ctor-gen")(
        ConstructorGenerator.rewriteModule
      )
      |> CompilerState.timePhase("semantic", "mem-fn-gen")(
        MemoryFunctionGenerator.rewriteModule
      )
      |> CompilerState.timePhase("semantic", "clone-instances")(StructCloneGenerator.register)
      |> CompilerState.timePhase("semantic", "protocol-checker")(ProtocolChecker.rewriteModule)
      |> CompilerState.timePhase("semantic", "ref-resolver")(indexed(RefResolver.rewriteModule))
      |> CompilerState.timePhase("semantic", "clone-bodies")(StructCloneGenerator.generateBodies)
      |> CompilerState.timePhase("semantic", "op-coherence")(OperatorCoherence.checkModule)
      |> CompilerState
        .timePhase("semantic", "expression-rewriter")(indexed(ExpressionRewriter.rewriteModule))
      |> CompilerState.timePhase("semantic", "simplifier")(indexed(Simplifier.rewriteModule))
      |> CompilerState.timePhase("semantic", "capture-analyzer")(
        indexed(CaptureAnalyzer.rewriteModule)
      )
      |> CompilerState.timePhase("semantic", "type-checker")(indexed(TypeChecker.rewriteModule))
      |> instantiateAndLower

  private def instantiateAndLower(state: CompilerState): CompilerState =
    val concrete = CompilerState.timePhase("semantic", "fn-monomorphizer")(
      FnMonomorphizer.rewriteModule
    )(state)
    if concrete.hasErrors && GenericSyntax.exists(concrete.module) then
      concrete.withCanEmitCode(false)
    else lowerConcrete(concrete)

  /** Lower executable members while retaining checked templates in the same module and index. */
  private def lowerConcrete(state: CompilerState): CompilerState =
    val (templates, executable) =
      state.module.members.partition(DeclarationQueries.isGenericFunction)
    val lowered = lowerMembers(state.withModule(state.module.copy(members = executable)))
    ResolvablesIndexer.rewriteModule(
      lowered.withModule(lowered.module.copy(members = templates ++ lowered.module.members))
    )

  /** Lowers concrete callables and validates their ownership and destruction. */
  private def lowerMembers(state: CompilerState): CompilerState =
    state
      |> indexed(CaptureAnalyzer.rewriteModule)
      |> CompilerState.timePhase("semantic", "pap-elaborator")(
        PartialApplicationElaborator.rewriteModule
      )
      |> CompilerState.timePhase("semantic", "closure-mem-gen")(
        ClosureMemoryFnGenerator.rewriteModule
      )
      |> CompilerState.timePhase("semantic", "struct-destructor-bodies")(
        indexed(StructDestructorBodyGenerator.rewriteModule)
      )
      |> CompilerState.timePhase("semantic", "ownership-analyzer")(
        indexed(OwnershipAnalyzer.rewriteModule)
      )
      |> CompilerState.timePhase("semantic", "closure-destructor-bodies")(
        indexed(ClosureDestructorBodyGenerator.rewriteModule)
      )
      |> CompilerState.timePhase("semantic", "tailrec-detector")(
        indexed(TailRecursionDetector.rewriteModule)
      )
      |> CompilerState.timePhase("semantic", "destruction-validation")(
        DestructionValidator.rewriteModule
      )

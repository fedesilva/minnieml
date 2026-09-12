package mml.mmlclib.compiler

import mml.mmlclib.semantic.*
import mml.mmlclib.util.pipe.*

object SemanticStage:

  /** Publish phases that do not rebuild their own indexes. Allocating phases seed their supply. */
  private def indexed(phase: CompilerState => CompilerState)(state: CompilerState): CompilerState =
    ResolvablesIndexer.rewriteModule(phase(state))

  def rewrite(state: CompilerState): CompilerState =

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
      |> CompilerState.timePhase("semantic", "ref-resolver")(indexed(RefResolver.rewriteModule))
      |> CompilerState
        .timePhase("semantic", "expression-rewriter")(indexed(ExpressionRewriter.rewriteModule))
      |> CompilerState.timePhase("semantic", "simplifier")(indexed(Simplifier.rewriteModule))
      |> CompilerState.timePhase("semantic", "capture-analyzer")(
        indexed(CaptureAnalyzer.rewriteModule)
      )
      |> CompilerState.timePhase("semantic", "type-checker")(indexed(TypeChecker.rewriteModule))
      |> CompilerState.timePhase("semantic", "partial-application-elaborator")(
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

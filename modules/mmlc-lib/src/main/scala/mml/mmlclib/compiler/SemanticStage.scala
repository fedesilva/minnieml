package mml.mmlclib.compiler

import mml.mmlclib.semantic.*
import mml.mmlclib.util.pipe.*

object SemanticStage:

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
      |> CompilerState.timePhase("semantic", "type-resolver")(TypeResolver.rewriteModule)
      |> CompilerState.timePhase("semantic", "ref-resolver")(RefResolver.rewriteModule)
      |> CompilerState
        .timePhase("semantic", "expression-rewriter")(ExpressionRewriter.rewriteModule)
      |> CompilerState.timePhase("semantic", "simplifier")(Simplifier.rewriteModule)
      |> CompilerState.timePhase("semantic", "type-checker")(TypeChecker.rewriteModule)
      |> CompilerState.timePhase("semantic", "resolvables-indexer")(
        ResolvablesIndexer.rewriteModule
      )
      |> CompilerState.timePhase("semantic", "tailrec-labeller")(
        TailRecursionDetector.rewriteModule
      )

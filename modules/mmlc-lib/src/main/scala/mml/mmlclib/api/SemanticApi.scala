package mml.mmlclib.api
import cats.data.EitherT
import cats.effect.IO
import mml.mmlclib.api.CompilerEffect
import mml.mmlclib.ast.Module
import mml.mmlclib.semantic.*
import mml.mmlclib.util.pipe.*

object SemanticApi:
  /** Rewrite a Module with semantic transforms
    *
    * This function applies several semantic transformations to a module:
    *   - Injects standard operators
    *   - Checks for duplicate names
    *   - Resolves references
    *   - Rewrites expressions with function applications and operators
    *   - Simplifies the AST
    *
    * @param module
    *   the module to rewrite
    * @return
    *   a CompilerEffect that yields the final SemanticPhaseState (module + accumulated errors)
    */
  def rewriteModule(module: Module): CompilerEffect[SemanticPhaseState] =
    EitherT.liftF(
      IO.delay {
        // Create initial state with injected basic types, standard operators, and common functions
        // This is a workaround for the current lack of a real module system, with imports, etc.
        val moduleWithTypes = injectBasicTypes(module)
        val moduleWithOps   = injectStandardOperators(moduleWithTypes)
        val moduleWithFns   = injectCommonFunctions(moduleWithOps)
        val initialState    = SemanticPhaseState(moduleWithFns, Vector.empty)

        // Thread state through all phases
        initialState
          |> ParsingErrorChecker.checkModule
          |> DuplicateNameChecker.rewriteModule
          |> TypeResolver.rewriteModule
          |> RefResolver.rewriteModule
          |> ExpressionRewriter.rewriteModule
          |> Simplifier.rewriteModule
          |> TypeChecker.rewriteModule
      }
    )

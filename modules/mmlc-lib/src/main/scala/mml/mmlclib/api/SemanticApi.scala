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
    *   a CompilerEffect that, when run, yields either a CompilerError or a rewritten Module
    */
  def rewriteModule(module: Module): CompilerEffect[Module] =
    EitherT
      .liftF(IO.delay {
        // Create initial state with injected basic types and standard operators
        val moduleWithTypes = injectBasicTypes(module)
        val moduleWithOps   = injectStandardOperators(moduleWithTypes)
        val initialState    = SemanticPhaseState(moduleWithOps, Vector.empty)

        // Thread state through all phases
        val finalState =
          initialState
            |> ParsingErrorChecker.checkModule
            |> DuplicateNameChecker.rewriteModule
            |> TypeResolver.rewriteModule
            |> RefResolver.rewriteModule
            |> ExpressionRewriter.rewriteModule
            |> Simplifier.rewriteModule
            |> TypeChecker.rewriteModule

        // Convert back to Either for compatibility
        if finalState.errors.isEmpty then Right(finalState.module)
        else Left(finalState.errors.toList)
      })
      .flatMap {
        case Right(res) => EitherT.rightT[IO, CompilerError](res)
        case Left(errors) => EitherT.leftT[IO, Module](CompilerError.SemanticErrors(errors))
      }

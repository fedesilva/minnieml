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
        // Create initial state with injected standard operators
        val moduleWithOps = injectStandardOperators(module)
        val initialState  = SemanticPhaseState(moduleWithOps, Vector.empty)

        // Thread state through all phases
        val finalState =
          initialState
            |> DuplicateNameChecker.rewriteModule
            |> RefResolver.rewriteModule
            |> TypeResolver.rewriteModule
            |> ExpressionRewriter.rewriteModule
            |> MemberErrorChecker.checkModule
            |> Simplifier.rewriteModule

        // Convert back to Either for compatibility
        if finalState.errors.isEmpty then Right(finalState.module)
        else Left(finalState.errors.toList)
      })
      .flatMap {
        case Right(res) => EitherT.rightT[IO, CompilerError](res)
        case Left(errors) => EitherT.leftT[IO, Module](CompilerError.SemanticErrors(errors))
      }

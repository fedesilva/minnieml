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
        val result = module
        // Inject standard operators into the module
        // This is a temporary solution until we have a proper module system
          |> injectStandardOperators
          |> DuplicateNameChecker.checkModule
          |> RefResolver.rewriteModule
          // Handle expression rewriting (function applications and operator precedence)
          |> ExpressionRewriter.rewriteModule
          |> Simplifier.rewriteModule

        result
      })
      .flatMap {
        case Right(mod) => EitherT.rightT[IO, CompilerError](mod)
        case Left(errors) => EitherT.leftT[IO, Module](CompilerError.SemanticErrors(errors))
      }

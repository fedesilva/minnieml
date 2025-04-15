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
        module
        // Inject standard operators into the module
        // This is a temporary solution until we have a proper module system
          |> injectStandardOperators
          // Now check for any MemberError instances
          |> DuplicateNameChecker.checkModule
          |> RefResolver.rewriteModule
          |> ExpressionRewriter.rewriteModule
          |> MemberErrorChecker.checkModule
          |> Simplifier.rewriteModule
      })
      .flatMap {
        case Right(res) => EitherT.rightT[IO, CompilerError](res)
        case Left(errors) => EitherT.leftT[IO, Module](CompilerError.SemanticErrors(errors))
      }

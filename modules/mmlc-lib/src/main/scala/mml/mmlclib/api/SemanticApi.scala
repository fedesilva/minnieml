package mml.mmlclib.api

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.*

object SemanticApi:

  def rewriteModule(module: Module): IO[Either[CompilerError, Module]] =
    IO.delay {
      // Inject standard operators into the module
      // TODO: This should be done as a separate step.
      //       So when we have a proper prelude, we can remove that step
      //       and also so that we can have tests that don't rely on the prelude,
      //       but we define the operators in the test itself.
      val moduleWithOps = injectStandardOperators(module)
      // Resolve references, apply precedence rewriting and
      // simplify the module since the precedence climbing algorithm
      // may leave some unnecessary exprs in the tree.
      val result = for {
        resolvedModule <- RefResolver.rewriteModule(moduleWithOps)
        bloomedModule <- PrecedenceClimbing.rewriteModule(resolvedModule)
        finalModule <- Simplifier.rewriteModule(bloomedModule)
      } yield finalModule
      result match
        case Right(mod) => Right(mod)
        case Left(errors) =>
          CompilerError.SemanticErrors(errors).asLeft
    }

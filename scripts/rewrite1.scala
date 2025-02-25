import mml.mmlclib.api.*
import mml.mmlclib.util.yolo.*
import mml.mmlclib.util.*
import cats.syntax.all.*
import mml.mmlclib.semantic.*

val src =
  """
    let a = 2 * 2 + 1;
  """

def rewrite(src: String): Unit =
  parseModule(src) match
    case Some(module) =>
      println(s"Original module: \n${prettyPrintAst(module)} ")

      // Inject standard operators first
      val moduleWithOps = injectStandardOperators(module)

      // Resolve references
      // Apply precedence rewriting
      // Simplify the module, since precedence rewriting
      // may have introduced redundant Expr nodes
      val result = for
        resolvedModule <- RefResolver.rewriteModule(moduleWithOps)
        _ = println(s"\n \n resolvedModule \n ${prettyPrintAst(resolvedModule)}")
        bloomedModule <- PrecedenceClimbing.rewriteModule(resolvedModule)
        _ = println(s"\n \n Precedence Climbed \n ${prettyPrintAst(bloomedModule)}")
        simplifiedModule <- Simplifier.rewriteModule(bloomedModule)
      yield simplifiedModule

      result match
        case Right(mod) =>
          println(s"Rewritten module: \n${prettyPrintAst(mod)} ")
          println(s"Original source: \n$src")
        case Left(errors) => println(s"Errors: $errors")

    case None =>
      println("Failed to parse module")

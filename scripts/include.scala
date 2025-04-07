import mml.mmlclib.api.*
import mml.mmlclib.util.yolo.*
import mml.mmlclib.util.*
import mml.mmlclib.util.prettyprint.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import cats.syntax.all.*
import mml.mmlclib.semantic.*

def rewrite(src: String): Unit =
  parseModule(src) match
    case Some(module) =>
      println(s"Original module: \n${prettyPrintAst(module)} ")

      // Inject standard operators first
      val moduleWithOps = injectStandardOperators(module)

      // Resolve references
      // Apply unified expression rewriting (replaces AppRewriter and PrecedenceClimber)
      // Simplify the module, since rewriting may have introduced redundant Expr nodes
      val result = for
        dedupedModule <- DuplicateNameChecker.checkModule(moduleWithOps)
        _ = println(s"\n \n dedupedModule \n ${prettyPrintAst(dedupedModule)}")
        resolvedModule <- RefResolver.rewriteModule(dedupedModule)
        _ = println(s"\n \n resolvedModule \n ${prettyPrintAst(resolvedModule)}")

        // Apply the new unified expression rewriting
        // New unified phase
        unifiedModule <- ExpressionRewriter.rewriteModule(resolvedModule)
        _ = println(s"\n \n Unified Rewriting \n ${prettyPrintAst(unifiedModule)}")

        simplifiedModule <- Simplifier.rewriteModule(unifiedModule)
      yield simplifiedModule

      result match
        case Right(mod) =>
          println(s"Rewritten module: \n${prettyPrintAst(mod)} ")
          println(s"Original source: \n$src")
        case Left(errors) => println(s"Errors: $errors")

    case None =>
      println("Failed to parse module")

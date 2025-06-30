import mml.mmlclib.api.*
import mml.mmlclib.util.yolo.*
import mml.mmlclib.util.*
import mml.mmlclib.util.prettyprint.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import cats.syntax.all.*
import mml.mmlclib.semantic.*

def rewrite(src: String, showTypes: Boolean = false): Unit =
  parseModule(src) match
    case Some(module) =>
      println(s"Original module: \n${prettyPrintAst(module)} ")

      // Inject standard operators first
      val moduleWithOps = injectStandardOperators(module)

      // Resolve references
      // Apply unified expression rewriting (replaces AppRewriter and PrecedenceClimber)
      // Simplify the module, since rewriting may have introduced redundant Expr nodes
      val result = for
        // Check for MemberError instances
        dedupedModule <- DuplicateNameChecker.checkModule(moduleWithOps)
        resolvedModule <- RefResolver.rewriteModule(dedupedModule)
        _ = println(s"\n \n resolvedModule \n ${prettyPrintAst(resolvedModule)}")
        typesResolvedModule <- TypeResolver.rewriteModule(resolvedModule)
        _ = println(s"\n \n typesResolvedModule \n ${prettyPrintAst(typesResolvedModule)}")
        // Apply the new unified expression rewriting
        // New unified phase
        unifiedModule <- ExpressionRewriter.rewriteModule(typesResolvedModule)
        _ = println(s"\n \n Unified Rewriting \n ${prettyPrintAst(unifiedModule)}")
        checkedModule <- MemberErrorChecker.checkModule(unifiedModule)
        simplifiedModule <- Simplifier.rewriteModule(checkedModule)
      yield simplifiedModule

      result match
        case Right(mod) =>
          println(s"Simplified module: \n${prettyPrintAst(mod, showTypes = showTypes)} ")
          println(s"Original source: \n$src")
        case Left(errors) => println(s"Errors: $errors")

    case None =>
      println("Failed to parse module")

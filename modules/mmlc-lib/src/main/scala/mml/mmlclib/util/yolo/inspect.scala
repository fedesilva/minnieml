package mml.mmlclib.util.yolo

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.*
import mml.mmlclib.semantic.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

def printModuleAst(source: String, name: Option[String] = "Anon".some): Unit =
  ParserApi
    .parseModuleString(source, name)
    .value
    .map {
      case Right(ast) => println(s"Parsed AST:\n  ${prettyPrintAst(ast)}")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()

def printModuleAstSimple(source: String, name: Option[String] = "Anon".some): Unit =
  ParserApi
    .parseModuleString(source, name)
    .value
    .map {
      case Right(ast) => println(s"Parsed AST:\n  $ast")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()

def parseModule(source: String, name: Option[String] = "Anon".some): Option[Module] =
  ParserApi
    .parseModuleString(source, name)
    .value
    .map {
      case Right(ast) => ast.some
      case Left(errors) =>
        println(s"Parse error:\n  $errors")
        none
    }
    .unsafeRunSync()

def rewrite(src: String, showTypes: Boolean = false, dumpRawState: Boolean = false): Unit =
  parseModule(src) match
    case Some(module) =>
      println("-" * 80)
      println(s"As parsed module: \n${prettyPrintAst(module)} ")

      // Inject basic types and standard operators first
      val moduleWithTypes = injectBasicTypes(module)
      val moduleWithOps   = injectStandardOperators(moduleWithTypes)

      // Create initial state
      val initialState = SemanticPhaseState(moduleWithOps, Vector.empty)
      println("-" * 80)
      println(s"\n \n Synthetic members injected: \n ${prettyPrintAst(initialState.module)}")

      // Thread state through all phases with debug output
      val state1 = DuplicateNameChecker.rewriteModule(initialState)

      val state2 = TypeResolver.rewriteModule(state1)
      println("-" * 80)
      println(s"\n \n Type Resolver phase:  \n ${prettyPrintAst(state2.module)}")

      val state3 = RefResolver.rewriteModule(state2)
      println("-" * 80)
      println(s"\n \n Reference Resolver phase: \n ${prettyPrintAst(state3.module)}")

      val state4 = ExpressionRewriter.rewriteModule(state3)
      println("-" * 80)
      println(s"\n \n Expression Rewriting phase: \n ${prettyPrintAst(state4.module)}")

      val state5 = ParsingErrorChecker.checkModule(state4)

      val state6 = Simplifier.rewriteModule(state5)
      println("-" * 80)
      println(s"\n \n Simplifier phase: \n ${prettyPrintAst(state6.module)}")

      val finalState = TypeChecker.rewriteModule(state6)

      // Always print the final module
      println("-" * 80)
      println(
        s"Type Checker phase \n${prettyPrintAst(finalState.module, showTypes = true)}"
      )

      println("-" * 80)
      println("Original source")
      println("=" * 80)
      println(s"$src")
      println("=" * 80)

      // Print error status
      if finalState.errors.isEmpty then println("No errors")
      else println(s"Errors: ${finalState.errors.toList}")

      if dumpRawState then
        println("-" * 80)
        println(finalState)
        println("-" * 80)

    case None =>
      println("Failed to parse module")

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

def rewrite(src: String, showTypes: Boolean = false): Unit =
  parseModule(src) match
    case Some(module) =>
      println(s"Original module: \n${prettyPrintAst(module)} ")

      // Inject standard operators first
      val moduleWithOps = injectStandardOperators(module)

      // Create initial state
      val initialState = SemanticPhaseState(moduleWithOps, Vector.empty)

      // Thread state through all phases with debug output
      val state1 = DuplicateNameChecker.rewriteModule(initialState)

      val state2 = RefResolver.rewriteModule(state1)
      println(s"\n \n resolvedModule \n ${prettyPrintAst(state2.module)}")

      val state3 = TypeResolver.rewriteModule(state2)
      println(s"\n \n typesResolvedModule \n ${prettyPrintAst(state3.module)}")

      val state4 = ExpressionRewriter.rewriteModule(state3)
      println(s"\n \n Unified Rewriting \n ${prettyPrintAst(state4.module)}")

      val state5 = MemberErrorChecker.checkModule(state4)

      val finalState = Simplifier.rewriteModule(state5)

      // Always print the final module
      println(
        s"Simplified module: \n${prettyPrintAst(finalState.module, showTypes = showTypes)} "
      )
      println(s"Original source: \n$src")

      // Print error status
      if finalState.errors.isEmpty then println("No errors")
      else println(s"Errors: ${finalState.errors.toList}")

    case None =>
      println("Failed to parse module")

package mml.mmlclib.util.yolo

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig, IngestStage}
import mml.mmlclib.semantic.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

import java.nio.file.{Files, Paths}

def printModuleAst(source: String, name: String = "Anon"): Unit =
  ParserApi
    .parseModuleString(source, name)
    .value
    .map {
      case Right(ast) => println(s"Parsed AST:\n  ${prettyPrintAst(ast)}")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()

def printModuleAstSimple(source: String, name: String = "Anon"): Unit =
  ParserApi
    .parseModuleString(source, name)
    .value
    .map {
      case Right(ast) => println(s"Parsed AST:\n  $ast")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()

def parseModule(source: String, name: String = "Anon"): Option[Module] =
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

def rewritePath(
  path:         String,
  showTypes:    Boolean = false,
  dumpRawState: Boolean = false,
  showIr:       Boolean = false,
  showSpans:    Boolean = false
): Unit =
  import scala.jdk.CollectionConverters.*
  val src = Files.readAllLines(Paths.get(path)).asScala.mkString("\n")
  rewrite(src, showTypes, dumpRawState, showIr, showSpans)

def rewrite(
  src:          String,
  showTypes:    Boolean = false,
  dumpRawState: Boolean = false,
  showIr:       Boolean = false,
  showSpans:    Boolean = false
): Unit =
  val ingestState = IngestStage.fromSource(src, "Anon", config = CompilerConfig.default)

  println("-" * 80)
  println(
    s"As parsed module: \n${prettyPrintAst(ingestState.module, showSourceSpans = showSpans)} "
  )
  println("-" * 80)
  println(
    s"\n \n Parser errors collected: \n ${prettyPrintAst(ingestState.module, showSourceSpans = showSpans)}"
  )

  // Inject basic types and standard operators after parsing errors are collected
  val moduleWithTypes  = injectBasicTypes(ingestState.module)
  val moduleWithOps    = injectStandardOperators(moduleWithTypes)
  val moduleWithCommon = injectCommonFunctions(moduleWithOps)
  val injectedState    = ingestState.withModule(moduleWithCommon)
  println("-" * 80)
  println(
    s"\n \n Synthetic members injected: \n ${prettyPrintAst(injectedState.module, showSourceSpans = showSpans)}"
  )

  // Thread state through all phases with debug output
  val state1 = DuplicateNameChecker.rewriteModule(injectedState)
  println("-" * 80)
  println(
    s"\n \n Duplicate Name phase: \n ${prettyPrintAst(state1.module, showSourceSpans = showSpans)}"
  )

  val state2 = TypeResolver.rewriteModule(state1)
  println("-" * 80)
  println(
    s"\n \n Type Resolver phase:  \n ${prettyPrintAst(state2.module, showSourceSpans = showSpans)}"
  )

  val state3 = RefResolver.rewriteModule(state2)
  println("-" * 80)
  println(
    s"\n \n Reference Resolver phase: \n ${prettyPrintAst(state3.module, showSourceSpans = showSpans)}"
  )

  val state4 = ExpressionRewriter.rewriteModule(state3)
  println("-" * 80)
  println(
    s"\n \n Expression Rewriting phase: \n ${prettyPrintAst(state4.module, showSourceSpans = showSpans)}"
  )

  val state5 = Simplifier.rewriteModule(state4)
  println("-" * 80)
  println(
    s"\n \n Simplifier phase: \n ${prettyPrintAst(state5.module, showSourceSpans = showSpans)}"
  )

  val state6 = TypeChecker.rewriteModule(state5)

  // Always print the final module
  println("-" * 80)
  println(
    s"Type Checker phase \n${prettyPrintAst(state6.module, showTypes = true, showSourceSpans = showSpans)}"
  )

  val finalState = TailRecursionDetector.rewriteModule(state6)
  println("-" * 80)
  println(
    s"Tail Recursion phase \n${prettyPrintAst(finalState.module, showTypes = showTypes, showSourceSpans = showSpans)}"
  )

  val validated = CodegenStage.process(finalState)
  if validated.errors.nonEmpty then
    println("-" * 80)
    println(s"Pre-codegen validation errors:\n${validated.errors.toList}")
  else if showIr then
    CodegenStage.processIrOnly(validated).unsafeRunSync() match
      case codegenState =>
        codegenState.llvmIr match
          case Some(ir) =>
            println("-" * 80)
            println(s"LLVM IR:\n$ir")
          case None =>
            println("-" * 80)
            println(s"Codegen errors:\n${codegenState.errors.toList}")

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

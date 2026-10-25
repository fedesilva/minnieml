package mml.mmlclib.test

import cats.effect.IO
import mml.mmlclib.api.{FrontEndApi, ParserApi}
import mml.mmlclib.ast.{BindOwnership, Bnd, Error, Lambda, Member, Module, TermTraversal}
import mml.mmlclib.codegen.LlvmToolchain
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig}
import mml.mmlclib.semantic.*
import mml.mmlclib.test.llvm.LlvmAssertions
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.sys.process.{Process, ProcessLogger}

/** Base trait for effectful tests; adds common MML specific assertions. */
trait BaseEffFunSuite extends CatsEffectSuite with LlvmAssertions:

  /** Runs a test with a temporary source file and an initially absent output directory. */
  protected def withSourceFixture[A](
    source:   String,
    fileName: String
  )(use: (Path, CompilerConfig) => IO[A]): IO[A] =

    IO.blocking(Files.createTempDirectory("mml-source-fixture"))
      .bracket { directory =>
        val path   = directory.resolve(fileName)
        val config = CompilerConfig.default.copy(outputDir = directory.resolve("output"))
        IO.blocking(Files.writeString(path, source)).flatMap(_ => use(path, config))
      } { directory =>
        IO.blocking {
          val paths = Files.walk(directory)
          try
            paths
              .sorted(Comparator.reverseOrder[Path]())
              .forEach(path => Files.deleteIfExists(path))
          finally paths.close()
        }
      }

  /** Assert success through the compiler's shared toolchain command execution. */
  protected def commandNotFailed(command: List[String], workingDir: Path): IO[Unit] =
    LlvmToolchain
      .executeCommand(command, "Fixture command failed", workingDir, verbose = false)
      .map(_.fold(error => fail(error.message), code => assertEquals(code, 0)))

  /** Assert a fixture's exit code, optional stdout, and diagnostic text on stderr. */
  protected def programExits(
    command:        List[String],
    workingDir:     Path,
    expectedCode:   Int,
    expectedOutput: Option[List[String]] = None,
    expectedError:  Option[String]       = None
  ): IO[Unit] =
    IO.blocking {
      // The child's stdout and stderr callbacks can run concurrently.
      val stdout = new StringBuffer
      val stderr = new StringBuffer
      val logger = ProcessLogger(
        line =>
          stdout.append(s"$line\n")
          ()
        ,
        line =>
          stderr.append(s"$line\n")
          ()
      )
      val code       = Process(command, workingDir.toFile).!(logger)
      val diagnostic = s"${command.mkString(" ")}\n$stdout$stderr"
      assertEquals(code, expectedCode, diagnostic)
      expectedOutput.foreach(expected =>
        assertEquals(stdout.toString.linesIterator.toList, expected, diagnostic)
      )
      expectedError.foreach(expected => assert(stderr.toString.contains(expected), diagnostic))
    }

  final case class SemanticResult(module: Module, errors: List[SemanticError])

  /** Returns the unique resolved binding named within a module member. */
  protected def resolvedBindingId(module: Module, owner: String, name: String): String =

    val member = module.members
      .collectFirst { case binding: Bnd if binding.name == owner => binding }
      .getOrElse(fail(s"Expected member $owner"))
    val ids = TermTraversal
      .collect(member.value) {
        case lambda:  Lambda => lambda.params.filter(_.name == name).flatMap(_.id)
        case binding: BindOwnership if binding.valueParam.name == name =>
          binding.valueParam.id.toList
      }
      .flatten
      .distinct

    assertEquals(ids.size, 1, s"Expected one resolved binding for $owner.$name")
    ids.head

  private def containsErrorNode(module: Module): Boolean = {
    def checkMembers(members: List[Member]): Boolean =
      members.exists {
        case _: Error => true
        case _ => false
      }

    checkMembers(module.members)
  }

  private def collectErrors(module: Module): List[Error] = {
    module.members.collect { case error: Error =>
      error
    }
  }

  def parseNotFailed(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[Module] = {

    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) =>
        assert(
          !containsErrorNode(module),
          msg.getOrElse(
            s"Failed: found Error nodes:\n ${prettyPrintAst(module)}"
          )
        )
        module
      case Left(error) =>
        fail(msg.getOrElse("Parser Failed: .") + s"\n$error")
    }
  }

  def parseFailed(
    source: String,
    name:   String         = "TestFail",
    msg:    Option[String] = None
  ): IO[Unit] = {
    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) =>
        assert(
          containsErrorNode(module),
          msg.getOrElse(s"Expected Error nodes but found none. ${prettyPrintAst(module)} ")
        )
      case Left(_) =>
      // pass
    }
  }

  def parseFailedWithErrors(
    source: String,
    name:   String         = "TestErrors",
    msg:    Option[String] = None
  ): IO[List[Error]] = {
    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) =>
        val errors = collectErrors(module)
        if errors.isEmpty then
          import mml.mmlclib.util.prettyprint.ast.*
          prettyPrintAst(module)
        assert(
          errors.nonEmpty,
          msg.getOrElse(s"Expected Error nodes but found none. ${prettyPrintAst(module)}")
        )
        errors // Return just the errors for inspection
      case Left(error) =>
        fail(
          msg.getOrElse(
            "Parser should not fail completely, but should create error nodes."
          ) + s"\n$error"
        )
    }
  }

  def semNotFailed(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[Module] =
    FrontEndApi.compile(source, name).value.map {
      case Right(state) =>
        if state.errors.nonEmpty then
          fail(msg.getOrElse("Semantic Failed: .") + s"\n${state.errors}")
        else
          val module = state.module
          assert(
            !containsErrorNode(module),
            msg.getOrElse(
              s"Failed: found Error nodes:\n ${prettyPrintAst(module)}"
            )
          )
          module
      case Left(error) =>
        fail(msg.getOrElse("Semantic Failed: .") + s"\n${error}")
    }

  def semFailed(
    source: String,
    name:   String         = "TestFail",
    msg:    Option[String] = None
  ): IO[Unit] =
    FrontEndApi.compile(source, name).value.map {
      case Right(state) =>
        assert(
          state.errors.nonEmpty || containsErrorNode(state.module),
          msg.getOrElse(s"Expected errors but found none. ${prettyPrintAst(state.module)} ")
        )
      case Left(_) =>
      // pass
    }

  def semState(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[SemanticResult] =
    FrontEndApi.compile(source, name).value.map {
      case Right(state) => SemanticResult(state.module, state.semanticErrors.toList)
      case Left(error) =>
        fail(
          msg.getOrElse("Semantic pipeline failed before producing a state.") + s"\n$error"
        )
    }

  /** Semantic results with the same diagnostic selection used by CLI and editor presentation. */
  def semVisibleState(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[SemanticResult] =
    FrontEndApi.compile(source, name).value.map {
      case Right(state) =>
        SemanticResult(
          state.module,
          state.visibleErrors.collect { case e: SemanticError => e }.toList
        )
      case Left(error) =>
        fail(msg.getOrElse("Semantic pipeline failed before producing a state.") + s"\n$error")
    }

  /** Parse source code without asserting on MemberErrors. Useful for testing phases that
    * specifically deal with MemberErrors
    */
  def justParse(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[Module] =
    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) => module
      case Left(error) => fail(msg.getOrElse("Parser Failed: ") + s"\n$error")
    }

  protected def compileAndGenerate(
    source: String,
    name:   String         = "Test",
    config: CompilerConfig = CompilerConfig.default
  ): IO[String] =
    FrontEndApi.compile(source, name, config).value.flatMap {
      case Right(state) =>
        if state.errors.nonEmpty then fail(s"Compilation failed: ${state.errors}")
        else
          val validated = CodegenStage.validate(state)
          if validated.hasErrors then fail(s"Validation failed: ${validated.errors}")
          else
            CodegenStage.emitIrOnly(validated).flatMap { codegenState =>
              codegenState.llvmIr match
                case Some(ir) => IO.pure(ir)
                case None => fail(s"CodeGen failed: ${codegenState.errors}")
            }
      case Left(error) =>
        fail(s"Compilation failed: $error")
    }

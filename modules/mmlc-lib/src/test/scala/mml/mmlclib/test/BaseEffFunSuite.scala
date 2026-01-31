package mml.mmlclib.test

import cats.effect.IO
import mml.mmlclib.api.{FrontEndApi, ParserApi}
import mml.mmlclib.ast.{Error, Member, Module}
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig}
import mml.mmlclib.semantic.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.CatsEffectSuite

/** Base trait for effectful tests; adds common MML specific assertions. */
trait BaseEffFunSuite extends CatsEffectSuite:

  final case class SemanticResult(module: Module, errors: List[SemanticError])

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

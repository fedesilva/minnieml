package mml.mmlclib.test

import cats.effect.IO
import mml.mmlclib.api.{CompilerApi, ParserApi}
import mml.mmlclib.ast.{Error, Member, Module}
import mml.mmlclib.semantic.*
import mml.mmlclib.util.pipe.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.CatsEffectSuite

/** Base trait for effectful tests; adds common MML specific assertions. */
trait BaseEffFunSuite extends CatsEffectSuite:

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
      case Left(error) =>
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
    CompilerApi.compileString(source, name).value.map {
      case Right(module) =>
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
    CompilerApi.compileString(source, name).value.map {
      case Right(module) =>
        assert(
          containsErrorNode(module),
          msg.getOrElse(s"Expected Error nodes but found none. ${prettyPrintAst(module)} ")
        )
      case Left(error) =>
      // pass
    }

    // We need to change the underlying api
    // to pass the errors so we can use them in assertions
    // def semFailedWithErrors(
    //   source: String,
    //       name:   Option[String] = "TestFail".some,
    //       msg:    Option[String] = None
    //     ): IO[Option[]] =
    //       CompilerApi.compileString(source, name).value.map {
    //         case Right(module) =>
    //           assert(
    //             containsErrorNode(module),
    //             msg.getOrElse(s"Expected Error nodes but found none. ${prettyPrintAst(module)} ")
    //           )
    //         case Left(error) =>

    //       }

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

  def semWithState(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[mml.mmlclib.semantic.SemanticPhaseState] =

    justParse(source, name, msg).map { module =>
      val moduleWithTypes = injectBasicTypes(module)
      val moduleWithOps   = injectStandardOperators(moduleWithTypes)

      val initialState = SemanticPhaseState(moduleWithOps, Vector.empty)

      initialState
        |> DuplicateNameChecker.rewriteModule
        |> RefResolver.rewriteModule
        |> TypeResolver.rewriteModule
        |> ExpressionRewriter.rewriteModule
        |> ParsingErrorChecker.checkModule
        |> Simplifier.rewriteModule
    }

  protected def compileAndGenerate(
    source: String,
    name:   String = "Test"
  ): IO[String] =
    import mml.mmlclib.api.CodeGenApi
    CompilerApi.compileString(source, name).value.flatMap {
      case Right(compiled) =>
        CodeGenApi.generateFromModule(compiled).value.map {
          case Right(llvmIr) => llvmIr
          case Left(error) => fail(s"CodeGen failed: $error")
        }
      case Left(error) =>
        fail(s"Compilation failed: $error")
    }

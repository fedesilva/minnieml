package mml.mmlclib.test

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.{CompilerApi, ParserApi}
import mml.mmlclib.ast.{Member, MemberError, Module}
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.CatsEffectSuite

/** Base trait for effectful tests; adds common MML specific assertions. */
trait BaseEffFunSuite extends CatsEffectSuite:

  private def containsMemberError(module: Module): Boolean = {
    def checkMembers(members: List[Member]): Boolean =
      members.exists {
        case _: MemberError => true
        case _ => false
      }

    checkMembers(module.members)
  }

  def parseNotFailed(
    source: String,
    name:   Option[String] = "Test".some,
    msg:    Option[String] = None
  ): IO[Module] = {

    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) =>
        assert(
          !containsMemberError(module),
          msg.getOrElse(
            s"Failed: found MemberError nodes:\n ${prettyPrintAst(module)}"
          )
        )
        module
      case Left(error) =>
        fail(msg.getOrElse("Parser Failed: .") + s"\n$error")
    }
  }

  def parseFailed(
    source: String,
    name:   Option[String] = "TestFail".some,
    msg:    Option[String] = None
  ): IO[Unit] = {
    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) =>
        assert(
          containsMemberError(module),
          msg.getOrElse(s"Expected MemberError nodes but found none. ${prettyPrintAst(module)} ")
        )
      case Left(error) =>
      // pass
    }
  }

  def semNotFailed(
    source: String,
    name:   Option[String] = "Test".some,
    msg:    Option[String] = None
  ): IO[Module] =
    CompilerApi.compileString(source, name).value.map {
      case Right(module) =>
        assert(
          !containsMemberError(module),
          msg.getOrElse(
            s"Failed: found MemberError nodes:\n ${prettyPrintAst(module)}"
          )
        )
        module
      case Left(error) =>
        fail(msg.getOrElse("Semantic Failed: .") + s"\n${error}")
    }

  def semFailed(
    source: String,
    name:   Option[String] = "TestFail".some,
    msg:    Option[String] = None
  ): IO[Unit] =
    CompilerApi.compileString(source, name).value.map {
      case Right(module) =>
        assert(
          containsMemberError(module),
          msg.getOrElse(s"Expected MemberError nodes but found none. ${prettyPrintAst(module)} ")
        )
      case Left(error) =>
      // pass
    }

  /** Parse source code without asserting on MemberErrors. Useful for testing phases that
    * specifically deal with MemberErrors
    */
  def justParse(
    source: String,
    name:   Option[String] = "Test".some,
    msg:    Option[String] = None
  ): IO[Module] =
    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) => module
      case Left(error) => fail(msg.getOrElse("Parser Failed: ") + s"\n$error")
    }

  def semWithState(
    source: String,
    name:   Option[String] = "Test".some,
    msg:    Option[String] = None
  ): IO[mml.mmlclib.semantic.SemanticPhaseState] =
    import mml.mmlclib.semantic.*
    import mml.mmlclib.util.pipe.*

    justParse(source, name, msg).map { module =>
      val moduleWithOps = injectStandardOperators(module)
      val initialState  = SemanticPhaseState(moduleWithOps, Vector.empty)

      initialState
        |> DuplicateNameChecker.rewriteModule
        |> RefResolver.rewriteModule
        |> TypeResolver.rewriteModule
        |> ExpressionRewriter.rewriteModule
        |> MemberErrorChecker.checkModule
        |> Simplifier.rewriteModule
    }

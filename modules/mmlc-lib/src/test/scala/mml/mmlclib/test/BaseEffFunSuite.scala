package mml.mmlclib.test

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.Member
import mml.mmlclib.ast.MemberError
import mml.mmlclib.ast.Module
import mml.mmlclib.util.prettyPrintAst
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

  def modNotFailed(
    source: String,
    name:   Option[String] = "Test".some,
    msg:    Option[String] = None
  ): IO[Module] = {

    ParserApi.parseModuleString(source, name).map {
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

  def modFailed(
    source: String,
    name:   Option[String] = "TestFail".some,
    msg:    Option[String] = None
  ): IO[Unit] = {
    ParserApi.parseModuleString(source, name).map {
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
    CompilerApi.compileString(source, name).map {
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
    CompilerApi.compileString(source, name).map {
      case Right(module) =>
        assert(
          containsMemberError(module),
          msg.getOrElse(s"Expected MemberError nodes but found none. ${prettyPrintAst(module)} ")
        )
      case Left(error) =>
      // pass
    }

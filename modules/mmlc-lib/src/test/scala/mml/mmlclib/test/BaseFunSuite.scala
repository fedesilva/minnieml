package mml.mmlclib.test

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.{Member, MemberError, Module}
import mml.mmlclib.util.prettyPrintAst

/** Base trait for effectful tests; adds common MML specific assertions. */
trait BaseFunSuite extends munit.FunSuite {

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
          msg.getOrElse("Expected MemberError nodes but found none.")
        )
      case Left(error) =>
        assert(error.nonEmpty, msg.getOrElse("Expected errors, but got none."))
    }
  }
}

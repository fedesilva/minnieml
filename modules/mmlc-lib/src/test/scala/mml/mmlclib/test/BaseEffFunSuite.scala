package mml.mmlclib.test

import cats.effect.IO
import mml.mmlclib.api.impl.InMemoryAstApi
import mml.mmlclib.api.{AstApi, ParserApi}
import mml.mmlclib.ast.{Member, MemberError, Module}
import mml.mmlclib.util.prettyPrintAst
import munit.CatsEffectSuite
import cats.syntax.all.*

/** Base trait for effectful tests; adds common MML specific assertions. */
trait BaseEffFunSuite extends CatsEffectSuite {

//  given Monad[IO]  = cats.effect.IO.asyncForIO
  given AstApi[IO] = InMemoryAstApi

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
    name:   Option[String] = None,
    msg:    Option[String] = None
  ): IO[Module] = {

    ParserApi.parseModuleString[IO](source, name).map {
      case Right(module) =>
        assert(
          !containsMemberError(module),
          msg.getOrElse(
            s"Expected no errors, but found MemberError nodes:\n ${prettyPrintAst(module)}"
          )
        )
        module
      case Left(error) =>
        fail(msg.getOrElse("Expected successful parsing but got errors.") + s"\n$error")
    }
  }

  def modFailed(
    source: String,
    name:   Option[String] = None,
    msg:    Option[String] = None
  ): IO[Unit] = {
    ParserApi.parseModuleString[IO](source, name).map {
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

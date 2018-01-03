package mml.mmlclib.test

import mml.mmlclib.api.ParserApi
import mml.mmlclib.util.parser.ErrorChecker
import org.scalactic.source.Position
import org.scalatest.{Assertion, FunSuite, Matchers}

/**
  *  Base trait for `FunSuite` tests; adds common `Matchers` and some MML specific assertions.
  */
trait BaseFunSuite extends FunSuite with Matchers {

  def modNotFailed(source: String, msg: Option[String] = None)(
    implicit pos: Position
  ): Assertion = {

    val failures =
      ErrorChecker.failures(
        ParserApi().parseModuleString(source)
      )
    assert(
      failures.isEmpty,
      msg.getOrElse("") + s" $failures "
    )
  }

  def modFailed(source: String, msg: Option[String] = None)(
    implicit pos: Position
  ): Assertion = {

    val errors =
      ErrorChecker.failures(
        ParserApi().parseModuleString(source)
      )
    assert(errors.nonEmpty, msg.getOrElse(""))
  }

}

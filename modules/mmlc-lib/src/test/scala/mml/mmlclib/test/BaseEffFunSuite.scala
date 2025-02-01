package mml.mmlclib.test

import cats.effect.IO
import mml.mmlclib.api.ParserApi
import munit.CatsEffectSuite

/** Base trait for effectful tests; adds common MML specific assertions. */
trait BaseEffFunSuite extends CatsEffectSuite {

  def modNotFailed(source: String, msg: Option[String] = None): IO[Unit] = {
    ParserApi.parseModuleString(source).map { result =>
      val failures = ErrorChecker.failures(result)
      assert(failures.isEmpty, msg.getOrElse("") + s" $failures ")
    }
  }

  def modFailed(source: String, msg: Option[String] = None): IO[Unit] = {
    ParserApi.parseModuleString(source).map { result =>
      val errors = ErrorChecker.failures(result)
      assert(errors.nonEmpty, msg.getOrElse("Expected errors, but got none."))
    }
  }
}

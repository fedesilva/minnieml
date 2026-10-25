package mml.mmlclib.parser

import mml.mmlclib.test.BaseEffFunSuite

import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

class ParserDeadlineTests extends BaseEffFunSuite:

  private def assertStopped(result: ParserDeadline.Result): Unit =
    assert(result.stopped, result.output)
    assert(ProcessHandle.of(result.processId).toScala.forall(process => !process.isAlive))

  test("a child parses valid source and exits before its deadline") {
    ParserDeadline.run(List("parse", "let answer = 42;"), 15.seconds).map { result =>
      assert(!result.timedOut, result.output)
      assertEquals(result.exitCode, Some(0), result.output)
      assert(result.output.linesIterator.contains("clean module"), result.output)
      assertStopped(result)
    }
  }

  test("a child reports recovered parse errors rather than a clean module") {
    ParserDeadline.run(List("parse", "let answer = ;"), 15.seconds).map { result =>
      assert(!result.timedOut, result.output)
      assertEquals(result.exitCode, Some(10), result.output)
      assert(result.output.contains("recovered parse errors:"), result.output)
      assertStopped(result)
    }
  }

  test("a deadline stops and reaps a child that reached its blocking control") {
    ParserDeadline.run(List("wait"), 5.seconds).map { result =>
      assert(result.output.contains("deadline probe ready"), result.output)
      assert(result.timedOut, result.output)
      assert(result.exitCode.exists(_ != 0), result.output)
      assertStopped(result)
    }
  }

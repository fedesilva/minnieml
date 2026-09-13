package mml.mmlc

import cats.effect.{ExitCode, IO, Ref}
import cats.syntax.all.*
import mml.mmlc.CommandLineConfig.{Command, Config}
import munit.CatsEffectSuite
import scopt.OParser

class CommandLineConfigTests extends CatsEffectSuite:

  private def optimizerArgs(args: List[String]): List[String] =
    parse(args).map(_.command) match
      case Some(build: Command.Build) => build.llvmOptArgs
      case Some(run: Command.Run) => run.llvmOptArgs
      case other => fail(s"Expected a native compilation command, got $other")

  private val nativeCommands = List(Nil, List("-x", "lib"), List("run"))

  private def parse(args: List[String]): Option[Config] =
    OParser.runParser(CommandLineConfig.createParser, args, Config())._1

  test("optimization defaults to O3 and each compact flag selects its level") {
    nativeCommands.foreach { command =>
      assertEquals(parse(command :+ "source.mml").map(_.optLevel), Some(3))
      (0 to 3).foreach { level =>
        assertEquals(parse(command ++ List(s"-O$level", "source.mml")).map(_.optLevel), Some(level))
      }
    }
  }

  test("optimization flags are mutually exclusive in either order") {
    nativeCommands.foreach { command =>
      (0 to 3).foreach { first =>
        (0 to 3).foreach { second =>
          assertEquals(parse(command ++ List(s"-O$first", s"-O$second", "source.mml")), None)
        }
      }
    }
  }

  test("generic and unsupported optimization options are rejected") {
    val invalidOptions = List(List("-O", "3"), List("--opt", "3"), List("--opt=3"), List("-O4"))
    nativeCommands.foreach { command =>
      invalidOptions.foreach { options =>
        assertEquals(parse(command ++ options :+ "source.mml"), None)
      }
    }
  }

  test("forwarded LLVM optimization values do not count as MML optimization flags") {
    nativeCommands.foreach { command =>
      val args = command ++ List("-O3", "--llvm-opt-arg", "-O1", "source.mml")
      assertEquals(parse(args).map(_.optLevel), Some(3))
      assertEquals(optimizerArgs(args), List("-O1"))
    }
  }

  test("native commands leave LLVM options empty by default") {
    nativeCommands.foreach { command =>
      assertEquals(optimizerArgs(command :+ "source.mml"), Nil)
    }
  }

  test("repeated LLVM options preserve order, duplicates and argument boundaries") {
    val arguments = List(
      "-force-vector-interleave=4",
      "-pass-remarks=loop-vectorize",
      "-pass-remarks-output=remarks with 'quotes' and spaces.yaml",
      "-pass-remarks=loop-vectorize"
    )
    nativeCommands.foreach { command =>
      val options = arguments.map(arg => s"--llvm-opt-arg=$arg")
      assertEquals(optimizerArgs(command ++ options :+ "source.mml"), arguments)
    }
  }

  test("LLVM options accept separate values and leave option validation to LLVM") {
    nativeCommands.foreach { command =>
      assertEquals(
        optimizerArgs(command ++ List("--llvm-opt-arg", "-unknown-llvm-option", "source.mml")),
        List("-unknown-llvm-option")
      )
    }
  }

  private def assertCliResult(
    args:         List[String],
    expectedCode: ExitCode,
    expectedText: String
  ): IO[Unit] =
    for
      output <- Ref.of[IO, Vector[String]](Vector.empty)
      capture = (message: String) => output.update(_ :+ message)
      code <- Main.runWithOutput(args, capture, capture)
      lines <- output.get
    yield
      val diagnostic = lines.mkString("\n")
      assertEquals(code, expectedCode, diagnostic)
      assert(diagnostic.contains(expectedText), diagnostic)

  test("entrypoint treats forwarded help tokens as values rather than MML help") {
    nativeCommands.traverse_ { command =>
      List("--help", "-h").traverse_ { value =>
        List(List("--llvm-opt-arg", value), List(s"--llvm-opt-arg=$value")).traverse_ { option =>
          assertCliResult(command ++ option, ExitCode(1), "source-file")
        }
      }
    }
  }

  test("entrypoint preserves MML help and reports errors beside forwarded help values") {
    for
      _ <- List(List("--help"), List("run", "-h")).traverse_ { args =>
        assertCliResult(args, ExitCode.Success, "Usage: mmlc")
      }
      _ <- assertCliResult(
        List("--llvm-opt-arg", "--help", "--mml-invalid-option"),
        ExitCode(1),
        "Unknown option --mml-invalid-option"
      )
    yield ()
  }

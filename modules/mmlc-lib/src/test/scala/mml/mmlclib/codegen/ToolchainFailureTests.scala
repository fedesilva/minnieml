package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

class ToolchainFailureTests extends BaseEffFunSuite:

  test("a missing executable preserves argv and invalidates only the tool marker") {
    withSourceFixture("", "fixture") { (path, _) =>
      val directory = path.getParent
      val tool      = directory.resolve("absent tool").toString
      val command   = List(tool, "argument with spaces")
      val marker    = directory.resolve("llvm-info")
      for
        _ <- IO.blocking(Files.writeString(marker, "verified tools"))
        result <- LlvmToolchain.executeCommand(command, ToolOperation.Invoke, directory, false)
        _ = assertEquals(result, LlvmCompilationError.ToolNotFound(tool, command).asLeft[Int])
        _ <- IO.blocking {
          assert(!Files.exists(marker))
          val files = Files.list(directory)
          try
            val archived = files
              .iterator()
              .asScala
              .filter(_.getFileName.toString.startsWith("llvm-info-"))
              .toList
            assertEquals(archived.size, 1)
            assertEquals(Files.readString(archived.head), "verified tools")
          finally files.close()
        }
      yield ()
    }
  }

  test("an existing non-executable file is a launch failure and keeps the marker") {
    withSourceFixture("#!/bin/sh\nexit 0\n", "not executable") { (path, _) =>
      val command = List(path.toString, "argument with spaces")
      val marker  = path.getParent.resolve("llvm-info")
      for
        _ <- IO.blocking {
          assert(path.toFile.setExecutable(false, false))
          Files.writeString(marker, "verified tools")
        }
        result <- LlvmToolchain.executeCommand(command, ToolOperation.Invoke, path.getParent, false)
        _ = result match
          case Left(LlvmCompilationError.ProcessStartError(actual, detail)) =>
            assertEquals(actual, command)
            assert(detail.nonEmpty)
          case other => fail(s"Expected process-start failure, got $other")
        _ <- IO.blocking(assertEquals(Files.readString(marker), "verified tools"))
      yield ()
    }
  }

  test("an unsuccessful exit retains operation, argv, and external stderr without invalidation") {
    withSourceFixture("", "fixture") { (path, _) =>
      val command = List(
        "/bin/sh",
        "-c",
        "printf '%s\\n' \"$1\" >&2; exit 7",
        "fixture",
        "opaque external output"
      )
      val marker = path.getParent.resolve("llvm-info")
      for
        _ <- IO.blocking(Files.writeString(marker, "verified tools"))
        result <- LlvmToolchain.executeCommand(
          command,
          ToolOperation.Assemble,
          path.getParent,
          false
        )
        _ = assertEquals(
          result,
          LlvmCompilationError
            .CommandExecutionError(command, ToolOperation.Assemble, "opaque external output", 7)
            .asLeft[Int]
        )
        _ <- IO.blocking(assertEquals(Files.readString(marker), "verified tools"))
      yield ()
    }
  }

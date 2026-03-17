package mml.mmlclib.lsp

import cats.effect.IO
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class LspLoggingTests extends BaseEffFunSuite:

  test("rotation does nothing when server log is below threshold"):
    withTempDir("lsp-logging-no-rotate") { root =>
      val logDir    = root.resolve("lsp")
      val serverLog = logDir.resolve("server.log")
      for
        _ <- writeString(serverLog, "small-log")
        _ <- LspLogging.rotateIfNeeded(logDir, maxSizeBytes = 1024, maxFiles = 3)
        current <- readString(serverLog)
        rotatedExists <- exists(logDir.resolve("server.log.1"))
      yield
        assertEquals(current, "small-log")
        assertEquals(rotatedExists, false)
    }

  test("rotation shifts backups and deletes oldest beyond retention"):
    withTempDir("lsp-logging-rotate") { root =>
      val logDir    = root.resolve("lsp")
      val serverLog = logDir.resolve("server.log")
      for
        _ <- writeString(serverLog, "current-log")
        _ <- writeString(logDir.resolve("server.log.1"), "prev-1")
        _ <- writeString(logDir.resolve("server.log.2"), "prev-2")
        _ <- writeString(logDir.resolve("server.log.3"), "prev-3")
        _ <- LspLogging.rotateIfNeeded(logDir, maxSizeBytes = 1, maxFiles = 3)
        currentExists <- exists(serverLog)
        rotated1 <- readString(logDir.resolve("server.log.1"))
        rotated2 <- readString(logDir.resolve("server.log.2"))
        rotated3 <- readString(logDir.resolve("server.log.3"))
      yield
        assertEquals(currentExists, false)
        assertEquals(rotated1, "current-log")
        assertEquals(rotated2, "prev-1")
        assertEquals(rotated3, "prev-2")
    }

  test("create rotates oversized log before writing new entries"):
    withTempDir("lsp-logging-create") { outputDir =>
      val logDir    = outputDir.resolve("lsp")
      val serverLog = logDir.resolve("server.log")
      val oversized = Array.fill[Byte]((LspLogging.MaxLogSizeBytes + 1024).toInt)('x'.toByte)
      for
        _ <- IO.blocking {
          Files.createDirectories(logDir)
          Files.write(serverLog, oversized)
        }
        logger <- LspLogging.create(outputDir)
        _ <- logger.info("hello")
        currentExists <- exists(serverLog)
        rotatedExists <- exists(logDir.resolve("server.log.1"))
        currentSize <- fileSize(serverLog)
        rotatedSize <- fileSize(logDir.resolve("server.log.1"))
      yield
        assertEquals(currentExists, true)
        assertEquals(rotatedExists, true)
        assert(
          currentSize > 0L,
          s"Expected current log file to have fresh content, got $currentSize"
        )
        assert(
          rotatedSize > LspLogging.MaxLogSizeBytes,
          s"Expected rotated log to preserve oversized content, got $rotatedSize"
        )
    }

  private def writeString(path: Path, content: String): IO[Unit] =
    IO.blocking {
      Files.createDirectories(path.getParent)
      Files.writeString(path, content, StandardCharsets.UTF_8)
    }.void

  private def readString(path: Path): IO[String] =
    IO.blocking(Files.readString(path, StandardCharsets.UTF_8))

  private def exists(path: Path): IO[Boolean] =
    IO.blocking(Files.exists(path))

  private def fileSize(path: Path): IO[Long] =
    IO.blocking(Files.size(path))

  private def withTempDir[A](prefix: String)(f: Path => IO[A]): IO[A] =
    IO.blocking(Files.createTempDirectory(prefix)).bracket(f)(deleteRecursively)

  private def deleteRecursively(root: Path): IO[Unit] =
    IO.blocking {
      if Files.exists(root) then
        val walk = Files.walk(root)
        try
          val paths = walk.iterator().asScala.toList.reverse
          paths.foreach(path => Files.deleteIfExists(path))
        finally walk.close()
    }.void

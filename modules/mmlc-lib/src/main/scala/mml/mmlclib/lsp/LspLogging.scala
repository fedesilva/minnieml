package mml.mmlclib.lsp

import cats.effect.IO
import org.typelevel.log4cats.Logger

import java.io.{FileWriter, PrintWriter}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LspLogging:

  private[lsp] val MaxLogSizeBytes: Long = 5L * 1024 * 1024
  private[lsp] val MaxRotatedFiles: Int  = 10
  private val LogFileName = "server.log"

  /** Create a Logger[IO] that writes to $outputDir/lsp/server.log. */
  def create(outputDir: Path): IO[Logger[IO]] =
    IO.blocking {
      val logDir = outputDir.resolve("lsp")
      Files.createDirectories(logDir)
      rotateIfNeededBlocking(logDir, MaxLogSizeBytes, MaxRotatedFiles)
      val logFile = logDir.resolve(LogFileName)
      val writer  = new PrintWriter(new FileWriter(logFile.toFile, true), true)
      fileLogger(writer)
    }

  private[lsp] def rotateIfNeeded(
    logDir:       Path,
    maxSizeBytes: Long = MaxLogSizeBytes,
    maxFiles:     Int  = MaxRotatedFiles
  ): IO[Unit] =
    IO.blocking(rotateIfNeededBlocking(logDir, maxSizeBytes, maxFiles))

  private def rotateIfNeededBlocking(logDir: Path, maxSizeBytes: Long, maxFiles: Int): Unit =
    if maxFiles > 0 then
      val logFile = logDir.resolve(LogFileName)
      if Files.exists(logFile) && Files.size(logFile) > maxSizeBytes then
        Files.deleteIfExists(rotatedFile(logDir, maxFiles))

        (1 until maxFiles).reverse.foreach { index =>
          val from = rotatedFile(logDir, index)
          if Files.exists(from) then
            val to = rotatedFile(logDir, index + 1)
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }

        Files.move(logFile, rotatedFile(logDir, 1), StandardCopyOption.REPLACE_EXISTING)

  private def rotatedFile(logDir: Path, index: Int): Path =
    logDir.resolve(s"$LogFileName.$index")

  private def fileLogger(writer: PrintWriter): Logger[IO] =
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    def write(level: String, msg: => String): IO[Unit] =
      IO.blocking {
        val ts = LocalDateTime.now().format(fmt)
        writer.println(s"$ts [$level] $msg")
        writer.flush()
      }

    def writeErr(level: String, t: Throwable, msg: => String): IO[Unit] =
      write(level, s"$msg — ${t.getMessage}") *>
        write(level, s"  ${t.getStackTrace.take(15).mkString("\n  ")}")

    new Logger[IO]:
      def error(message: => String):                     IO[Unit] = write("ERROR", message)
      def warn(message:  => String):                     IO[Unit] = write("WARN", message)
      def info(message:  => String):                     IO[Unit] = write("INFO", message)
      def debug(message: => String):                     IO[Unit] = write("DEBUG", message)
      def trace(message: => String):                     IO[Unit] = write("TRACE", message)
      def error(t:       Throwable)(message: => String): IO[Unit] = writeErr("ERROR", t, message)
      def warn(t:        Throwable)(message: => String): IO[Unit] = writeErr("WARN", t, message)
      def info(t:        Throwable)(message: => String): IO[Unit] = writeErr("INFO", t, message)
      def debug(t:       Throwable)(message: => String): IO[Unit] = writeErr("DEBUG", t, message)
      def trace(t:       Throwable)(message: => String): IO[Unit] = writeErr("TRACE", t, message)

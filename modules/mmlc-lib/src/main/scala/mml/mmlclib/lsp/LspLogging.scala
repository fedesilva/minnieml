package mml.mmlclib.lsp

import cats.effect.IO
import org.typelevel.log4cats.Logger

import java.io.{FileWriter, PrintWriter}
import java.nio.file.{Files, Path}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LspLogging:

  /** Create a Logger[IO] that writes to $outputDir/lsp/server.log. */
  def create(outputDir: Path): IO[Logger[IO]] =
    IO.blocking {
      val logDir = outputDir.resolve("lsp")
      Files.createDirectories(logDir)
      val logFile = logDir.resolve("server.log")
      val writer  = new PrintWriter(new FileWriter(logFile.toFile, true), true)
      fileLogger(writer)
    }

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

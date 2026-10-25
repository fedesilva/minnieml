package mml.mmlclib.parser

import cats.effect.{IO, Resource}
import cats.syntax.all.*

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import scala.concurrent.duration.*

/** Runs parser probes in a child JVM so a CPU-bound parser cannot outlive its deadline. */
object ParserDeadline:

  case class Result(
    timedOut:  Boolean,
    exitCode:  Option[Int],
    output:    String,
    processId: Long,
    stopped:   Boolean
  )

  private val cleanupTimeout = 5.seconds

  private def loaderPaths(loader: ClassLoader): List[String] =
    val current = loader match
      case urls: URLClassLoader =>
        urls.getURLs.toList
          .filter(_.getProtocol == "file")
          .map(url => Path.of(url.toURI).toString)
      case _ => Nil

    current ++ Option(loader.getParent).toList.flatMap(loaderPaths)

  private def classpath: String =
    val loaders = List(
      Option(getClass.getClassLoader),
      Option(Thread.currentThread().getContextClassLoader)
    ).flatten
    val runtime = System
      .getProperty("java.class.path", "")
      .split(Pattern.quote(File.pathSeparator))
      .toList

    (loaders.flatMap(loaderPaths) ++ runtime)
      .filter(_.nonEmpty)
      .distinct
      .mkString(File.pathSeparator)

  private def stopAndReap(process: Process): IO[Boolean] =
    IO.blocking {
      // Process lifecycle mutation is confined to this test I/O boundary.
      if process.isAlive then process.destroyForcibly()
      process.waitFor(cleanupTimeout.toNanos, TimeUnit.NANOSECONDS)
    }

  private def closeStreams(process: Process): IO[Unit] =
    List(process.getInputStream, process.getErrorStream, process.getOutputStream)
      .traverse_(stream => IO.blocking(stream.close()))

  private def child(arguments: List[String], output: Path): Resource[IO, Process] =
    val launch = IO.blocking {
      val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val command = List(java, "-cp", classpath, "mml.mmlclib.parser.ParserDeadlineProbe") ++
        arguments

      new ProcessBuilder(command*)
        .redirectErrorStream(true)
        .redirectOutput(output.toFile)
        .start()
    }

    Resource.make(launch) { process =>
      stopAndReap(process).void.guarantee(closeStreams(process))
    }

  /** Returns only after the child's deadline or exit and bounded process cleanup. */
  def run(arguments: List[String], timeout: FiniteDuration): IO[Result] =
    val output = Resource.make(IO.blocking(Files.createTempFile("mml-parser-deadline", ".txt"))) {
      path => IO.blocking(Files.deleteIfExists(path)).void
    }

    output.use { path =>
      child(arguments, path).use { process =>
        for
          completed <- IO.interruptible(process.waitFor(timeout.toNanos, TimeUnit.NANOSECONDS))
          stopped <- if completed then IO.pure(true) else stopAndReap(process)
          result <- IO.blocking {
            Result(
              timedOut  = !completed,
              exitCode  = Option.when(stopped)(process.exitValue()),
              output    = Files.readString(path),
              processId = process.pid(),
              stopped   = stopped
            )
          }
        yield result
      }
    }

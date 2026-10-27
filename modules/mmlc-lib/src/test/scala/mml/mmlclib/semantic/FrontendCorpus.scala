package mml.mmlclib.semantic

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.compiler.CompilerConfig
import upickle.default.{Writer, write}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Reports frontend diagnostics for shipped sources as one JSON record per relative path. */
object FrontendCorpus extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    args match
      case root :: Nil =>
        IO.blocking(Path.of(root).toAbsolutePath.normalize()).attempt.flatMap {
          case Left(error) => reportFailure(error.toString)
          case Right(path) => collect(path)
        }

      case _ => reportFailure("Usage: FrontendCorpus <repo-root>")

  private case class SourceDirectory(path: String, depth: Int)

  private enum Outcome(val label: String, val incomplete: Boolean):
    case Clean             extends Outcome("clean", false)
    case Diagnostics       extends Outcome("diagnostics", false)
    case Exception         extends Outcome("exception", true)
    case FrontendFailure   extends Outcome("frontend-failure", true)
    case CollectionFailure extends Outcome("collection-failure", true)

  private given Writer[Outcome] = upickle.default.writer[String].comap(_.label)

  private case class Result(
    path:          String,
    outcome:       Outcome,
    rawErrorCount: Int,
    errors:        Vector[String]
  ) derives Writer

  private val directories = List(
    SourceDirectory("mml/samples", Int.MaxValue),
    SourceDirectory("benchmark", 1),
    SourceDirectory("tests/smoke", 1),
    SourceDirectory("tests/mem", 1)
  )

  private def relativeMessage(message: String, root: Path): String =
    message.replace(root.toString + root.getFileSystem.getSeparator, "")

  private def inputs(root: Path): IO[Either[String, List[Path]]] =
    directories
      .traverse { directory =>
        Resource
          .fromAutoCloseable(
            IO.blocking(Files.walk(root.resolve(directory.path), directory.depth))
          )
          .use { stream =>
            IO.blocking {
              stream
                .iterator()
                .asScala
                .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".mml"))
                .toList
            }
          }
          .attempt
          .map(_.leftMap { error =>
            s"Cannot scan ${directory.path}: ${relativeMessage(error.toString, root)}"
          })
      }
      .map(_.sequence.map(_.flatten.sortBy(path => root.relativize(path).toString)))

  private def compile(root: Path, path: Path): IO[Result] =
    val relativePath = root.relativize(path).iterator().asScala.mkString("/")

    CompilerApi.compilePath(path, CompilerConfig.default).attempt.map {
      case Left(error) =>
        Result(relativePath, Outcome.Exception, 0, Vector(relativeMessage(error.toString, root)))

      case Right(Left(error)) =>
        Result(relativePath, Outcome.FrontendFailure, 0, Vector(relativeMessage(error, root)))

      case Right(Right(state)) =>
        Result(
          relativePath,
          if state.hasErrors then Outcome.Diagnostics else Outcome.Clean,
          state.errors.size,
          state.visibleErrors.map(error => relativeMessage(error.message, root))
        )
    }

  private def reportFailure(message: String): IO[ExitCode] =
    IO.println(write(Result(".", Outcome.CollectionFailure, 0, Vector(message))))
      .as(ExitCode.Error)

  private def collect(root: Path): IO[ExitCode] =
    inputs(root).flatMap {
      case Left(error) => reportFailure(error)

      case Right(paths) =>
        paths
          .foldLeftM(false) { (incomplete, path) =>
            compile(root, path).flatMap { result =>
              IO.println(write(result)).as(incomplete || result.outcome.incomplete)
            }
          }
          .map(incomplete => if incomplete then ExitCode.Error else ExitCode.Success)
    }

package mml.mmlclib.compiler

import cats.effect.IO
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.parser.SourceInfo

import java.net.URI
import java.nio.file.Path

/** Shared compilation logic used by both dev mode and LSP. */
object Compilation:

  /** Compile source content and return the compiler state. */
  def compileSource(
    content:    String,
    moduleName: String,
    sourcePath: String,
    config:     CompilerConfig
  ): IO[CompilerState] =
    FrontEndApi.compile(content, moduleName, config, Some(sourcePath)).value.map {
      case Right(state) => state
      case Left(_) => emptyState(moduleName, content, config)
    }

  /** Compile a file from disk. */
  def compileFile(path: Path, config: CompilerConfig): IO[Either[String, CompilerState]] =
    val moduleName = moduleNameFromPath(path)
    val sourcePath = sourcePathFromPath(path)
    FileOperations.readFile(path).flatMap {
      case Left(error) =>
        IO.pure(Left(s"Error reading file: ${error.getMessage}"))
      case Right(content) =>
        compileSource(content, moduleName, sourcePath, config).map(Right(_))
    }

  /** Extract module name from a file path. */
  def moduleNameFromPath(path: Path): String =
    FileOperations.sanitizeFileName(path)

  /** Convert a file path to a relative source path. */
  def sourcePathFromPath(path: Path): String =
    val cwd      = Path.of("").toAbsolutePath.normalize()
    val absolute = path.toAbsolutePath.normalize()
    try cwd.relativize(absolute).toString
    catch case _: IllegalArgumentException => path.normalize().toString

  /** Extract module name from a URI. */
  def moduleNameFromUri(uri: String): String =
    try
      val path     = new URI(uri).getPath
      val fileName = path.substring(path.lastIndexOf('/') + 1)
      FileOperations.sanitizeFileName(Path.of(fileName))
    catch case _: Exception => "Module"

  /** Extract source path from a URI. */
  def sourcePathFromUri(uri: String): String =
    try new URI(uri).getPath
    catch case _: Exception => uri

  private def emptyState(
    moduleName: String,
    content:    String,
    config:     CompilerConfig
  ): CompilerState =
    import mml.mmlclib.ast.*
    val module = Module(SourceOrigin.Synth, moduleName, Visibility.Public, Nil)
    CompilerState.empty(module, SourceInfo(content), config)

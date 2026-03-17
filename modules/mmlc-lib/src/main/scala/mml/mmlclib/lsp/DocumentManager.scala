package mml.mmlclib.lsp

import cats.effect.{IO, Ref}
import mml.mmlclib.compiler.{Compilation, CompilerConfig, CompilerState}
import org.typelevel.log4cats.Logger

/** State of a single open document. */
case class DocumentState(
  uri:     String,
  content: String,
  version: Int,
  state:   CompilerState
)

/** Manages open documents in the LSP server. */
class DocumentManager(
  config:       CompilerConfig,
  documentsRef: Ref[IO, Map[String, DocumentState]],
  logger:       Logger[IO]
):

  /** Open a document. Compiles and returns the state. */
  def open(uri: String, content: String, version: Int): IO[Option[CompilerState]] =
    logger.debug(s"Opening document: $uri (version $version)") *>
      compileAndStore(uri, content, version)

  /** Update a document. Compiles and returns the state. */
  def change(uri: String, content: String, version: Int): IO[Option[CompilerState]] =
    logger.debug(s"Document changed: $uri (version $version)") *>
      compileAndStore(uri, content, version)

  /** Close a document. */
  def close(uri: String): IO[Unit] =
    logger.debug(s"Closing document: $uri") *>
      documentsRef.update(_ - uri)

  /** Get the current state of a document. */
  def get(uri: String): IO[Option[DocumentState]] =
    documentsRef.get.map(_.get(uri))

  /** Get all open documents. */
  def all: IO[Map[String, DocumentState]] =
    documentsRef.get

  private def compileAndStore(
    uri:     String,
    content: String,
    version: Int
  ): IO[Option[CompilerState]] =
    val moduleName = Compilation.moduleNameFromUri(uri)
    val sourcePath = Compilation.sourcePathFromUri(uri)
    logger.debug(s"Compiling $uri (module=$moduleName)") *>
      Compilation
        .compileSource(content, moduleName, sourcePath, config)
        .flatMap { state =>
          val errorCount = state.errors.size
          val docState   = DocumentState(uri, content, version, state)
          logger.debug(s"Compiled $uri: $errorCount error(s)") *>
            documentsRef.update(_.updated(uri, docState)).as(Some(state))
        }
        .handleErrorWith { e =>
          logger.error(e)(s"Compilation failed for $uri") *> IO.pure(None)
        }

object DocumentManager:

  /** Create a new document manager. */
  def create(config: CompilerConfig, logger: Logger[IO]): IO[DocumentManager] =
    Ref.of[IO, Map[String, DocumentState]](Map.empty).map { ref =>
      new DocumentManager(config, ref, logger)
    }

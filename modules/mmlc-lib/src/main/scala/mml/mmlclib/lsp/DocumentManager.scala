package mml.mmlclib.lsp

import cats.effect.{IO, Ref}
import mml.mmlclib.compiler.{Compilation, CompilerConfig, CompilerState}

/** State of a single open document. */
case class DocumentState(
  uri:     String,
  content: String,
  version: Int,
  state:   CompilerState
)

/** Manages open documents in the LSP server. */
class DocumentManager(config: CompilerConfig, documentsRef: Ref[IO, Map[String, DocumentState]]):

  /** Open a document. Compiles and returns the state. */
  def open(uri: String, content: String, version: Int): IO[Option[CompilerState]] =
    compileAndStore(uri, content, version)

  /** Update a document. Compiles and returns the state. */
  def change(uri: String, content: String, version: Int): IO[Option[CompilerState]] =
    compileAndStore(uri, content, version)

  /** Close a document. */
  def close(uri: String): IO[Unit] =
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
    Compilation.compileSource(content, moduleName, sourcePath, config).flatMap { state =>
      val docState = DocumentState(uri, content, version, state)
      documentsRef.update(_.updated(uri, docState)).as(Some(state))
    }

object DocumentManager:

  /** Create a new document manager. */
  def create(config: CompilerConfig): IO[DocumentManager] =
    Ref.of[IO, Map[String, DocumentState]](Map.empty).map { ref =>
      new DocumentManager(config, ref)
    }

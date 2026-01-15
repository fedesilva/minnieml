package mml.mmlclib.lsp

import cats.effect.IO
import mml.mmlclib.compiler.CompilerState

import java.io.{BufferedReader, PrintStream}
import scala.concurrent.duration.*

/** LSP handler that processes requests and manages state. */
class LspHandler(
  documentManager: DocumentManager,
  input:           BufferedReader,
  output:          PrintStream
):

  private var initialized = false
  private var shutdown    = false

  private def log(msg: String): IO[Unit] =
    IO.blocking(System.err.println(s"[LSP] $msg"))

  /** Main loop - reads and handles messages until exit. */
  def run: IO[Unit] =
    handleNextMessage.flatMap { continue =>
      if continue then run else IO.unit
    }

  /** Handle a single message. Returns false if we should exit. */
  def handleNextMessage: IO[Boolean] =
    JsonRpc.readMessage(input).flatMap {
      case Left(error) =>
        IO.println(s"[LSP] Read error: $error") *> IO.pure(false)
      case Right(msg) =>
        handleMessage(msg)
    }

  private def handleMessage(msg: RpcMessage): IO[Boolean] =
    msg match
      case req: RpcRequest =>
        handleRequest(req) *> IO.delay(!shutdown)
      case notif: RpcNotification =>
        handleNotification(notif) *> IO.delay(!shutdown)
      case _: RpcResponse =>
        IO.delay(!shutdown)

  private def handleRequest(req: RpcRequest): IO[Unit] =
    req.method match
      case "initialize" =>
        handleInitialize(req.id)
      case "shutdown" =>
        handleShutdown(req.id)
      case "textDocument/hover" =>
        req.params match
          case Some(params) => handleHover(req.id, params)
          case None =>
            JsonRpc.writeError(
              output,
              req.id,
              RpcError(RpcError.InvalidParams, "Missing params")
            )
      case "textDocument/definition" =>
        req.params match
          case Some(params) => handleDefinition(req.id, params)
          case None =>
            JsonRpc.writeError(
              output,
              req.id,
              RpcError(RpcError.InvalidParams, "Missing params")
            )
      case "textDocument/references" =>
        req.params match
          case Some(params) => handleReferences(req.id, params)
          case None =>
            JsonRpc.writeError(
              output,
              req.id,
              RpcError(RpcError.InvalidParams, "Missing params")
            )
      case "textDocument/semanticTokens/full" =>
        req.params match
          case Some(params) => handleSemanticTokens(req.id, params)
          case None =>
            JsonRpc.writeError(
              output,
              req.id,
              RpcError(RpcError.InvalidParams, "Missing params")
            )
      case "workspace/executeCommand" =>
        req.params match
          case Some(params) => handleExecuteCommand(req.id, params)
          case None =>
            JsonRpc.writeError(
              output,
              req.id,
              RpcError(RpcError.InvalidParams, "Missing params")
            )
      case "workspace/symbol" =>
        req.params match
          case Some(params) => handleWorkspaceSymbol(req.id, params)
          case None =>
            JsonRpc.writeError(
              output,
              req.id,
              RpcError(RpcError.InvalidParams, "Missing params")
            )
      case method =>
        JsonRpc.writeError(
          output,
          req.id,
          RpcError(RpcError.MethodNotFound, s"Method not found: $method")
        )

  private def handleNotification(notif: RpcNotification): IO[Unit] =
    notif.method match
      case "initialized" =>
        initialized = true
        IO.unit
      case "exit" =>
        log("Exit notification received") *> IO { shutdown = true }
      case "textDocument/didOpen" =>
        notif.params match
          case Some(params) => handleDidOpen(params)
          case None => IO.unit
      case "textDocument/didChange" =>
        notif.params match
          case Some(params) => handleDidChange(params)
          case None => IO.unit
      case "textDocument/didClose" =>
        notif.params match
          case Some(params) => handleDidClose(params)
          case None => IO.unit
      case _ =>
        IO.unit

  private def handleInitialize(id: ujson.Value): IO[Unit] =
    val capabilities = ServerCapabilities.toJson(
      hoverProvider           = true,
      definitionProvider      = true,
      referencesProvider      = true,
      workspaceSymbolProvider = true,
      textDocumentSync        = 1,
      commands                = LspCommands.all,
      semanticTokensProvider  = Some(SemanticTokens.legend)
    )
    val result = InitializeResult.toJson(capabilities)
    JsonRpc.writeResponse(output, id, result)

  private def handleShutdown(id: ujson.Value): IO[Unit] =
    log("Shutdown request received") *>
      IO { shutdown = true } *>
      JsonRpc.writeResponse(output, id, ujson.Null)

  private def handleDidOpen(params: ujson.Value): IO[Unit] =
    val textDocument = params("textDocument")
    val uri          = textDocument("uri").str
    val text         = textDocument("text").str
    val version      = textDocument.obj.get("version").map(_.num.toInt).getOrElse(0)
    documentManager.open(uri, text, version).flatMap {
      case Some(state) => publishDiagnostics(uri, state)
      case None => IO.unit
    }

  private def handleDidChange(params: ujson.Value): IO[Unit] =
    val textDocument   = params("textDocument")
    val uri            = textDocument("uri").str
    val version        = textDocument.obj.get("version").map(_.num.toInt).getOrElse(0)
    val contentChanges = params("contentChanges").arr
    contentChanges.lastOption match
      case Some(change) =>
        val text = change("text").str
        documentManager.change(uri, text, version).flatMap {
          case Some(state) => publishDiagnostics(uri, state)
          case None => IO.unit
        }
      case None => IO.unit

  private def handleDidClose(params: ujson.Value): IO[Unit] =
    val uri = params("textDocument")("uri").str
    clearDiagnostics(uri) *> documentManager.close(uri)

  private def handleHover(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val pos = TextDocumentPositionParams.fromJson(params)
    documentManager.get(pos.textDocument.uri).flatMap {
      case None =>
        JsonRpc.writeResponse(output, id, ujson.Null)
      case Some(docState) =>
        val line = pos.position.line + 1
        val col  = pos.position.character + 1
        AstLookup.findAt(docState.state.module, line, col) match
          case None =>
            JsonRpc.writeResponse(output, id, ujson.Null)
          case Some(result) =>
            val typeStr = AstLookup.formatType(result.typeSpec)
            val content = result.name match
              case Some(n) => s"`${n}`: $typeStr"
              case None => typeStr
            val hover = Hover(
              contents = MarkupContent(MarkupContent.Markdown, content),
              range    = Some(Range.fromSrcSpan(result.span))
            )
            JsonRpc.writeResponse(output, id, Hover.toJson(hover))
    }

  private def handleDefinition(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val pos = TextDocumentPositionParams.fromJson(params)
    documentManager.get(pos.textDocument.uri).flatMap {
      case None =>
        JsonRpc.writeResponse(output, id, ujson.Null)
      case Some(docState) =>
        val line  = pos.position.line + 1
        val col   = pos.position.character + 1
        val spans = AstLookup.findDefinitionAt(docState.state.module, line, col)
        if spans.isEmpty then JsonRpc.writeResponse(output, id, ujson.Null)
        else
          val locations = spans.map(span => Location(pos.textDocument.uri, Range.fromSrcSpan(span)))
          val json      = ujson.Arr(locations.map(Location.toJson)*)
          JsonRpc.writeResponse(output, id, json)
    }

  private def handleReferences(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val pos = TextDocumentPositionParams.fromJson(params)
    val includeDecl = params.obj
      .get("context")
      .flatMap(_.obj.get("includeDeclaration"))
      .exists(_.bool)
    documentManager.get(pos.textDocument.uri).flatMap {
      case None =>
        JsonRpc.writeResponse(output, id, ujson.Arr())
      case Some(docState) =>
        val line      = pos.position.line + 1
        val col       = pos.position.character + 1
        val spans     = AstLookup.findReferencesAt(docState.state.module, line, col, includeDecl)
        val locations = spans.map(span => Location(pos.textDocument.uri, Range.fromSrcSpan(span)))
        val json      = ujson.Arr(locations.map(Location.toJson)*)
        JsonRpc.writeResponse(output, id, json)
    }

  private def handleSemanticTokens(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val uri = params("textDocument")("uri").str
    documentManager.get(uri).flatMap {
      case None =>
        JsonRpc.writeResponse(output, id, ujson.Null)
      case Some(docState) =>
        val result = SemanticTokens.compute(docState.state.module)
        JsonRpc.writeResponse(output, id, SemanticTokensResult.toJson(result))
    }

  private def handleWorkspaceSymbol(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val query = params.obj.get("query").map(_.str).getOrElse("").toLowerCase
    documentManager.all.flatMap { docs =>
      val symbols = docs.toList.flatMap { case (uri, docState) =>
        AstLookup.collectSymbols(docState.state.module, uri).filter { sym =>
          query.isEmpty || sym.name.toLowerCase.contains(query)
        }
      }
      val json = ujson.Arr(symbols.map(SymbolInformation.toJson)*)
      JsonRpc.writeResponse(output, id, json)
    }

  /** Publish diagnostics for a document. */
  def publishDiagnostics(uri: String, state: CompilerState): IO[Unit] =
    val diagnostics = Diagnostics.fromCompilerState(state)
    val params      = PublishDiagnosticsParams(uri, diagnostics)
    JsonRpc.writeNotification(
      output,
      "textDocument/publishDiagnostics",
      PublishDiagnosticsParams.toJson(params)
    )

  /** Clear diagnostics for a document. */
  def clearDiagnostics(uri: String): IO[Unit] =
    val params = PublishDiagnosticsParams(uri, Nil)
    JsonRpc.writeNotification(
      output,
      "textDocument/publishDiagnostics",
      PublishDiagnosticsParams.toJson(params)
    )

  private def handleExecuteCommand(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val command   = params("command").str
    val arguments = params.obj.get("arguments").map(_.arr.toList).getOrElse(Nil)
    command match
      case LspCommands.Restart =>
        // Respond first, flush, delay to ensure response is transmitted, then signal shutdown
        log("Restart command received") *>
          JsonRpc.writeResponse(output, id, ujson.Obj("success" -> true)).flatMap { _ =>
            IO.blocking(output.flush()) *> IO.sleep(100.millis) *> IO { shutdown = true }
          }
      case LspCommands.CompileBin =>
        executeCompile(id, arguments, isLib = false)
      case LspCommands.CompileLib =>
        executeCompile(id, arguments, isLib = true)
      case _ =>
        JsonRpc.writeError(
          output,
          id,
          RpcError(RpcError.InvalidParams, s"Unknown command: $command")
        )

  private def executeCompile(
    id:        ujson.Value,
    arguments: List[ujson.Value],
    isLib:     Boolean
  ): IO[Unit] =
    val uriOpt = arguments.headOption.flatMap(_.strOpt)
    uriOpt match
      case None =>
        JsonRpc.writeError(
          output,
          id,
          RpcError(RpcError.InvalidParams, "Missing file URI argument")
        )
      case Some(uri) =>
        val path = uriToPath(uri)
        val cmd  = if isLib then "lib" else "bin"
        IO.blocking {
          import scala.sys.process.*
          val result = Process(List("mmlc", cmd, path)).!
          result
        }.flatMap { exitCode =>
          val success = exitCode == 0
          JsonRpc.writeResponse(
            output,
            id,
            ujson.Obj("success" -> success, "exitCode" -> exitCode)
          )
        }

  private def uriToPath(uri: String): String =
    if uri.startsWith("file://") then uri.stripPrefix("file://")
    else uri

/** Supported LSP commands (server-side). */
object LspCommands:
  val Restart:    String       = "mml.server.restart"
  val CompileBin: String       = "mml.server.compileBin"
  val CompileLib: String       = "mml.server.compileLib"
  val all:        List[String] = List(Restart, CompileBin, CompileLib)

object LspHandler:

  def create(
    documentManager: DocumentManager,
    input:           BufferedReader,
    output:          PrintStream
  ): LspHandler =
    new LspHandler(documentManager, input, output)

  /** Convert a file path to a file:// URI. */
  def pathToUri(path: java.nio.file.Path): String =
    s"file://${path.toAbsolutePath.normalize()}"

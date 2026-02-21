package mml.mmlclib.lsp

import cats.effect.IO
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import org.typelevel.log4cats.Logger

import java.io.{InputStream, PrintStream}
import java.nio.file.Path
import scala.concurrent.duration.*

/** LSP handler that processes requests and manages state. */
class LspHandler(
  documentManager: DocumentManager,
  input:           InputStream,
  output:          PrintStream,
  logger:          Logger[IO]
):

  private case class HandlerState(
    initialized: Boolean,
    shutdown:    Boolean
  )

  private def requireInitialized(
    id:    ujson.Value,
    state: HandlerState
  )(action: => IO[HandlerState]): IO[HandlerState] =
    if state.initialized then action
    else
      logger.warn(s"Request rejected: server not initialized (id=$id)") *>
        JsonRpc
          .writeError(
            output,
            id,
            RpcError(RpcError.ServerNotInitialized, "Server not initialized")
          )
          .as(state)

  /** Main loop - reads and handles messages until exit. */
  def run: IO[Unit] =
    loop(HandlerState(initialized = false, shutdown = false))

  private def loop(state: HandlerState): IO[Unit] =
    if state.shutdown then logger.info("Message loop ending")
    else handleNextMessage(state).flatMap(loop)

  /** Handle a single message and return the next handler state. */
  private def handleNextMessage(state: HandlerState): IO[HandlerState] =
    JsonRpc.readMessage(input).flatMap {
      case Left(error) =>
        logger.error(s"Read error: $error") *> IO.pure(state.copy(shutdown = true))
      case Right(msg) =>
        handleMessage(msg, state).handleErrorWith { e =>
          logger.error(e)(s"Error handling message: ${msgSummary(msg)}") *>
            (msg match
              case req: RpcRequest =>
                JsonRpc.writeError(
                  output,
                  req.id,
                  RpcError(RpcError.InternalError, s"Internal error: ${e.toString}")
                )
              case _ => IO.unit
            ).as(state)
        }
    }

  private def msgSummary(msg: RpcMessage): String =
    msg match
      case req:   RpcRequest => s"request ${req.method} id=${req.id}"
      case notif: RpcNotification => s"notification ${notif.method}"
      case resp:  RpcResponse => s"response id=${resp.id}"

  private def handleMessage(msg: RpcMessage, state: HandlerState): IO[HandlerState] =
    msg match
      case req: RpcRequest =>
        logger.info(s"<-- request: ${req.method} id=${req.id}") *>
          handleRequest(req, state)
      case notif: RpcNotification =>
        logger.info(s"<-- notification: ${notif.method}") *>
          handleNotification(notif, state)
      case resp: RpcResponse =>
        logger.info(s"<-- response: id=${resp.id}") *>
          IO.pure(state)

  private def handleRequest(req: RpcRequest, state: HandlerState): IO[HandlerState] =
    req.method match
      case "initialize" =>
        handleInitialize(req.id).as(state)
      case "shutdown" =>
        handleShutdown(req.id).as(state.copy(shutdown = true))
      case "textDocument/hover" =>
        requireInitialized(req.id, state) {
          req.params match
            case Some(params) => handleHover(req.id, params).as(state)
            case None =>
              JsonRpc
                .writeError(
                  output,
                  req.id,
                  RpcError(RpcError.InvalidParams, "Missing params")
                )
                .as(state)
        }
      case "textDocument/definition" =>
        requireInitialized(req.id, state) {
          req.params match
            case Some(params) => handleDefinition(req.id, params).as(state)
            case None =>
              JsonRpc
                .writeError(
                  output,
                  req.id,
                  RpcError(RpcError.InvalidParams, "Missing params")
                )
                .as(state)
        }
      case "textDocument/references" =>
        requireInitialized(req.id, state) {
          req.params match
            case Some(params) => handleReferences(req.id, params).as(state)
            case None =>
              JsonRpc
                .writeError(
                  output,
                  req.id,
                  RpcError(RpcError.InvalidParams, "Missing params")
                )
                .as(state)
        }
      case "textDocument/semanticTokens/full" =>
        requireInitialized(req.id, state) {
          req.params match
            case Some(params) => handleSemanticTokens(req.id, params).as(state)
            case None =>
              JsonRpc
                .writeError(
                  output,
                  req.id,
                  RpcError(RpcError.InvalidParams, "Missing params")
                )
                .as(state)
        }
      case "workspace/executeCommand" =>
        requireInitialized(req.id, state) {
          req.params match
            case Some(params) =>
              handleExecuteCommand(req.id, params).map { shouldShutdown =>
                if shouldShutdown then state.copy(shutdown = true) else state
              }
            case None =>
              JsonRpc
                .writeError(
                  output,
                  req.id,
                  RpcError(RpcError.InvalidParams, "Missing params")
                )
                .as(state)
        }
      case "workspace/symbol" =>
        requireInitialized(req.id, state) {
          req.params match
            case Some(params) => handleWorkspaceSymbol(req.id, params).as(state)
            case None =>
              JsonRpc
                .writeError(
                  output,
                  req.id,
                  RpcError(RpcError.InvalidParams, "Missing params")
                )
                .as(state)
        }
      case method =>
        logger.warn(s"Method not found: $method") *>
          JsonRpc
            .writeError(
              output,
              req.id,
              RpcError(RpcError.MethodNotFound, s"Method not found: $method")
            )
            .as(state)

  private def handleNotification(
    notif: RpcNotification,
    state: HandlerState
  ): IO[HandlerState] =
    notif.method match
      case "initialized" =>
        logger.info("Client initialized") *> IO.pure(state.copy(initialized = true))
      case "exit" =>
        logger.info("Exit notification received") *> IO.pure(state.copy(shutdown = true))
      case "textDocument/didOpen" =>
        notif.params match
          case Some(params) => handleDidOpen(params).as(state)
          case None => IO.pure(state)
      case "textDocument/didChange" =>
        notif.params match
          case Some(params) => handleDidChange(params).as(state)
          case None => IO.pure(state)
      case "textDocument/didClose" =>
        notif.params match
          case Some(params) => handleDidClose(params).as(state)
          case None => IO.pure(state)
      case other =>
        logger.debug(s"Ignoring notification: $other").as(state)

  private def handleInitialize(id: ujson.Value): IO[Unit] =
    logger.info("Handling initialize") *> {
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
      sendResponse(id, result)
    }

  private def handleShutdown(id: ujson.Value): IO[Unit] =
    logger.info("Shutdown request received") *>
      sendResponse(id, ujson.Null)

  private def handleDidOpen(params: ujson.Value): IO[Unit] =
    val textDocument = params("textDocument")
    val uri          = textDocument("uri").str
    val text         = textDocument("text").str
    val version      = textDocument.obj.get("version").map(_.num.toInt).getOrElse(0)
    logger.info(s"didOpen: $uri (${text.length} chars)") *>
      documentManager.open(uri, text, version).flatMap {
        case Some(state) =>
          logger.debug(s"Publishing diagnostics for $uri") *>
            publishDiagnostics(uri, state)
        case None =>
          logger.warn(s"No state after opening $uri")
      }

  private def handleDidChange(params: ujson.Value): IO[Unit] =
    val textDocument   = params("textDocument")
    val uri            = textDocument("uri").str
    val version        = textDocument.obj.get("version").map(_.num.toInt).getOrElse(0)
    val contentChanges = params("contentChanges").arr
    contentChanges.lastOption match
      case Some(change) =>
        val text = change("text").str
        logger.debug(s"didChange: $uri v$version") *>
          documentManager.change(uri, text, version).flatMap {
            case Some(state) => publishDiagnostics(uri, state)
            case None => IO.unit
          }
      case None => IO.unit

  private def handleDidClose(params: ujson.Value): IO[Unit] =
    val uri = params("textDocument")("uri").str
    logger.info(s"didClose: $uri") *>
      clearDiagnostics(uri) *> documentManager.close(uri)

  private def handleHover(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val pos = TextDocumentPositionParams.fromJson(params)
    logger.debug(
      s"hover: ${pos.textDocument.uri} ${pos.position.line}:${pos.position.character}"
    ) *>
      documentManager.get(pos.textDocument.uri).flatMap {
        case None =>
          sendResponse(id, ujson.Null)
        case Some(docState) =>
          val line = pos.position.line + 1
          val col  = pos.position.character + 1
          AstLookup.findAt(docState.state.module, line, col) match
            case None =>
              sendResponse(id, ujson.Null)
            case Some(result) =>
              val typeStr = AstLookup.formatType(result.typeSpec)
              val content = result.name match
                case Some(n) => s"`${n}`: $typeStr"
                case None => typeStr
              val hover = Hover(
                contents = MarkupContent(MarkupContent.Markdown, content),
                range    = result.span.map(Range.fromSrcSpan)
              )
              sendResponse(id, Hover.toJson(hover))
      }

  private def handleDefinition(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val pos = TextDocumentPositionParams.fromJson(params)
    logger.debug(
      s"definition: ${pos.textDocument.uri} ${pos.position.line}:${pos.position.character}"
    ) *>
      documentManager.get(pos.textDocument.uri).flatMap {
        case None =>
          sendResponse(id, ujson.Null)
        case Some(docState) =>
          val line  = pos.position.line + 1
          val col   = pos.position.character + 1
          val spans = AstLookup.findDefinitionAt(docState.state.module, line, col)
          if spans.isEmpty then sendResponse(id, ujson.Null)
          else
            val locations =
              spans.map(span => Location(pos.textDocument.uri, Range.fromSrcSpan(span)))
            val json = ujson.Arr(locations.map(Location.toJson)*)
            sendResponse(id, json)
      }

  private def handleReferences(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val pos = TextDocumentPositionParams.fromJson(params)
    val includeDecl = params.obj
      .get("context")
      .flatMap(_.obj.get("includeDeclaration"))
      .exists(_.bool)
    logger.debug(
      s"references: ${pos.textDocument.uri} ${pos.position.line}:${pos.position.character}"
    ) *>
      documentManager.get(pos.textDocument.uri).flatMap {
        case None =>
          sendResponse(id, ujson.Arr())
        case Some(docState) =>
          val line = pos.position.line + 1
          val col  = pos.position.character + 1
          val spans =
            AstLookup.findReferencesAt(docState.state.module, line, col, includeDecl)
          val locations = spans.map(span => Location(pos.textDocument.uri, Range.fromSrcSpan(span)))
          val json      = ujson.Arr(locations.map(Location.toJson)*)
          sendResponse(id, json)
      }

  private def handleSemanticTokens(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val uri = params("textDocument")("uri").str
    logger.debug(s"semanticTokens: $uri") *>
      documentManager.get(uri).flatMap {
        case None =>
          sendResponse(id, ujson.Null)
        case Some(docState) =>
          val result = SemanticTokens.compute(docState.state.module)
          logger.debug(s"semanticTokens: ${result.data.length / 5} tokens") *>
            sendResponse(id, SemanticTokensResult.toJson(result))
      }

  private def handleWorkspaceSymbol(id: ujson.Value, params: ujson.Value): IO[Unit] =
    val query = params.obj.get("query").map(_.str).getOrElse("").toLowerCase
    logger.debug(s"workspace/symbol: query='$query'") *>
      documentManager.all.flatMap { docs =>
        val symbols = docs.toList.flatMap { case (uri, docState) =>
          AstLookup.collectSymbols(docState.state.module, uri).filter { sym =>
            query.isEmpty || sym.name.toLowerCase.contains(query)
          }
        }
        logger.debug(s"workspace/symbol: ${symbols.size} results") *>
          sendResponse(id, ujson.Arr(symbols.map(SymbolInformation.toJson)*))
      }

  /** Send a JSON-RPC response and log it. */
  private def sendResponse(id: ujson.Value, result: ujson.Value): IO[Unit] =
    logger.debug(s"--> response id=$id") *>
      JsonRpc.writeResponse(output, id, result)

  /** Publish diagnostics for a document. */
  def publishDiagnostics(uri: String, state: CompilerState): IO[Unit] =
    val diagnostics = Diagnostics.fromCompilerState(state)
    val params      = PublishDiagnosticsParams(uri, diagnostics)
    logger.debug(s"--> publishDiagnostics: $uri (${diagnostics.size} items)") *>
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

  private def handleExecuteCommand(id: ujson.Value, params: ujson.Value): IO[Boolean] =
    val command   = params("command").str
    val arguments = params.obj.get("arguments").map(_.arr.toList).getOrElse(Nil)
    logger.info(s"executeCommand: $command") *> {
      command match
        case LspCommands.Restart =>
          logger.info("Restart command received") *>
            JsonRpc.writeResponse(output, id, ujson.Obj("success" -> true)) *>
            IO.blocking(output.flush()) *>
            IO.sleep(100.millis) *>
            IO.pure(true)
        case LspCommands.CompileBin =>
          executeCompile(id, arguments, isLib = false).as(false)
        case LspCommands.CompileLib =>
          executeCompile(id, arguments, isLib = true).as(false)
        case LspCommands.Clean =>
          executeClean(id).as(false)
        case LspCommands.Ast =>
          executeAst(id, arguments).as(false)
        case LspCommands.Ir =>
          executeIr(id, arguments).as(false)
        case _ =>
          JsonRpc
            .writeError(
              output,
              id,
              RpcError(RpcError.InvalidParams, s"Unknown command: $command")
            )
            .as(false)
    }

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
        val filePath = Path.of(uriToPath(uri))
        val mode     = if isLib then CompilationMode.Library else CompilationMode.Exe
        val config   = CompilerConfig.default.copy(mode = mode)
        CompilerApi.compileNativeQuiet(filePath, config).flatMap {
          case Left(errorMsg) =>
            JsonRpc.writeResponse(
              output,
              id,
              ujson.Obj("success" -> false, "message" -> errorMsg)
            )
          case Right(_) =>
            JsonRpc.writeResponse(
              output,
              id,
              ujson.Obj("success" -> true)
            )
        }

  private def executeClean(id: ujson.Value): IO[Unit] =
    CompilerApi.cleanQuiet("build").flatMap {
      case Left(errorMsg) =>
        JsonRpc.writeResponse(output, id, ujson.Obj("success" -> false, "message" -> errorMsg))
      case Right(msg) =>
        JsonRpc.writeResponse(output, id, ujson.Obj("success" -> true, "message" -> msg))
    }

  private def executeAst(id: ujson.Value, arguments: List[ujson.Value]): IO[Unit] =
    arguments.headOption.flatMap(_.strOpt) match
      case None =>
        JsonRpc.writeError(
          output,
          id,
          RpcError(RpcError.InvalidParams, "Missing file URI argument")
        )
      case Some(uri) =>
        val filePath = Path.of(uriToPath(uri))
        CompilerApi.processAstQuiet(filePath, CompilerConfig.default).flatMap {
          case Left(errorMsg) =>
            JsonRpc.writeResponse(output, id, ujson.Obj("success" -> false, "message" -> errorMsg))
          case Right(astPath) =>
            JsonRpc.writeResponse(
              output,
              id,
              ujson.Obj("success" -> true, "message" -> s"AST written to $astPath")
            )
        }

  private def executeIr(id: ujson.Value, arguments: List[ujson.Value]): IO[Unit] =
    arguments.headOption.flatMap(_.strOpt) match
      case None =>
        JsonRpc.writeError(
          output,
          id,
          RpcError(RpcError.InvalidParams, "Missing file URI argument")
        )
      case Some(uri) =>
        val filePath = Path.of(uriToPath(uri))
        CompilerApi.processIrQuiet(filePath, CompilerConfig.default).flatMap {
          case Left(errorMsg) =>
            JsonRpc.writeResponse(output, id, ujson.Obj("success" -> false, "message" -> errorMsg))
          case Right(irPath) =>
            JsonRpc.writeResponse(
              output,
              id,
              ujson.Obj("success" -> true, "message" -> s"IR written to $irPath")
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
  val Clean:      String       = "mml.server.clean"
  val Ast:        String       = "mml.server.ast"
  val Ir:         String       = "mml.server.ir"
  val all:        List[String] = List(Restart, CompileBin, CompileLib, Clean, Ast, Ir)

object LspHandler:

  def create(
    documentManager: DocumentManager,
    input:           InputStream,
    output:          PrintStream,
    logger:          Logger[IO]
  ): LspHandler =
    new LspHandler(documentManager, input, output, logger)

  /** Convert a file path to a file:// URI. */
  def pathToUri(path: java.nio.file.Path): String =
    s"file://${path.toAbsolutePath.normalize()}"

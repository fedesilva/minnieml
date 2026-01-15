package mml.mmlclib.lsp

import mml.mmlclib.ast.SrcSpan

/** LSP Position - 0-based line and character offsets. */
case class Position(line: Int, character: Int)

object Position:
  def toJson(p: Position): ujson.Value =
    ujson.Obj("line" -> p.line, "character" -> p.character)

  def fromJson(json: ujson.Value): Position =
    Position(json("line").num.toInt, json("character").num.toInt)

/** LSP Range - start and end positions. */
case class Range(start: Position, end: Position)

object Range:
  def toJson(r: Range): ujson.Value =
    ujson.Obj("start" -> Position.toJson(r.start), "end" -> Position.toJson(r.end))

  def fromSrcSpan(span: SrcSpan): Range =
    Range(
      Position(span.start.line - 1, span.start.col - 1),
      Position(span.end.line - 1, span.end.col - 1)
    )

/** LSP Location - a range within a document. */
case class Location(uri: String, range: Range)

object Location:
  def toJson(l: Location): ujson.Value =
    ujson.Obj("uri" -> l.uri, "range" -> Range.toJson(l.range))

/** LSP TextDocumentIdentifier */
case class TextDocumentIdentifier(uri: String)

object TextDocumentIdentifier:
  def fromJson(json: ujson.Value): TextDocumentIdentifier =
    TextDocumentIdentifier(json("uri").str)

/** LSP TextDocumentPositionParams - used for hover, definition, etc. */
case class TextDocumentPositionParams(textDocument: TextDocumentIdentifier, position: Position)

object TextDocumentPositionParams:
  def fromJson(json: ujson.Value): TextDocumentPositionParams =
    TextDocumentPositionParams(
      TextDocumentIdentifier.fromJson(json("textDocument")),
      Position.fromJson(json("position"))
    )

/** LSP DiagnosticSeverity */
object DiagnosticSeverity:
  val Error:       Int = 1
  val Warning:     Int = 2
  val Information: Int = 3
  val Hint:        Int = 4

/** LSP Diagnostic */
case class Diagnostic(
  range:    Range,
  severity: Int,
  message:  String,
  source:   Option[String] = Some("mmlc")
)

object Diagnostic:
  def toJson(d: Diagnostic): ujson.Value =
    val obj = ujson.Obj(
      "range" -> Range.toJson(d.range),
      "severity" -> d.severity,
      "message" -> d.message
    )
    d.source.foreach(s => obj("source") = s)
    obj

/** LSP PublishDiagnosticsParams */
case class PublishDiagnosticsParams(uri: String, diagnostics: List[Diagnostic])

object PublishDiagnosticsParams:
  def toJson(p: PublishDiagnosticsParams): ujson.Value =
    ujson.Obj(
      "uri" -> p.uri,
      "diagnostics" -> ujson.Arr(p.diagnostics.map(Diagnostic.toJson)*)
    )

/** LSP MarkupContent for hover */
case class MarkupContent(kind: String, value: String)

object MarkupContent:
  val Markdown:  String = "markdown"
  val PlainText: String = "plaintext"

  def toJson(m: MarkupContent): ujson.Value =
    ujson.Obj("kind" -> m.kind, "value" -> m.value)

/** LSP Hover response */
case class Hover(contents: MarkupContent, range: Option[Range] = None)

object Hover:
  def toJson(h: Hover): ujson.Value =
    val obj = ujson.Obj("contents" -> MarkupContent.toJson(h.contents))
    h.range.foreach(r => obj("range") = Range.toJson(r))
    obj

/** LSP SemanticTokensLegend - defines the token types and modifiers supported. */
case class SemanticTokensLegend(
  tokenTypes:     List[String],
  tokenModifiers: List[String]
)

object SemanticTokensLegend:
  def toJson(l: SemanticTokensLegend): ujson.Value =
    ujson.Obj(
      "tokenTypes" -> ujson.Arr(l.tokenTypes.map(ujson.Str(_))*),
      "tokenModifiers" -> ujson.Arr(l.tokenModifiers.map(ujson.Str(_))*)
    )

/** LSP SemanticTokens response. */
case class SemanticTokensResult(data: Array[Int])

object SemanticTokensResult:
  def toJson(r: SemanticTokensResult): ujson.Value =
    ujson.Obj("data" -> ujson.Arr(r.data.map(ujson.Num(_))*))

/** LSP ServerCapabilities */
object ServerCapabilities:
  def toJson(
    hoverProvider:           Boolean                      = true,
    definitionProvider:      Boolean                      = false,
    referencesProvider:      Boolean                      = false,
    workspaceSymbolProvider: Boolean                      = false,
    textDocumentSync:        Int                          = 1,
    commands:                List[String]                 = Nil,
    semanticTokensProvider:  Option[SemanticTokensLegend] = None
  ): ujson.Value =
    val obj = ujson.Obj(
      "hoverProvider" -> hoverProvider,
      "definitionProvider" -> definitionProvider,
      "referencesProvider" -> referencesProvider,
      "workspaceSymbolProvider" -> workspaceSymbolProvider,
      "textDocumentSync" -> textDocumentSync
    )
    if commands.nonEmpty then
      obj("executeCommandProvider") = ujson.Obj(
        "commands" -> ujson.Arr(commands.map(ujson.Str(_))*)
      )
    semanticTokensProvider.foreach { legend =>
      obj("semanticTokensProvider") = ujson.Obj(
        "legend" -> SemanticTokensLegend.toJson(legend),
        "full" -> true
      )
    }
    obj

/** LSP InitializeResult */
object InitializeResult:
  def toJson(capabilities: ujson.Value): ujson.Value =
    ujson.Obj("capabilities" -> capabilities)

/** LSP SymbolKind - subset relevant to MML. */
object SymbolKind:
  val Field:         Int = 8
  val Function:      Int = 12
  val Variable:      Int = 13
  val Struct:        Int = 23
  val Operator:      Int = 25
  val TypeParameter: Int = 26

/** LSP SymbolInformation - information about a symbol. */
case class SymbolInformation(
  name:     String,
  kind:     Int,
  location: Location
)

object SymbolInformation:
  def toJson(s: SymbolInformation): ujson.Value =
    ujson.Obj(
      "name" -> s.name,
      "kind" -> s.kind,
      "location" -> Location.toJson(s.location)
    )

package mml.mmlclib.compiler

import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.{Module, SrcPoint, SrcSpan, Visibility}
import mml.mmlclib.parser.{Parser, SourceInfo}
import mml.mmlclib.util.pipe.*

object IngestStage:

  private val dummySpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  def fromSource(
    source:     String,
    name:       String,
    config:     CompilerConfig = CompilerConfig.default,
    sourcePath: Option[String] = None
  ): CompilerState =
    val sanitizedName = ParserApi.sanitizeModuleName(name)
    val emptyModule = Module(
      span       = dummySpan,
      name       = sanitizedName,
      visibility = Visibility.Public,
      members    = List.empty,
      sourcePath = sourcePath
    )
    val emptyState = CompilerState.empty(emptyModule, SourceInfo(source), config)

    emptyState
      |> CompilerState.timePhase("ingest", "parse")(
        parseModule(source, sanitizedName, sourcePath)
      )
      |> CompilerState.timePhase("ingest", "lift-parse-errors")(ParsingErrorChecker.checkModule)

  private def parseModule(
    source:     String,
    name:       String,
    sourcePath: Option[String]
  )(state: CompilerState): CompilerState =
    Parser.parseModule(source, name, sourcePath) match
      case Right(module) => state.withModule(module)
      case Left(error) => state.addError(error)

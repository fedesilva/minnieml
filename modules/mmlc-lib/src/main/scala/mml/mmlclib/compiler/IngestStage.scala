package mml.mmlclib.compiler

import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.{Module, SourceOrigin, Visibility}
import mml.mmlclib.parser.{Parser, SourceInfo}
import mml.mmlclib.util.pipe.*

object IngestStage:

  def fromSource(
    source:     String,
    name:       String,
    config:     CompilerConfig = CompilerConfig.default,
    sourcePath: Option[String] = None
  ): CompilerState =
    val sanitizedName = ParserApi.sanitizeModuleName(name)
    val emptyModule = Module(
      source     = SourceOrigin.Synth,
      name       = sanitizedName,
      visibility = Visibility.Public,
      members    = List.empty,
      sourcePath = sourcePath
    )
    val emptyState = CompilerState.empty(emptyModule, SourceInfo(source), config)

    emptyState
      |> CompilerState.timePhase("ingest", "parse-total")(
        parseModule(source, sanitizedName, sourcePath)
      )
      |> CompilerState.timePhase("ingest", "lift-parse-errors")(ParsingErrorChecker.checkModule)

  private def parseModule(
    source:     String,
    name:       String,
    sourcePath: Option[String]
  )(state: CompilerState): CompilerState =
    val (_, result, collector) = Parser.parseModuleInstrumented(source, name, sourcePath)
    val withCounters           = state.addCounters(collector.toCounters("ingest"))
    result match
      case Right(module) => withCounters.withModule(module)
      case Left(error) => withCounters.addError(error)

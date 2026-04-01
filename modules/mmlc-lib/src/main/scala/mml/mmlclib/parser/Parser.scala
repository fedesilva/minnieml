package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

enum ParserError extends CompilationError:
  case Failure(msg: String)
  case Unknown(msg: String)

  def message: String = this match
    case Failure(msg) => msg
    case Unknown(msg) => msg

type ParserResult = Either[ParserError, Module]

/** Entry points for parsing full MML source files into a top-level [[Module]].
  *
  * Example:
  * {{{
  * let answer = 42;
  *
  * fn inc(x: Int): Int =
  *   x + 1;
  * ;
  * }}}
  */
object Parser:

  /** Parses one source file while also returning the [[SourceInfo]] index used to build spans.
    *
    * Example:
    * {{{
    * Parser.parseModuleWithInfo(
    *   \"let greeting = \\\"hola\\\";\",
    *   name = \"Sample\"
    * )
    * }}}
    */
  def parseModuleWithInfo(
    source:     String,
    name:       String,
    sourcePath: Option[String] = None
  ): (SourceInfo, ParserResult) =
    val info = SourceInfo(source)
    val result =
      parse(source, p => topLevelModuleP(name, info, sourcePath)(using p)) match
        case Parsed.Success(result, _) =>
          result.asRight
        case f: Parsed.Failure =>
          ParserError.Failure(f.trace().longMsg).asLeft
    (info, result)

  /** Parses one source file into a synthetic top-level module.
    *
    * Example:
    * {{{
    * Parser.parseModule(\"struct Person { name: String };\", name = \"Sample\")
    * }}}
    */
  def parseModule(
    source:     String,
    name:       String,
    sourcePath: Option[String] = None
  ): ParserResult =
    parseModuleWithInfo(source, name, sourcePath)._2

  /** Parses one source file and collects FastParse instrumentation counters for that run.
    *
    * Example:
    * {{{
    * Parser.parseModuleInstrumented(
    *   \"fn id(x: Int): Int = x; ;\",
    *   name = \"Metrics\"
    * )
    * }}}
    */
  def parseModuleInstrumented(
    source:     String,
    name:       String,
    sourcePath: Option[String] = None
  ): (SourceInfo, ParserResult, ParserMetricsCollector) =
    val info      = SourceInfo(source)
    val collector = new ParserMetricsCollector(source.length)

    val result = parse(
      source,
      p => topLevelModuleP(name, info, sourcePath)(using p),
      instrument = collector
    ) match
      case Parsed.Success(result, _) => result.asRight
      case f: Parsed.Failure => ParserError.Failure(f.trace().longMsg).asLeft

    (info, result, collector)

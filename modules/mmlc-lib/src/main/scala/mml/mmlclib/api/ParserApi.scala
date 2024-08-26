package mml.mmlclib.api

import java.nio.file.Path
import mml.mmlclib.parser.antlr.MinnieMLParser._
import mml.mmlclib.parser.antlr._
import mml.mmlclib.parser.SyntaxErrorAccumulator
import org.antlr.v4.runtime._
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.tree.ErrorNode
import cats.effect.IO

case class ParseContext[T <: ParserRuleContext](
  tree:   T,
  errors: List[ParseError]
)

sealed trait ParseError

object ParseError:

  case class SyntaxError(
    offendingSymbol:    String,
    line:               Int,
    charPositionInLine: Int,
    msg:                String,
    errorO:             Option[Throwable]
  ) extends ParseError

  case class TreeError(
    node: ErrorNode
  ) extends ParseError

object ParserApi:

  type ParseTree <: ParserRuleContext

  private def parseModuleTokens(tokens: CommonTokenStream): IO[ParseContext[ModuleContext]] =
    makeParser(tokens).map { case (parser, errorAccumulator) =>
      val module = parser.module()
      ParseContext(module, errorAccumulator.errorList)
    }

  def parseModuleString(source: String): IO[ParseContext[ModuleContext]] =
    LexerApi.tokenizeString(source).flatMap(parseModuleTokens)

  def parseModuleFile(path: Path): IO[ParseContext[ModuleContext]] =
    LexerApi.tokenizeFile(path).flatMap(parseModuleTokens)

  private def parseScriptTokens(tokens: CommonTokenStream): IO[ParseContext[ScriptContext]] =
    makeParser(tokens).map { case (parser, errorAccumulator) =>
      val script = parser.script()
      ParseContext(script, errorAccumulator.errorList)
    }

  def parseScriptString(source: String): IO[ParseContext[ScriptContext]] =
    LexerApi.tokenizeString(source).flatMap(parseScriptTokens)

  def parseScriptFile(path: Path): IO[ParseContext[ScriptContext]] =
    LexerApi.tokenizeFile(path).flatMap(parseScriptTokens)

  private def makeParser(tokens: CommonTokenStream): IO[(MinnieMLParser, SyntaxErrorAccumulator)] =
    IO {
      val parser = new MinnieMLParser(tokens)
      parser.removeErrorListeners()
      parser.removeParseListeners()

      val syntaxErrorAccumulator = new SyntaxErrorAccumulator
      parser.addErrorListener(syntaxErrorAccumulator)

      parser.getInterpreter
        .setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION)

      (parser, syntaxErrorAccumulator)
    }

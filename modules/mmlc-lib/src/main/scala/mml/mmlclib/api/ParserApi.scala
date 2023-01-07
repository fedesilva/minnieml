package mml.mmlclib.api

import java.nio.file.Path
import mml.mmlclib.parser.antlr.MinnieMLParser._
import mml.mmlclib.parser.antlr._
import mml.mmlclib.util._
import mml.mmlclib.parser.SyntaxErrorAccumulator
import org.antlr.v4.runtime._
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.tree.ErrorNode

import mml.mmlclib.api.|>
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

  def apply(): ParserApi = new ParserApi()


/** Created by f on 4/1/16.
  */
class ParserApi():

  def parseModuleTokens(tokens: CommonTokenStream): ParseContext[ModuleContext] =

    val (parser, errorAccumulator) = makeParser(tokens)

    val module = parser.module()
    val errors = errorAccumulator.errorList

    ParseContext(
      module,
      errors
    )

  def parseModuleString(source: String): ParseContext[ModuleContext] =
    LexerApi.tokenizeString(source) |> parseModuleTokens

  def parseModuleFile(path: Path): ParseContext[ModuleContext] =
    LexerApi.tokenizeFile(path) |> parseModuleTokens


  def parseScriptTokens(tokens: CommonTokenStream): ParseContext[ScriptContext] =

    val (parser, errorAccumulator) = makeParser(tokens)
    
    val module = parser.script()
    // The syntax error accumulator can only be queried for errors once.
    val errors = errorAccumulator.errorList

    ParseContext(
      module,
      errors
    )

  def parseScriptString(source: String): ParseContext[ScriptContext] =
    LexerApi.tokenizeString(source) |> parseScriptTokens

  def parseScriptFile(path: Path): ParseContext[ScriptContext] =
    LexerApi.tokenizeFile(path) |> parseScriptTokens


  def makeParser(tokens: CommonTokenStream): (MinnieMLParser, SyntaxErrorAccumulator) =

    val parser = new MinnieMLParser(tokens)

    parser.removeErrorListeners()
    parser.removeParseListeners()

    // The syntax error accumulator can only be queried for errors once.
    val syntaxErrorAccumulator = new SyntaxErrorAccumulator
    parser.addErrorListener(syntaxErrorAccumulator)

    parser
      .getInterpreter
      .setPredictionMode(
        PredictionMode.LL_EXACT_AMBIG_DETECTION
      )

    (parser, syntaxErrorAccumulator)



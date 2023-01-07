package mml.mmlclib.parser

import mml.mmlclib.api.ParseError.SyntaxError
import org.antlr.v4.runtime._

class SyntaxErrorAccumulator extends BaseErrorListener:

  private[this] val errors = collection.mutable.ListBuffer[SyntaxError]()

  override def syntaxError(
    recognizer:         Recognizer[_, _],
    offendingSymbol:    Any,
    line:               Int,
    charPositionInLine: Int,
    msg:                String,
    e:                  RecognitionException
  ): Unit =

    val re =
      SyntaxError(
        offendingSymbol.toString,
        line,
        charPositionInLine,
        msg,
        Option(e)
      )

    errors.append(re)


  lazy val errorList: List[SyntaxError] = errors.toList


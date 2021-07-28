package mml.mmlclib.api.parser.antlr.listeners

import org.antlr.v4.runtime._

class UnderlineErrorListener extends BaseErrorListener {

  override def syntaxError(
    recognizer:         Recognizer[_, _],
    offendingSymbol:    Any,
    line:               Int,
    charPositionInLine: Int,
    msg:                String,
    e:                  RecognitionException
  ): Unit = {
    
    val eO = Option(e)
    println("line " + line + ":" + charPositionInLine + " " + msg + " ex: " + eO)
    underlineError(recognizer, offendingSymbol.asInstanceOf[Token], line, charPositionInLine)
    
  }
  
  protected def underlineError(
    recognizer:     Recognizer[_, _],
    offendingToken: Token,
    line:           Int,
    charPositionInLine: Int
  ): Unit = {

    val tokens    = recognizer.getInputStream.asInstanceOf[CommonTokenStream]
    val input     = tokens.getTokenSource.getInputStream.toString
    val lines     = input.split("\n")
    val errorLine = lines(line - 1)
    
    println(errorLine)
    
    (0 until charPositionInLine).foreach { _ =>
      print(" ")
    }
    
    val start = offendingToken.getStartIndex
    val stop  = offendingToken.getStopIndex
    
    if (start >= 0 && stop >= 0) {
      (start until stop) foreach { _ =>
        print("^")
      }
    }

    println()

  }

}

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
object LexerApi:

  def tokenize(input: CharStream): CommonTokenStream =
    new CommonTokenStream(new MinnieMLLexer(input))

  def tokenizeFile(path: Path): CommonTokenStream =
    CharStreams.fromPath(path) |> tokenize

  def tokenizeString(source: String): CommonTokenStream =
    CharStreams.fromString(source) |> tokenize
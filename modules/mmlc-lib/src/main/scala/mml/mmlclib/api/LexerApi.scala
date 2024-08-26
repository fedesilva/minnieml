package mml.mmlclib.api

import cats.effect.IO
import mml.mmlclib.parser.SyntaxErrorAccumulator
import mml.mmlclib.parser.antlr.*
import mml.mmlclib.parser.antlr.MinnieMLParser.*
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.tree.ErrorNode

import java.nio.file.Path

object LexerApi:

  def tokenize(input: CharStream): IO[CommonTokenStream] =
    IO.pure(new CommonTokenStream(new MinnieMLLexer(input)))

  def tokenizeFile(path: Path): IO[CommonTokenStream] =
    IO.blocking(CharStreams.fromPath(path)).flatMap(tokenize)

  def tokenizeString(source: String): IO[CommonTokenStream] =
    IO.pure(CharStreams.fromString(source)).flatMap(tokenize)

    
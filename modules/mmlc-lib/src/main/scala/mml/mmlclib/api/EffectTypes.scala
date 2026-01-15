package mml.mmlclib.api

import cats.data.EitherT
import cats.effect.IO
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.SemanticError

// Define effect type aliases for each layer of the compiler
type ParserEffect[A]   = EitherT[IO, ParserError, A]
type CompilerEffect[A] = EitherT[IO, CompilerError, A]

// Utility functions for lifting between effect types
object EffectOps:
  // Parser error lifting
  def liftParserError[A](error: ParserError): CompilerEffect[A] =
    EitherT.leftT[IO, A](CompilerError.ParserErrors(List(error)))

  // Semantic error lifting
  def liftSemanticError[A](errors: List[SemanticError]): CompilerEffect[A] =
    EitherT.leftT[IO, A](CompilerError.SemanticErrors(errors))

  // General error lifting
  def liftThrowable[A](error: Throwable): CompilerEffect[A] =
    EitherT.leftT[IO, A](CompilerError.Unknown(error.getMessage))

  // Conversion helpers using specific names to avoid erasure conflicts
  extension [A](effect: ParserEffect[A])
    def toParserEither: IO[Either[ParserError, A]] = effect.value

  extension [A](effect: CompilerEffect[A])
    def toCompilerEither: IO[Either[CompilerError, A]] = effect.value

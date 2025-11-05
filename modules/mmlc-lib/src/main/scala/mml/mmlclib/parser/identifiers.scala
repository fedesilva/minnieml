package mml.mmlclib.parser

import fastparse.*

private[parser] def bindingIdP[$: P]: P[String] =
  import fastparse.NoWhitespace.*
  P(!keywords ~ CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

private[parser] def operatorIdP[$: P]: P[String] =
  val opChars    = "=!#$%^&*+<>?/\\|~-"
  val symbolicOp = P(CharsWhile(c => opChars.indexOf(c) >= 0, min = 1).!)
  P(symbolicOp | bindingIdP)

private[parser] def typeIdP[$: P]: P[String] =
  import fastparse.NoWhitespace.*
  P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

private[parser] def bindingIdOrError[$: P]: P[Either[String, String]] =
  import fastparse.NoWhitespace.*
  P((!keywords ~ CharsWhileIn("a-zA-Z0-9_", 1)).!).map { captured =>
    if captured.headOption.exists(_.isLower) && captured.forall(c => c.isLetterOrDigit || c == '_')
    then Right(captured)
    else Left(captured)
  }

private[parser] def operatorIdOrError[$: P]: P[Either[String, String]] =
  val opChars    = "=!#$%^&*+<>?/\\|~-"
  val symbolicOp = P(CharsWhile(c => opChars.indexOf(c) >= 0, min = 1).!)

  P(symbolicOp | CharsWhileIn("a-zA-Z0-9_", 1).!).map { captured =>
    if captured.forall(c => opChars.indexOf(c) >= 0) then Right(captured)
    else if captured.headOption.exists(_.isLower) && captured.forall(c =>
        c.isLetterOrDigit || c == '_'
      )
    then Right(captured)
    else Left(captured)
  }

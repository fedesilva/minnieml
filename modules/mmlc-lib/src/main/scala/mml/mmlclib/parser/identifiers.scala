package mml.mmlclib.parser

import fastparse.*

/** Parses a lowercase binding identifier such as `value`, `map2`, or `acc_1`. */
private[parser] def bindingIdP[$: P]: P[String] =
  import fastparse.NoWhitespace.*
  P(!keywords ~ CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

/** Parses an operator identifier.
  *
  * Examples:
  * {{{
  * +
  * ::
  * map
  * }}}
  */
private[parser] def operatorIdP[$: P]: P[String] =
  import fastparse.NoWhitespace.*
  val opChars    = "=!#$%^&*+<>?/\\|-."
  val symbolicOp = P(!arrowKw ~ CharsWhile(c => opChars.indexOf(c) >= 0, min = 1).!)
  P(symbolicOp | bindingIdP)

/** Parses an uppercase type identifier such as `Int`, `Maybe`, or `Person`. */
private[parser] def typeIdP[$: P]: P[String] =
  import fastparse.NoWhitespace.*
  P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

/** Parses a would-be binding identifier and preserves the invalid spelling for better errors. */
private[parser] def bindingIdOrError[$: P]: P[Either[String, String]] =
  import fastparse.NoWhitespace.*
  P((!keywords ~ CharsWhileIn("a-zA-Z0-9_", 1)).!).map { captured =>
    if captured.headOption.exists(_.isLower) && captured.forall(c => c.isLetterOrDigit || c == '_')
    then Right(captured)
    else Left(captured)
  }

/** Parses a would-be operator identifier and preserves invalid spellings for diagnostics. */
private[parser] def operatorIdOrError[$: P]: P[Either[String, String]] =
  import fastparse.NoWhitespace.*
  val opChars    = "=!#$%^&*+<>?/\\|-."
  val symbolicOp = P(!arrowKw ~ CharsWhile(c => opChars.indexOf(c) >= 0, min = 1).!)

  P(symbolicOp | CharsWhileIn("a-zA-Z0-9_", 1).!).map { captured =>
    if captured.forall(c => opChars.indexOf(c) >= 0) then Right(captured)
    else if captured.headOption.exists(_.isLower) && captured.forall(c =>
        c.isLetterOrDigit || c == '_'
      )
    then Right(captured)
    else Left(captured)
  }

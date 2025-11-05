package mml.mmlclib.parser

import fastparse.*

import MmlWhitespace.*

private[parser] def letKw[$: P]:         P[Unit] = P("let")
private[parser] def fnKw[$: P]:          P[Unit] = P("fn")
private[parser] def opKw[$: P]:          P[Unit] = P("op")
private[parser] def defAsKw[$: P]:       P[Unit] = P("=")
private[parser] def endKw[$: P]:         P[Unit] = P(";")
private[parser] def placeholderKw[$: P]: P[Unit] = P("_")
private[parser] def holeKw[$: P]:        P[Unit] = P("???")
private[parser] def typeKw[$: P]:        P[Unit] = P("type")

private[parser] def ifKw[$: P]:   P[Unit] = P("if")
private[parser] def elseKw[$: P]: P[Unit] = P("else")
private[parser] def thenKw[$: P]: P[Unit] = P("then")

private[parser] def moduleEndKw[$: P]: P[Unit] =
  P(";".? ~ CharsWhile(c => c.isWhitespace, 0) ~ End)

private[parser] def moduleKw[$: P]: P[Unit] = P("module")

private[parser] def nativeKw[$: P]: P[Unit] = P("@native")

private[parser] def keywords[$: P]: P[Unit] =
  P(
    moduleKw |
      endKw |
      defAsKw |
      placeholderKw |
      letKw |
      holeKw |
      ifKw |
      elseKw |
      thenKw |
      typeKw |
      nativeKw |
      fnKw
  )

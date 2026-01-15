package mml.mmlclib.parser

import fastparse.*
import fastparse.NoWhitespace.*

// Word boundary: keyword must not be followed by identifier characters
private def wordBoundary[$: P]: P[Unit] = P(!CharIn("a-zA-Z0-9_"))

private[parser] def letKw[$: P]:         P[Unit] = P("let" ~ wordBoundary)
private[parser] def fnKw[$: P]:          P[Unit] = P("fn" ~ wordBoundary)
private[parser] def opKw[$: P]:          P[Unit] = P("op" ~ wordBoundary)
private[parser] def defAsKw[$: P]:       P[Unit] = P("=")
private[parser] def semiKw[$: P]:        P[Unit] = P(";")
private[parser] def endKw[$: P]:         P[Unit] = P("end" ~ wordBoundary)
private[parser] def placeholderKw[$: P]: P[Unit] = P("_" ~ wordBoundary)
private[parser] def holeKw[$: P]:        P[Unit] = P("???" ~ wordBoundary)
private[parser] def typeKw[$: P]:        P[Unit] = P("type" ~ wordBoundary)
private[parser] def moveKw[$: P]:        P[Unit] = P("~" ~ wordBoundary)
private[parser] def structKw[$: P]:      P[Unit] = P("struct" ~ wordBoundary)

private[parser] def ifKw[$: P]:   P[Unit] = P("if" ~ wordBoundary)
private[parser] def elifKw[$: P]: P[Unit] = P("elif" ~ wordBoundary)
private[parser] def elseKw[$: P]: P[Unit] = P("else" ~ wordBoundary)
private[parser] def thenKw[$: P]: P[Unit] = P("then" ~ wordBoundary)

private[parser] def moduleKw[$: P]: P[Unit] = P("module" ~ wordBoundary)

private[parser] def nativeKw[$: P]: P[Unit] = P("@native" ~ wordBoundary)

/** All the keywords in a list, so we can check against it to prevent usage in say ids. */
private[parser] def keywords[$: P]: P[Unit] =
  P(
    moduleKw |
      semiKw |
      endKw |
      defAsKw |
      placeholderKw |
      letKw |
      opKw |
      holeKw |
      ifKw |
      elifKw |
      elseKw |
      thenKw |
      typeKw |
      nativeKw |
      fnKw
  )

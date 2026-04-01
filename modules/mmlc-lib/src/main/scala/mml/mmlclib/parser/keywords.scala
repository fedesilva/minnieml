package mml.mmlclib.parser

import fastparse.*
import fastparse.NoWhitespace.*

/** Matches the end of a keyword so identifiers like `letter` do not consume `let`. */
private def wordBoundary[$: P]: P[Unit] = P(!CharIn("a-zA-Z0-9_"))

/** Parses the `let` keyword used by both top-level and local bindings.
  *
  * Example:
  * {{{
  * let answer = 42;
  * }}}
  */
private[parser] def letKw[$: P]: P[Unit] = P("let" ~ wordBoundary)

/** Parses the `fn` keyword used by top-level and local function syntax. */
private[parser] def fnKw[$: P]: P[Unit] = P("fn" ~ wordBoundary)

/** Parses the `op` keyword used for operator declarations. */
private[parser] def opKw[$: P]: P[Unit] = P("op" ~ wordBoundary)

/** Parses the `=` token that introduces a definition body. */
private[parser] def defAsKw[$: P]: P[Unit] = P("=")

/** Parses the `;` terminator used by declarations and expression frames. */
private[parser] def semiKw[$: P]: P[Unit] = P(";")

/** Parses the `_` placeholder term. */
private[parser] def placeholderKw[$: P]: P[Unit] = P("_" ~ wordBoundary)

/** Parses the `???` typed-hole token. */
private[parser] def holeKw[$: P]: P[Unit] = P("???" ~ wordBoundary)

/** Parses the `type` keyword used by aliases and native type declarations. */
private[parser] def typeKw[$: P]: P[Unit] = P("type" ~ wordBoundary)

/** Parses the `~` marker used by move lambdas and consuming parameters. */
private[parser] def moveKw[$: P]: P[Unit] = P("~" ~ wordBoundary)

/** Parses the `->` token used by lambda syntax and function types. */
private[parser] def arrowKw[$: P]: P[Unit] = P("->")

/** Parses the `struct` keyword used by nominal record declarations. */
private[parser] def structKw[$: P]: P[Unit] = P("struct" ~ wordBoundary)

/** Parses the `if` keyword. */
private[parser] def ifKw[$: P]: P[Unit] = P("if" ~ wordBoundary)

/** Parses the `elif` keyword. */
private[parser] def elifKw[$: P]: P[Unit] = P("elif" ~ wordBoundary)

/** Parses the `else` keyword. */
private[parser] def elseKw[$: P]: P[Unit] = P("else" ~ wordBoundary)

/** Parses the `then` keyword. */
private[parser] def thenKw[$: P]: P[Unit] = P("then" ~ wordBoundary)

/** Reserved only for future surface syntax.
  *
  * Source files do not currently use a `module` keyword.
  */
private[parser] def moduleKw[$: P]: P[Unit] = P("module" ~ wordBoundary)

/** Parses the `inline` modifier on functions and operators. */
private[parser] def inlineKw[$: P]: P[Unit] = P("inline" ~ wordBoundary)

/** Parses the `@native` attribute introducer for native values and native types. */
private[parser] def nativeKw[$: P]: P[Unit] = P("@native" ~ wordBoundary)

/** Parses the `pub` visibility modifier. */
private[parser] def pubKw[$: P]: P[Unit] = P("pub" ~ wordBoundary)

/** Parses the `prot` visibility modifier. */
private[parser] def protKw[$: P]: P[Unit] = P("prot" ~ wordBoundary)

/** Parses the `priv` visibility modifier. */
private[parser] def privKw[$: P]: P[Unit] = P("priv" ~ wordBoundary)

/** Parses any reserved word or punctuation token that must be excluded from identifiers. */
private[parser] def keywords[$: P]: P[Unit] =
  P(
    moduleKw |
      semiKw |
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
      inlineKw |
      nativeKw |
      fnKw |
      arrowKw |
      structKw |
      pubKw |
      protKw |
      privKw
  )

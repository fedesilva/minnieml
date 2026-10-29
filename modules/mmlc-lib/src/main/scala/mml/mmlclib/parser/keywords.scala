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

/** Parses a protocol declaration. */
private[parser] def protocolKw[$: P]: P[Unit] = P("protocol" ~ wordBoundary)

/** Recognizes deferred member constraints so the parser can diagnose them explicitly. */
private[parser] def whereKw[$: P]: P[Unit] = P("where" ~ wordBoundary)

/** Parses a concrete instance declaration. */
private[parser] def instanceKw[$: P]: P[Unit] = P("instance" ~ wordBoundary)

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

/** Parses the `@metal:repr` introducer for representation types. */
private[parser] def reprKw[$: P]: P[Unit] = P("@metal:repr" ~ wordBoundary)

/** Parses the `@metal:ffi` introducer for foreign function bindings. */
private[parser] def ffiKw[$: P]: P[Unit] = P("@metal:ffi" ~ wordBoundary)

/** Parses the `@metal:tpl` introducer for inline LLVM templates. */
private[parser] def tplKw[$: P]: P[Unit] = P("@metal:tpl" ~ wordBoundary)

/** Parses the `pub` visibility modifier. */
private[parser] def pubKw[$: P]: P[Unit] = P("pub" ~ wordBoundary)

/** Parses the `prot` visibility modifier. */
private[parser] def protKw[$: P]: P[Unit] = P("prot" ~ wordBoundary)

/** Parses the `priv` visibility modifier. */
private[parser] def privKw[$: P]: P[Unit] = P("priv" ~ wordBoundary)

/** Declaration recovery must preserve modifiers as well as the declaration keyword. Expressions
  * permit local `let` and `fn` bindings, so their recovery uses only the declaration-only starts.
  */
private val declarationOnlyKeywords =
  List("op", "type", "struct", "protocol", "instance", "pub", "prot", "priv", "inline")

private[parser] val declarationOnlyStops: List[Recovery.Anchor] =
  declarationOnlyKeywords.map(Recovery.Anchor.Keyword(_))

private[parser] val declarationStops: List[Recovery.Anchor] =
  List("let", "fn").map(Recovery.Anchor.Keyword(_)) ++ declarationOnlyStops

private[parser] def declarationStartP[$: P]: P[Unit] =
  P(letKw | fnKw | declarationOnlyStartP)

private[parser] def declarationOnlyStartP[$: P]: P[Unit] =
  P(CharsWhileIn("a-zA-Z0-9_", 1).!)
    .filter(declarationOnlyKeywords.contains)
    .map(_ => ())

/** Parses any reserved word or punctuation token that must be excluded from identifiers. */
private[parser] def keywords[$: P]: P[Unit] =
  P(
    moduleKw | protocolKw | instanceKw |
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
      reprKw |
      ffiKw |
      tplKw |
      fnKw |
      arrowKw |
      structKw |
      pubKw |
      protKw |
      privKw
  )

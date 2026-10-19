import sbt.*
import sbt.Keys.{console, scalacOptions}

object ScalacConfig {

  lazy val opts: Seq[String] =
    Seq(
      "-deprecation", // Emit warning and location for usages of deprecated APIs.
      "-encoding",
      "utf-8", // Specify character encoding used by source files.
      // "-explain", // Explain errors in more detail.
      // "-explaintypes", // Explain type errors in more detail.
      "-feature", // Emit warning and location for usages of features that should be imported explicitly.
      "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
      "-language:higherKinds", // Allow higher-kinded types
      "-language:implicitConversions", // Allow definition of implicit functions called views
      "-unchecked", // Enable additional warnings where generated code depends on assumptions.
      "-Wunused:all",
      "-Werror",
      "-language:strictEquality",
      "-new-syntax" // Enforce new syntax
    )

  // FastParse 3.1.1 uses ArrowAssoc in its inline failure tracing.
  val fastparseWarningSuppression: String =
    "-Wconf:cat=deprecation&msg=^method (ArrowAssoc in object Predef|-> in class ArrowAssoc)" +
      " is deprecated since 3[.]10[.]0&src=fastparse/internal/MacroInlineImpls[.]scala$:s"

}

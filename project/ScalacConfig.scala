import sbt.Keys.{console, scalacOptions}
import sbt.*

object ScalacConfig {

  lazy val opts: Seq[String] =
    Seq(
      "-deprecation", // Emit warning and location for usages of deprecated APIs.
      "-encoding",
      "utf-8", // Specify character encoding used by source files.
      "-explaintypes", // Explain type errors in more detail.
      "-feature", // Emit warning and location for usages of features that should be imported explicitly.
      "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
      "-language:higherKinds", // Allow higher-kinded types
      "-language:implicitConversions", // Allow definition of implicit functions called views
      "-unchecked", // Enable additional warnings where generated code depends on assumptions.
      "-Xfatal-warnings", // Fail the compilation if there are any warnings.
      // "-Ywarn-unused:imports",           // Warn if an import selector is not referenced.
      "-language:strictEquality",
      // "-explain",
      "-new-syntax" // Enforce new syntax
//      "-indent"
//      "-rewrite",
    )

}

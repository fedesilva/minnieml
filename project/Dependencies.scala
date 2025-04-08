import sbt.*

object Dependencies {

  val logBackVersion       = "1.5.18"
  val monocleVersion       = "3.3.0"
  val catsVersion          = "2.12.0"
  val catsEffectVersion    = "3.6.1"
  val refinedVersion       = "0.11.3"
  val reload4jVersion      = "1.2.26"
  val lucumaRefinedVersion = "0.1.4"
  val munitVersion         = "1.1.0"
  val munitCatsVersion     = "2.1.0"
  val fastparseVersion     = "3.1.1"
  val scoptVersion         = "4.1.0"

  lazy val commonDependencies: Seq[ModuleID] =
    Seq(
      "ch.qos.reload4j" % "reload4j"        % reload4jVersion,
      "ch.qos.logback"  % "logback-classic" % logBackVersion,
      // "core" module - IO, IOApp, schedulers
      // This pulls in the kernel and std modules automatically.
      "org.typelevel" %% "cats-effect"    % catsEffectVersion,
      "eu.timepit"    %% "refined"        % refinedVersion,
      "eu.timepit"    %% "refined-cats"   % refinedVersion,
      "edu.gemini"    %% "lucuma-refined" % lucumaRefinedVersion,
      "dev.optics"    %% "monocle-core"   % monocleVersion,
      "dev.optics"    %% "monocle-macro"  % monocleVersion
    )

  lazy val testDeps: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit"             % munitVersion,
    "org.typelevel" %% "munit-cats-effect" % munitCatsVersion
  ).map(_ % Test)

  lazy val mmlclib: Seq[ModuleID] =
    Seq(
      "com.lihaoyi" %% "fastparse" % fastparseVersion
    ) ++ commonDependencies ++ testDeps

  lazy val mmlc: Seq[ModuleID] =
    Seq(
      "com.github.scopt" %% "scopt" % scoptVersion
    ) ++ commonDependencies ++ testDeps

  lazy val resolvers =
    Seq(
      "fede-github" at "https://raw.githubusercontent.com/fedesilva/fede-maven/master/"
    )

}

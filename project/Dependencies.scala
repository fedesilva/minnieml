import sbt.*

object Dependencies {

  val logBackVersion    = "1.5.18"
  val monocleVersion    = "3.3.0"
  val catsVersion       = "2.12.0"
  val catsEffectVersion = "3.6.0"
  val refinedVersion    = "0.11.3"

  lazy val commonDependencies: Seq[ModuleID] =
    Seq(
      "ch.qos.reload4j" % "reload4j"        % "1.2.26",
      "ch.qos.logback"  % "logback-classic" % logBackVersion,
      // "core" module - IO, IOApp, schedulers
      // This pulls in the kernel and std modules automatically.
      "org.typelevel" %% "cats-effect"    % catsEffectVersion,
      "eu.timepit"    %% "refined"        % refinedVersion,
      "eu.timepit"    %% "refined-cats"   % refinedVersion,
      "edu.gemini"    %% "lucuma-refined" % "0.1.3",
      "dev.optics"    %% "monocle-core"   % monocleVersion,
      "dev.optics"    %% "monocle-macro"  % monocleVersion
    )

  lazy val testDeps: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit"             % "1.1.0",
    "org.typelevel" %% "munit-cats-effect" % "2.0.0"
  ).map(_ % Test)

  lazy val mmlclib: Seq[ModuleID] =
    Seq(
      "com.lihaoyi" %% "fastparse" % "3.1.1"
    ) ++ commonDependencies ++ testDeps

  lazy val mmlc: Seq[ModuleID] =
    Seq(
      "com.github.scopt" %% "scopt" % "4.1.0"
    ) ++ commonDependencies ++ testDeps

  lazy val resolvers =
    Seq(
      "fede-github" at "https://raw.githubusercontent.com/fedesilva/fede-maven/master/"
    )

}

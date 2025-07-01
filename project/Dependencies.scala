import sbt.*

object Dependencies {

  val logBackVersion:       String = "1.5.18"
  val monocleVersion:       String = "3.3.0"
  val catsVersion:          String = "2.12.0"
  val catsEffectVersion:    String = "3.6.1"
  val refinedVersion:       String = "0.11.3"
  val reload4jVersion:      String = "1.2.26"
  val lucumaRefinedVersion: String = "0.1.4"
  val munitVersion:         String = "1.1.1"
  val munitCatsVersion:     String = "2.1.0"
  val fastparseVersion:     String = "3.1.1"
  val scoptVersion:         String = "4.1.0"

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

  lazy val resolvers: Seq[MavenRepository] =
    Seq(
      "fede-github" at "https://raw.githubusercontent.com/fedesilva/fede-maven/master/"
    )

}

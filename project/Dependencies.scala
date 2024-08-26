import sbt._

object Dependencies {

  val logBackVersion    = "1.2.3"
  val monocleVersion    = "3.3.0"
  val catsVersion       = "2.12.0"
  val catsEffectVersion = "3.5.4"
  val slf4jVersion      = "1.7.30"
  val neo4jVersion      = "4.3.2"

  lazy val commonDependencies: Seq[ModuleID] =
    Seq(
      "org.slf4j"      % "slf4j-api"       % slf4jVersion,
      "org.slf4j"      % "slf4j-nop"       % slf4jVersion,
      "ch.qos.logback" % "logback-classic" % logBackVersion,
      // "core" module - IO, IOApp, schedulers
      // This pulls in the kernel and std modules automatically.
      "org.typelevel" %% "cats-effect" % catsEffectVersion
    )

  lazy val testDeps: Seq[ModuleID] = Seq(
    "org.scalatest" %% "scalatest" % "3.2.19",
    "org.typelevel" %% "munit-cats-effect" % "2.0.0" 
  ).map(_ % Test)

  lazy val mmlclib: Seq[ModuleID] =
    Seq(
      "org.bytedeco.javacpp-presets" % "llvm-platform" % "7.0.1-1.4.4",
      // "io.github.myui"               % "btree4j"       % "0.9.1",
      "org.neo4j" % "neo4j" % neo4jVersion
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

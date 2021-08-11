import sbt._

object Dependencies {

  val logBackVersion = "1.2.3"
  val monocleVersion = "2.0.4"
  val catsVersion = "2.1.1"
  val catsMtlVersion = "0.7.1"
  val catsEffectVersion = "2.1.2"
  val slf4jVersion = "1.7.30"
  val neo4jVersion = "4.3.2"

  lazy val commonDependencies: Seq[ModuleID] =
    Seq(
      "org.slf4j"      % "slf4j-api"       % slf4jVersion,
      "org.slf4j"      % "slf4j-nop"       % slf4jVersion,
      "ch.qos.logback" % "logback-classic" % logBackVersion
    )

  lazy val testDeps: Seq[ModuleID] = Seq(
    "org.scalatest" %% "scalatest" % "3.1.1"
  ).map(_ % Test)

  lazy val mmlclib: Seq[ModuleID] =
    Seq(
      "org.bytedeco.javacpp-presets" % "llvm-platform" % "7.0.1-1.4.4",
      "com.sksamuel.avro4s"         %% "avro4s-core"   % "3.0.9",
      "io.github.myui"               % "btree4j"       % "0.9.1",
      "org.neo4j"                    % "neo4j"         % neo4jVersion
    ) ++ commonDependencies ++ testDeps

  lazy val mmlc: Seq[ModuleID] =
    Seq(
      "com.github.scopt" %% "scopt" % "4.0.0-RC2"
    ) ++ commonDependencies ++ testDeps

  lazy val resolvers =
    Seq(
      "fede-github" at "https://raw.githubusercontent.com/fedesilva/fede-maven/master/"
    )

}

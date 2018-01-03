import sbt._

object Dependencies {
  
  val logBackVersion    = "1.2.3"
  val monocleVersion    = "1.5.0" // 1.5.0-cats based on cats 1.0.x
  val catsVersion       = "1.6.0"
  val catsMtlVersion    = "0.5.0"
  val catsEffectVersion = "1.3.1"
  val slf4jVersion      = "1.7.26"
  
  lazy val commonDependencies: Seq[ModuleID] =
    Seq(
      "org.typelevel"                     %% "cats-core"        % catsVersion,
      "org.typelevel"                     %% "cats-macros"      % catsVersion,
      "org.typelevel"                     %% "cats-free"        % catsVersion,
      "org.typelevel"                     %% "cats-effect"      % catsEffectVersion,
      "org.typelevel"                     %% "cats-mtl-core"    % catsMtlVersion,
      "com.github.julien-truffaut"        %% "monocle-core"     % monocleVersion,
      "com.github.julien-truffaut"        %% "monocle-macro"    % monocleVersion,
      "org.slf4j"                         %  "slf4j-api"        % slf4jVersion,
      "org.slf4j"                         %  "slf4j-nop"        % slf4jVersion,
      "ch.qos.logback"                    %  "logback-classic"  % logBackVersion
    )
  
  lazy val testDeps: Seq[ModuleID] = Seq(
    "org.typelevel" %% "cats-testkit"               % catsVersion,
    "org.typelevel" %% "cats-laws"                  % catsVersion,
    "org.typelevel" %% "cats-mtl-laws"              % catsMtlVersion,
    "com.github.julien-truffaut" %%  "monocle-law"  % monocleVersion,
    "org.scalatest" %% "scalatest"                  % "3.0.7",
  ).map( _ % Test )
  
  lazy val mmlclib: Seq[ModuleID] =
    Seq(
      "org.bytedeco.javacpp-presets"  %   "llvm-platform"     % "7.0.1-1.4.4",
      "com.sksamuel.avro4s"           %%   "avro4s-core"      % "2.0.4",
      "io.github.myui"                %   "btree4j"           % "0.9"
    ) ++ commonDependencies ++ testDeps
  
  lazy val mmlc: Seq[ModuleID] =
    Seq(
      "com.github.scopt" % "scopt_2.11" % "4.0.0-RC2"
    ) ++ commonDependencies ++ testDeps
  
  lazy val resolvers = 
    Seq(
      "fede-github" at "https://raw.githubusercontent.com/fedesilva/fede-maven/master/"
    )
  
}

import sbt._

lazy val commonSettings =
  Seq(
    organization := "minnie-ml",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.6",
    maintainer   := "fede.silva@gmail.com"
  ) ++
    Seq(
      scalacOptions ++= ScalacConfig.opts,
      // because for tests, yolo.
      scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"),
      scalacOptions in (Test, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"),
      scalacOptions in Test --= Seq("-Ywarn-dead-code", "-Ywarn-unused:locals", "-Xfatal-warnings"),
      fork in test := true
    ) ++
    ScalacConfig.plugins ++
    Seq(
      resolvers ++= Dependencies.resolvers
    )

val antlrPackageName = "mml.mmlclib.parser.antlr"

lazy val mml =
  project
    .in(file("."))
    .settings(commonSettings)
    .dependsOn(mmlclib, mmlc)
    .aggregate(mmlclib, mmlc)

lazy val mmlclib =
  project
    .in(file("modules/mmlc-lib"))
    .enablePlugins(Antlr4Plugin)
    .enablePlugins(BuildInfoPlugin)
    .settings(
      name := "mmlc-lib",
      commonSettings
    )
    .settings(
      libraryDependencies ++= Dependencies.mmlclib
      //jacocoExcludes in Test := Seq(s"$antlrPackageName.*")
    )
    .settings(
      antlr4PackageName in Antlr4 := Some(antlrPackageName),
      // I can walk by myself, if you don't mind
      antlr4GenVisitor  in Antlr4 := false,
      antlr4GenListener in Antlr4 := false,
      antlr4Version     in Antlr4 := "4.8-1"
    )
    .settings(
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage := "mml.mmlclib"
    )

lazy val mmlc =
  project
    .in(file("modules/mmlc"))
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(BuildInfoPlugin)
    .settings(
      name := "mmlc",
      commonSettings
    )
    .settings(
      libraryDependencies ++= Dependencies.mmlc
    )
    .settings(
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage := "mml.mmlc"
    )
    .dependsOn(mmlclib)

import sbt.*

lazy val commonSettings =
  Seq(
    organization := "minnie-ml",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := "3.5.0",
    scalacOptions ++= ScalacConfig.opts,
    // because for tests, yolo.
    Test / scalacOptions --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"),
    Test / scalacOptions --= Seq("-Ywarn-dead-code", "-Ywarn-unused:locals", "-Xfatal-warnings"),
    // Global / onChangedBuildSource := ReloadOnSourceChanges
    resolvers ++= Dependencies.resolvers,
    // add the sbt plugin to the build
    notifyOn(Compile / compile)
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
    .enablePlugins(NotificationsPlugin)
    .settings(
      name := "mmlc-lib",
      commonSettings,
      libraryDependencies ++= Dependencies.mmlclib,
      // jacocoExcludes in Test := Seq(s"$antlrPackageName.*")
      Antlr4 / antlr4PackageName := Some(antlrPackageName),
      // I can walk by myself, if you don't mind
      Antlr4 / antlr4GenVisitor  := false,
      Antlr4 / antlr4GenListener := false,
      Antlr4 / antlr4Version     := "4.8-1",
      buildInfoKeys              := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage           := "mml.mmlclib"
    )

lazy val mmlc =
  project
    .in(file("modules/mmlc"))
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(BuildInfoPlugin)
    .enablePlugins(NotificationsPlugin)
    .settings(
      name := "mmlc",
      commonSettings,
      libraryDependencies ++= Dependencies.mmlc,
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage := "mml.mmlc"
    )
    .dependsOn(mmlclib)

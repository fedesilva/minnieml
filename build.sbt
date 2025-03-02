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


lazy val mml =
  project
    .in(file("."))
    .settings(commonSettings)
    .dependsOn(mmlclib, mmlc)
    .aggregate(mmlclib, mmlc)

lazy val mmlclib =
  project
    .in(file("modules/mmlc-lib"))
    .enablePlugins(BuildInfoPlugin, NotificationsPlugin)
    .settings(
      name := "mmlc-lib",
      commonSettings,
      libraryDependencies ++= Dependencies.mmlclib,
      buildInfoKeys              := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage           := "mml.mmlclib"
    )

lazy val mmlc =
  project
    .in(file("modules/mmlc"))
    // .enablePlugins(JavaAppPackaging)
    .enablePlugins(
      NativeImagePlugin,
      BuildInfoPlugin,
      NotificationsPlugin
    )
    .settings(
      name := "mmlc",
      commonSettings,
      libraryDependencies ++= Dependencies.mmlc,
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage := "mml.mmlc",
      // Native image settings
      // we need to manually install `sdk install java 23.3.3-graalce`
      // not sure how to tell the plugin to use a more recent version.
      nativeImageCommand := List("native-image"),
      nativeImageOptions ++= Seq(
        "-H:+UnlockExperimentalVMOptions",
        "--no-fallback",               // Remove JVM fallback, reducing size
        "-H:+RemoveUnusedSymbols",     // Strip unused symbols
        "-O3",                          // Enable maximum optimization level
      ),
      Compile / mainClass := Some("mml.mmlc.Main")
    )
    .dependsOn(mmlclib)




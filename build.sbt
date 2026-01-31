import sbt.`*`
import sbt.io.IO

lazy val commonSettings =
  Seq(
    organization := "minnie-ml",
    version      := "0.0.1-SNAPSHOT",
    scalaVersion := "3.8.1",
    scalacOptions ++= ScalacConfig.opts,
    // because for tests, yolo.
    Test / scalacOptions --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"),
    Test / scalacOptions --= Seq("-Ywarn-dead-code", "-Ywarn-unused:locals", "-Xfatal-warnings"),
    // Global / onChangedBuildSource := ReloadOnSourceChanges,
    resolvers ++= Dependencies.resolvers,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    // scalafixDependencies += "io.github.dedis" %% "scapegoat-scalafix" % "1.1.4", // disabled: no Scala 3.8.1 support
    // Set fork to true as recommended by cats-effect for all projects
    Compile / run / fork := true
  )



// Main project
lazy val mml: Project =
  project
    .in(file("."))
    .settings(
      commonSettings,
      Compile / mainClass := Some("mml.mmlc.Main"),
      Compile / gitSha    := readSha,

      mmlc / assembly / test := (Test / test).value,

      // Distribution tasks from root project
      mmlcPublishLocal := (mmlc / mmlcPublishLocal).value,
      
      mmlcDistroAssembly := {
        
        val jarPath = {
          val out = (mmlc / assembly / assemblyOutputPath).value
          (mmlc / assembly).value
          out
        }
        val scriptSource = file(sys.props("user.dir")) / "packaging" / "mmlc"

        val date       = java.time.LocalDate.now().toString
        val sha        = (Compile / gitSha).value
        val suffix     = s"$date-$sha"
        val packageDir = target.value / s"mml-distro-$suffix"

        // Ensure directory exists
        IO.createDirectory(packageDir)

        // Copy the assembled jar
        val jarTarget = packageDir / jarPath.getName
        IO.copyFile(jarPath, jarTarget, preserveLastModified = true)

        // Copy the shell script wrapper to the same directory
        val scriptTarget = packageDir / "mmlc"
        IO.copyFile(scriptSource, scriptTarget, preserveLastModified = true, preserveExecutable = true)
        IO.chmod("rwxr-xr-x", scriptTarget)

        // Copy samples directory
        val samplesSource = file(sys.props("user.dir")) / "mml" / "samples"
        val samplesTarget = packageDir / "samples"
        IO.createDirectory(samplesTarget)
        IO.copyDirectory(
          source               = samplesSource,
          target               = samplesTarget,
          preserveLastModified = true
        )
        println(s"Samples copied to ${samplesTarget.getAbsolutePath}")

        // Copy tooling/vscode directory
        val toolingSource = file(sys.props("user.dir")) / "tooling" / "vscode"
        val toolingTarget = packageDir / "tooling" / "vscode"
        IO.createDirectory(packageDir / "tooling") // Ensure parent 'tooling' dir exists
        IO.copyDirectory(
          source               = toolingSource,
          target               = toolingTarget,
          preserveLastModified = true
        )
        println(s"VSCode tooling copied to ${toolingTarget.getAbsolutePath}")

        println(s"Distribution package created at ${packageDir.getAbsolutePath}")
        packageDir
      },
      mmlcDistro := {
        val packageDir = mmlcDistroAssembly.value
        val date       = java.time.LocalDate.now().toString
        val sha        = (Compile / gitSha).value
        val suffix     = s"$date-$sha"
        val zipFile    = target.value / s"mml-distro-$suffix.zip"

        // Create the zip file
        IO.zip(
          sources   = Path.allSubpaths(packageDir),
          outputZip = zipFile,
          time      = None
        )

        println(s"Distribution package zipped to ${zipFile.getAbsolutePath}")
        zipFile
      }
    )
    .dependsOn(mmlclib, mmlc)
    .aggregate(mmlclib, mmlc)

// Library project
lazy val mmlclib =
  project
    .in(file("modules/mmlc-lib"))
    .enablePlugins(BuildInfoPlugin)
    .settings(
      name := "mmlc-lib",
      commonSettings,
      libraryDependencies ++= Dependencies.mmlclib,
      buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("build") {
          java.time.Instant.now().toString
        },
        BuildInfoKey.action("gitSha") {
          scala.sys.process.Process("git rev-parse --short HEAD").!!.trim
        },
        BuildInfoKey.action("os") {
          System.getProperty("os.name")
        },
        BuildInfoKey.action("arch") {
          System.getProperty("os.arch")
        }
      ),
      buildInfoObject  := "MmlLibBuildInfo",
      buildInfoPackage := "mml.mmlclib"
    )

// Compiler CLI project
lazy val mmlc =
  project
    .in(file("modules/mmlc"))
    .enablePlugins(
      JavaAppPackaging,
      BuildInfoPlugin
    )
    .settings(
      name := "mmlc",
      commonSettings,
      libraryDependencies ++= Dependencies.mmlc,

      assembly / assemblyJarName := s"mmlc.jar",

      assembly / test := (Test / test).value,

      buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("build") {
          java.time.Instant.now().toString
        },
        BuildInfoKey.action("gitSha") {
          scala.sys.process.Process("git rev-parse --short HEAD").!!.trim
        },
        BuildInfoKey.action("os") {
          System.getProperty("os.name")
        },
        BuildInfoKey.action("arch") {
          System.getProperty("os.arch")
        }
      ),
      buildInfoObject     := "MmlcBuildInfo",
      buildInfoPackage    := "mml.mmlc",
      Compile / mainClass := Some("mml.mmlc.Main"),

      mmlcPublishLocal := {
        val jarPath = {
          val out = (assembly / assemblyOutputPath).value
          (assembly).value
          out
        }
        val homeDir      = sys.props.getOrElse("user.home", "~")
        val binDir       = file(s"$homeDir/bin")
        val scriptSource = file(sys.props("user.dir")) / "packaging" / "mmlc"
        val scriptTarget = binDir / "mmlc"
        val jarTarget    = binDir / jarPath.getName

        println(s"Installing assembled jar to ${binDir.getAbsolutePath}")

        // Create bin directory if it doesn't exist
        if (!binDir.exists()) {
          IO.createDirectory(binDir)
          println(s"Created directory: ${binDir.getAbsolutePath}")
        }

        // Copy the jar
        IO.copyFile(jarPath, jarTarget, preserveLastModified = true)

        // Copy the shell script
        IO.copyFile(scriptSource, scriptTarget, preserveLastModified = true, preserveExecutable = true)

        // Ensure the script is executable
        IO.chmod("rwxr-xr-x", scriptTarget)

        println(s"Successfully installed mmlc.jar and script to ${binDir.getAbsolutePath}")
        scriptTarget
      }

    )
    .dependsOn(mmlclib)


// Task key definitions
lazy val mmlcPublishLocal = taskKey[File]("Generates a assembly jar and installs it to ~/bin")
lazy val mmlcDistroAssembly =
  taskKey[File]("Creates a distribution package with the assembly jar in bin/ directory")
lazy val mmlcDistro = taskKey[File]("Creates a zip file of the distribution package")

lazy val gitSha = settingKey[String]("Git version of the project")
def readSha: String = {
  import scala.sys.process._
  val sha = "git rev-parse HEAD".!!.trim
  sha.take(8)
}

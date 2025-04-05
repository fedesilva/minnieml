import sbt.`*`
import sbt.io.IO

lazy val commonSettings =
  Seq(
    organization := "minnie-ml",
    version      := "0.0.1-SNAPSHOT",
    scalaVersion := "3.6.4",
    scalacOptions ++= ScalacConfig.opts,
    // because for tests, yolo.
    Test / scalacOptions --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"),
    Test / scalacOptions --= Seq("-Ywarn-dead-code", "-Ywarn-unused:locals", "-Xfatal-warnings"),
    // Global / onChangedBuildSource := ReloadOnSourceChanges,
    resolvers ++= Dependencies.resolvers,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
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

      // Distribution tasks moved to root project
      mmlcPublishLocal := (mmlc / mmlcPublishLocal).value,
      mmlcDistroAssembly := {
        val binPath      = (mmlc / Compile / nativeImage).value
        val scriptSource = file(sys.props("user.dir")) / "packaging" / "mmlc"

        // Create the package directory structure in the root target
        val os         = (mmlc / Compile / osName).value
        val arch       = (mmlc / Compile / osArch).value
        val sha        = (Compile / gitSha).value
        val suffix     = s"$os-$arch-$sha"
        val packageDir = target.value / s"mml-distro-$suffix"

        // Ensure directory exists
        IO.createDirectory(packageDir)

        // Copy the binary with OS-arch suffix to the same directory as the script
        val targetBin = packageDir / binPath.getName
        IO.copyFile(binPath, targetBin, preserveLastModified = true, preserveExecutable = true)
        IO.chmod("rwxr-xr-x", targetBin)

        // Copy the shell script wrapper to the same directory
        val scriptTarget = packageDir / "mmlc"
        IO.copyFile(
          scriptSource,
          scriptTarget,
          preserveLastModified = true,
          preserveExecutable   = true
        )
        IO.chmod("rwxr-xr-x", scriptTarget)

        println(s"Distribution package created at ${packageDir.getAbsolutePath}")
        packageDir
      },
      mmlcDistro := {
        val packageDir = mmlcDistroAssembly.value
        val os         = (mmlc / Compile / osName).value
        val arch       = (mmlc / Compile / osArch).value
        val sha        = (Compile / gitSha).value
        val suffix     = s"$os-$arch-$sha"
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
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoObject  := "MmlLibBuildInfo",
      buildInfoPackage := "mml.mmlclib"
    )

// Compiler CLI project
lazy val mmlc =
  project
    .in(file("modules/mmlc"))
    .enablePlugins(
      NativeImagePlugin,
      JavaAppPackaging,
      BuildInfoPlugin
    )
    .settings(
      name := "mmlc",
      commonSettings,
      libraryDependencies ++= Dependencies.mmlc,

      // OS and architecture detection
      Compile / osName := readOs,
      Compile / osArch := readArch,
      nativeImageOptimizationLevel := {
        if (scala.sys.env.get("MML_NI_OPT").isDefined)
          "-" + scala.sys.env("MML_NI_OPT")
        else if (version.value.contains("SNAPSHOT"))
          "-Ob"
        else
          "-O3"
      },

      // Native image settings
      nativeImageCommand := List ("native-image"),
      nativeImageOptions ++= Seq(
        "-H:+UnlockExperimentalVMOptions",
        "--no-fallback", // Remove JVM fallback, reducing size
        "-H:+RemoveUnusedSymbols", // Strip unused symbols
        nativeImageOptimizationLevel.value,
        "--strict-image-heap",
        "-march=native", // Use the native architecture
        "-H:+BuildReport" // Generate a build report
        // "--gc=G1",                  // Use the G1 garbage collector. Not in darwin x86_64.
      ),

      // Use OS-arch suffix for the native image name
      nativeImageOutput := {
        val dir     = target.value / "native-image"
        val os      = (Compile / osName).value
        val arch    = (Compile / osArch).value
        val newName = s"mmlc-$os-$arch"
        dir / newName
      },
      buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("build") {
          java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now())
        },
        BuildInfoKey.action("os") {
          s"${(Compile / osName).value}"
        },
        BuildInfoKey.action("arch") {
          (Compile / osArch).value
        },
        BuildInfoKey.action("gitSha") {
          readSha
        }
      ),
      buildInfoObject     := "MmlcBuildInfo",
      buildInfoPackage    := "mml.mmlc",
      Compile / mainClass := Some("mml.mmlc.Main"),

      // Native image tasks
      mmlcPublishLocal := {
        val binPath = (Compile / nativeImage).value
        val homeDir = sys.props.getOrElse("user.home", "~")
        val binDir  = file(s"$homeDir/bin")

        // Use root level packaging directory to find the script
        val scriptSource = file(sys.props("user.dir")) / "packaging" / "mmlc"
        val scriptTarget = binDir / "mmlc"

        println(s"Installing native image to ${binDir.getAbsolutePath}")

        try {
          // Create bin directory if it doesn't exist
          if (!binDir.exists()) {
            IO.createDirectory(binDir)
            println(s"Created directory: ${binDir.getAbsolutePath}")
          }

          // Copy the binary with OS-arch suffix
          IO.copyFile(
            binPath,
            binDir / binPath.getName,
            preserveLastModified = true,
            preserveExecutable   = true
          )

          // Copy the shell script
          IO.copyFile(
            scriptSource,
            scriptTarget,
            preserveLastModified = true,
            preserveExecutable   = true
          )

          // Ensure the files are executable
          IO.chmod("rwxr-xr-x", binDir / binPath.getName)
          IO.chmod("rwxr-xr-x", scriptTarget)

          println(s"Successfully installed native image and script to ${binDir.getAbsolutePath}")
          scriptTarget
        } catch {
          case e: Exception =>
            println(s"Failed to install native image: ${e.getMessage}")
            throw e
        }
      }

      // Distribution tasks moved to root project
    )
    .dependsOn(mmlclib)
//
// Task key definitions
lazy val mmlcPublishLocal = taskKey[File]("Generates a native image and installs it to ~/bin")
lazy val mmlcDistroAssembly =
  taskKey[File]("Creates a distribution package with the native image in bin/ directory")
lazy val mmlcDistro = taskKey[File]("Creates a zip file of the distribution package")

lazy val nativeImageOptimizationLevel =
  settingKey[String]("Optimization level for native image generation")

// OS and architecture detection for binary naming
lazy val osName = settingKey[String]("Determines the OS name (linux, darwin, windows)")
def readOs = {
  val os = System.getProperty("os.name").toLowerCase
  if (os.contains("linux")) "linux"
  else if (os.contains("mac") || os.contains("darwin")) "darwin"
  else if (os.contains("windows")) "windows"
  else "unknown"
}

lazy val osArch = settingKey[String]("Determines the architecture (x86_64, aarch64)")
def readArch = {
  val arch = System.getProperty("os.arch").toLowerCase
  if (arch.contains("amd64") || arch.contains("x86_64")) "x86_64"
  else if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64"
  else arch
}

lazy val gitSha = settingKey[String]("Git version of the project")
def readSha: String = {
  import scala.sys.process._
  val sha = "git rev-parse HEAD".!!.trim
  sha.take(8)
}
// TODO: abstract the os and arch functions here, too. as opposed of computing them inline


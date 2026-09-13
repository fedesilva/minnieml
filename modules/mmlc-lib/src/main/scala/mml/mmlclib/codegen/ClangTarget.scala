package mml.mmlclib.codegen

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import java.io.{ByteArrayInputStream, File}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import java.util.HexFormat
import scala.sys.process.{Process, ProcessLogger}

/** Target-dependent function attributes shared by all MML definitions. */
case class TargetAttributes(cpu: Option[String], features: Option[String]):

  def llvm: String =
    List("target-cpu" -> cpu, "target-features" -> features)
      .flatMap { case (name, value) =>
        value.map(v => s""""$name"="$v"""")
      }
      .mkString(" ")

/** The selected Clang invocation and the attributes it assigns to this target. */
case class ClangTarget(
  executable: Path,
  flags:      List[String],
  cacheKey:   String,
  attributes: TargetAttributes
)

object ClangTarget:

  private val probeSource = "int mml_target_probe(int x) { return x + 1; }\n"
  private val probeDefinition =
    "(?m)^define [^\\n]*@mml_target_probe\\([^\\n]*\\) [^\\n]*#(\\d+) \\{".r
  private val attributeGroup      = "(?m)^attributes #(\\d+) = \\{ ([^\\n]*) \\}$".r
  private val targetAttributeName = "\"target-(cpu|features)\"".r
  private val targetAttribute     = "\"(target-cpu|target-features)\"=\"([^\"]*)\"".r

  /** Resolve attributes without executing target code. Cache entries live in the build directory.
    */
  def resolve(
    buildDir: Path,
    flags:    List[String]
  ): IO[Either[LlvmCompilationError, ClangTarget]] =
    (for
      executable <- EitherT(findClang)
      target <- EitherT(resolveWithClang(executable, buildDir, flags))
    yield target).value

  private def findClang: IO[Either[LlvmCompilationError, Path]] =
    IO.blocking {
      sys.env
        .getOrElse("PATH", "")
        .split(File.pathSeparator, -1)
        .iterator
        .map(dir => Paths.get(dir).resolve("clang"))
        .find(path => Files.isRegularFile(path) && Files.isExecutable(path))
        .map(_.toRealPath())
        .toRight(LlvmCompilationError.LlvmNotInstalled(List("clang")))
    }.attempt
      .map(_.leftMap(error => resolutionError(error.getMessage)).flatten)

  private[codegen] def resolveWithClang(
    executable: Path,
    buildDir:   Path,
    flags:      List[String]
  ): IO[Either[LlvmCompilationError, ClangTarget]] =
    (for
      identity <- EitherT(
        IO.blocking {
          val realPath = executable.toRealPath()
          val keyParts = List(
            "mml-target-probe-v1",
            realPath.toString,
            Files.size(realPath).toString,
            Files.getLastModifiedTime(realPath).toString
          ) ++ flags
          val digest =
            MessageDigest.getInstance("SHA-256").digest(keyParts.mkString("\u0000").getBytes(UTF_8))
          (realPath, HexFormat.of().formatHex(digest))
        }.attempt
          .map(_.leftMap(error => resolutionError(error.getMessage)))
      )
      (realPath, key) = identity
      cacheFile       = buildDir.resolve("toolchain").resolve(s"target-$key.ll")
      cached <- EitherT.right[LlvmCompilationError](
        IO.blocking(Files.readString(cacheFile)).attempt.map(_.toOption.flatMap(parseAttributes))
      )
      attributes <- EitherT(cached match
        case Some(value) => IO.pure(value.asRight[LlvmCompilationError])
        case None => probeAndCache(realPath, flags, cacheFile))
    yield ClangTarget(realPath, flags, key, attributes)).value

  private def probeAndCache(
    executable: Path,
    flags:      List[String],
    cacheFile:  Path
  ): IO[Either[LlvmCompilationError, TargetAttributes]] =
    val command =
      executable.toString :: flags ::: List("-x", "c", "-S", "-emit-llvm", "-o", "-", "-")
    (for
      ir <- EitherT(
        IO.blocking {
          // ProcessLogger callbacks require mutable buffers at the subprocess boundary.
          val stdout = new StringBuilder
          val stderr = new StringBuilder
          val logger = ProcessLogger(
            line => stdout.append(line).append('\n'),
            line => stderr.append(line).append('\n')
          )
          val exitCode =
            (Process(command) #< new ByteArrayInputStream(probeSource.getBytes(UTF_8))).!(logger)
          if exitCode == 0 then stdout.toString.asRight
          else
            LlvmCompilationError
              .CommandExecutionError(command.mkString(" "), stderr.toString, exitCode)
              .asLeft
        }.attempt
          .map(_.leftMap(error => resolutionError(error.getMessage)).flatten)
      )
      attributes <- EitherT.fromEither[IO](
        parseAttributes(ir).toRight(resolutionError("Clang returned no valid probe attributes"))
      )
      _ <- EitherT(writeCache(cacheFile, ir))
    yield attributes).value

  private def writeCache(path: Path, ir: String): IO[Either[LlvmCompilationError, Unit]] =
    IO.blocking {
      Files.createDirectories(path.getParent)
      Files.createTempFile(path.getParent, "target-", ".tmp")
    }.bracket { temporary =>
      IO.blocking {
        Files.writeString(temporary, ir)
        Files.move(
          temporary,
          path,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      }.void
    }(temporary => IO.blocking(Files.deleteIfExists(temporary)).void)
      .attempt
      .map(
        _.leftMap(error => resolutionError(s"Cannot cache target attributes: ${error.getMessage}"))
      )

  private[codegen] def parseAttributes(ir: String): Option[TargetAttributes] =
    for
      definition <- probeDefinition.findFirstMatchIn(ir)
      group <- attributeGroup.findAllMatchIn(ir).find(_.group(1) == definition.group(1))
      entries = targetAttribute
        .findAllMatchIn(group.group(2))
        .map(m => m.group(1) -> m.group(2))
        .toList
      if entries.size == targetAttributeName.findAllMatchIn(group.group(2)).size
      attributes = entries.toMap
      if attributes.size == entries.size
      cpu      = attributes.get("target-cpu")
      features = attributes.get("target-features")
      if cpu.forall(_.matches("[A-Za-z0-9_.+\\-]+"))
      if features.forall(_.matches("[+\\-][A-Za-z0-9_.\\-]+(,[+\\-][A-Za-z0-9_.\\-]+)*"))
    yield TargetAttributes(cpu, features)

  private def resolutionError(message: String): LlvmCompilationError =
    LlvmCompilationError.TargetResolutionError(message)

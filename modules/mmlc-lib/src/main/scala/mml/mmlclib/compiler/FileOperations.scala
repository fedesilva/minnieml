package mml.mmlclib.compiler

import cats.effect.{ExitCode, IO}
import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.Module
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

import java.nio.file.{Files, Path}

private[mmlclib] object FileOperations:

  def readFile(path: Path): IO[Either[Throwable, String]] =
    IO
      .blocking(Files.readString(path))
      .attempt

  def sanitizeFileName(path: Path): String =
    val fileName = path.getFileName.toString
    val nameWithoutExt = fileName.lastIndexOf('.') match
      case -1 => fileName
      case idx => fileName.substring(0, idx)
    ParserApi.sanitizeModuleName(nameWithoutExt)

  def cleanOutputDir(outputDir: String): IO[ExitCode] =
    IO.blocking {
      val dir = new java.io.File(outputDir)
      if dir.exists() then
        def deleteRecursively(file: java.io.File): Boolean =
          if file.isDirectory then file.listFiles().foreach(deleteRecursively)
          file.delete()

        deleteRecursively(dir)
        true
      else false
    }.flatMap { deleted =>
      if deleted then IO.println(s"Cleaned directory: $outputDir").as(ExitCode.Success)
      else IO.println(s"Output directory does not exist: $outputDir").as(ExitCode.Success)
    }

  def writeAstToFile(module: Module, outputDir: String): IO[Unit] =
    for
      _ <- IO.blocking(new java.io.File(outputDir).mkdirs())
      astFileName = s"$outputDir/${module.name}.ast"
      _ <- IO.blocking {
        val writer = new java.io.PrintWriter(new java.io.File(astFileName))
        try writer.write(prettyPrintAst(module, 2, false, true))
        finally writer.close()
      }
      _ <- IO.println(s"AST written to $astFileName")
    yield ()

  def handleFileReadError(error: Throwable): IO[ExitCode] =
    IO.println(s"""
                  |
                  | Error reading file: ${error.getMessage}
                  |
          """.stripMargin)
      .as(ExitCode.Error)

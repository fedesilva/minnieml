package mml.mmlc

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all.*
import mml.mmlclib.api.CodeGenApi
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.api.ParserApi
import mml.mmlclib.util.prettyPrintAst
import scopt.OParser

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object Main extends IOApp:

  case class Config(
    file:    Option[Path] = None,
    codeGen: Boolean      = false
  )

  private val builder = OParser.builder[Config]

  private val parser = {
    import builder.*
    OParser.sequence(
      programName("mmlc"),
      head("mmlc", "0.1"),
      arg[String]("<source-file>")
        .action((file, config) => config.copy(file = Some(Paths.get(file))))
        .text("Path to the source file"),
      opt[Unit]("code-gen")
        .action((_, config) => config.copy(codeGen = true))
        .text("Generate LLVM IR after compiling the source")
    )
  }

  def run(args: List[String]): IO[ExitCode] =
    OParser.parse(parser, args, Config()) match
      case Some(Config(Some(path), codeGen)) =>
        // if the module has an expicit name, use it; otherwise, use the file name
        val moduleName = sanitizeFileName(path)
        for
          content <- readFile(path)
          result <- CompilerApi.compileString(content, Some(moduleName))
          exitCode <- result match
            case Right(module) =>
              for
                _ <- IO.println(prettyPrintAst(module))
                cgResult <-
                  if codeGen then CodeGenApi.generateFromModule(module) else IO.pure(Right(""))
                _ <- cgResult match
                  case Right(ir) if codeGen => IO.println("\nGenerated LLVM IR:\n" + ir)
                  case Left(error) if codeGen => IO.println(s"Code Generation failed: $error")
                  case _ => IO.unit
              yield ExitCode.Success

            case Left(error) =>
              IO.println(s"Compilation failed: $error").as(ExitCode.Error)
        yield exitCode

      case _ =>
        IO.println("Usage: mmlc <source-file> [--code-gen]").as(ExitCode(1))

  private def readFile(path: Path): IO[String] =
    IO.blocking(Files.readString(path))

  private def sanitizeFileName(path: Path): String =
    val fileName = path.getFileName.toString
    val nameWithoutExt = fileName.lastIndexOf('.') match
      case -1 => fileName
      case idx => fileName.substring(0, idx)
    ParserApi.sanitizeModuleName(nameWithoutExt)

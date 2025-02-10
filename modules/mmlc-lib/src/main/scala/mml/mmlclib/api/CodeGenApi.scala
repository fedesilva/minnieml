package mml.mmlclib.api

import cats.effect.IO
import cats.effect.Sync
import cats.syntax.all.*
import mml.mmlclib.ast.Module
import mml.mmlclib.codegen.{LlvmIrPrinter, CodeGenError}

import java.nio.file.{Files, Path}

object CodeGenApi:
  /** Generate LLVM IR from a source string */
  def generateFromString(
    source: String,
    name:   Option[String] = "Anon".some
  ): IO[Either[String, String]] =
    for
      parsedModule <- ParserApi.parseModuleString(source, name)
      result <- parsedModule match
        case Right(module) =>
          IO.blocking(LlvmIrPrinter.printModule(module))
            .attempt
            .map {
              case Right(llvmIR) => Right(llvmIR)
              case Left(e: CodeGenError) => Left(e.getMessage)
              case Left(e) => Left(s"Unexpected error: ${e.getMessage}")
            }
        case Left(error) => IO.pure(Left(error))
    yield result

  /** Generate LLVM IR from a source file */
  def generateFromFile(
    path: Path
  ): IO[Either[String, String]] =
    for
      parsedModule <- ParserApi.parseModuleFile(path)
      result <- parsedModule match
        case Right(module) =>
          IO.blocking(LlvmIrPrinter.printModule(module))
            .attempt
            .map {
              case Right(llvmIR) => Right(llvmIR)
              case Left(e: CodeGenError) => Left(e.getMessage)
              case Left(e) => Left(s"Unexpected error: ${e.getMessage}")
            }
        case Left(error) => IO.pure(Left(error))
    yield result

  /** Generate LLVM IR and write it to a file */
  def generateToFile(
    sourcePath: Path,
    targetPath: Path
  ): IO[Either[String, Unit]] =
    for
      result <- generateFromFile(sourcePath)
      writeResult <- result match
        case Right(llvmIR) =>
          Sync[IO]
            .blocking(Files.writeString(targetPath, llvmIR))
            .attempt
            .map {
              case Right(_) => Right(())
              case Left(e) => Left(s"Failed to write output file: ${e.getMessage}")
            }
        case Left(error) => IO.pure(Left(error))
    yield writeResult

  /** Generate LLVM IR from a source string and write it to a file */
  def generateStringToFile(
    source:     String,
    name:       Option[String],
    targetPath: Path
  ): IO[Either[String, Unit]] =
    for
      result <- generateFromString(source, name)
      writeResult <- result match
        case Right(llvmIR) =>
          Sync[IO]
            .blocking(Files.writeString(targetPath, llvmIR))
            .attempt
            .map {
              case Right(_) => Right(())
              case Left(e) => Left(s"Failed to write output file: ${e.getMessage}")
            }
        case Left(error) => IO.pure(Left(error))
    yield writeResult

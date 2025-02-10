package mml.mmlclib.api

import cats.effect.IO
import cats.effect.Sync
import cats.syntax.all.*
import mml.mmlclib.ast.Module
import mml.mmlclib.interpreter.{Interpreter, Value, InterpretError}

import java.nio.file.Files
import java.nio.file.Path

object InterpreterApi:

  def interpretModuleString(
    source:     String,
    name:       Option[String] = "Anon".some,
    entryPoint: String         = "main"
  ): IO[Either[String, Value]] =
    val interpreter = new Interpreter()
    for
      parsedModule <- ParserApi.parseModuleString(source, name)
      result <- parsedModule match
        case Right(module) =>
          IO.blocking(interpreter.interpret(module, entryPoint))
            .attempt
            .map {
              case Right(value) => Right(value)
              case Left(e: InterpretError) => Left(e.getMessage)
              case Left(e) => Left(s"Unexpected error: ${e.getMessage}")
            }
        case Left(error) => IO.pure(Left(error))
    yield result

  def interpretModuleFile(
    path:       Path,
    entryPoint: String = "main"
  ): IO[Either[String, Value]] =
    val interpreter = new Interpreter()
    for
      parsedModule <- ParserApi.parseModuleFile(path)
      result <- parsedModule match
        case Right(module) =>
          IO.blocking(interpreter.interpret(module, entryPoint))
            .attempt
            .map {
              case Right(value) => Right(value)
              case Left(e: InterpretError) => Left(e.getMessage)
              case Left(e) => Left(s"Unexpected error: ${e.getMessage}")
            }
        case Left(error) => IO.pure(Left(error))
    yield result

  /** Helper method to get the string representation of a value */
  def valueToString(value: Value): String = value match
    case Value.IntV(v) => v.toString
    case Value.FloatV(v) => v.toString
    case Value.StringV(v) => v
    case Value.BoolV(v) => v.toString
    case Value.UnitV => "()"
    case Value.FunctionV(_, _, _) => "<function>"
    case Value.NativeFunctionV(name, _) => s"<native function: $name>"

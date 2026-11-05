package mml.mmlclib.lsp

import cats.effect.{IO, Resource}
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite
import org.typelevel.log4cats.Logger

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

class LspCommandTests extends BaseEffFunSuite:

  // A semantic failure confirms source loading before any command writes artifacts.
  private val source = "let value: Int = true;"
  private val commands = List(
    LspCommands.CompileBin,
    LspCommands.CompileLib,
    LspCommands.Ast,
    LspCommands.Ir
  )
  private val fileNames = List(
    "demo.mml",
    "demo file.mml",
    "demo#file.mml",
    "demo%file.mml",
    "demo+file.mml",
    "demo%20file.mml"
  )

  for
    command <- commands
    fileName <- fileNames
    useUri <- List(true, false)
  do
    val argumentKind = if useUri then "file URI" else "filesystem path"
    test(s"$command reads $fileName through its $argumentKind"):
      withSourceFixture(source, fileName) { (path, _) =>
        val argument = if useUri then path.toUri.toASCIIString else path.toString
        commandResponse(command, argument).map { result =>
          assertEquals(result("success").bool, false)
          assertEquals(result("message").str, "Type mismatch: expected Int, got Bool")
        }
      }

  test("AST command decodes a file URI with one slash"):
    withSourceFixture(source, "demo file.mml") { (path, _) =>
      val uri = s"file:${path.toUri.getRawPath}"
      commandResponse(LspCommands.Ast, uri).map { result =>
        assertEquals(result("success").bool, false)
        assertEquals(result("message").str, "Type mismatch: expected Int, got Bool")
      }
    }

  for command <- commands do
    test(s"$command reads a relative filesystem path beginning with file:"):
      // Relative filename arguments are resolved against the process working directory.
      Resource
        .make(IO.blocking(Files.createTempFile(Path.of("."), "file:lsp-command-", ".mml")))(path =>
          IO.blocking(Files.deleteIfExists(path)).void
        )
        .use { path =>
          for
            _ <- IO.blocking(Files.writeString(path, source))
            result <- commandResponse(command, path.getFileName.toString)
          yield
            assertEquals(result("success").bool, false)
            assertEquals(result("message").str, "Type mismatch: expected Int, got Bool")
        }

  /** Runs a command through the LSP lifecycle and returns its JSON-RPC result. */
  private def commandResponse(command: String, argument: String): IO[ujson.Value] =

    val messages = List(
      ujson.Obj("jsonrpc" -> "2.0", "id" -> 0, "method" -> "initialize"),
      ujson.Obj("jsonrpc" -> "2.0", "method" -> "initialized"),
      ujson.Obj(
        "jsonrpc" -> "2.0",
        "id" -> 1,
        "method" -> "workspace/executeCommand",
        "params" -> ujson.Obj("command" -> command, "arguments" -> ujson.Arr(argument))
      ),
      ujson.Obj("jsonrpc" -> "2.0", "id" -> 2, "method" -> "shutdown")
    )
    val requestBytes = messages.flatMap { message =>
      val body = ujson.write(message).getBytes(UTF_8)
      s"Content-Length: ${body.length}\r\n\r\n".getBytes(UTF_8) ++ body
    }.toArray

    // The LSP transport requires mutable streams; each command owns its in-memory transport.
    val streams = for
      input <- Resource.fromAutoCloseable(IO.blocking(new ByteArrayInputStream(requestBytes)))
      buffer <- Resource.fromAutoCloseable(IO.blocking(new ByteArrayOutputStream()))
      output <- Resource.fromAutoCloseable(IO.blocking(new PrintStream(buffer, true, UTF_8)))
    yield (input, buffer, output)

    streams.use { (input, buffer, output) =>
      for
        manager <- DocumentManager.create(CompilerConfig.default, silentLogger)
        _ <- LspHandler.create(manager, input, output, silentLogger).run
        responseBytes <- IO.blocking(buffer.toByteArray)
        result <- Resource
          .fromAutoCloseable(IO.blocking(new ByteArrayInputStream(responseBytes)))
          .use { responses =>
            for
              _ <- readResult(responses, 0)
              commandResult <- readResult(responses, 1)
              _ <- readResult(responses, 2)
            yield commandResult
          }
      yield result
    }

  /** Reads the next successful protocol response and verifies its request identity. */
  private def readResult(input: InputStream, expectedId: Int): IO[ujson.Value] =

    JsonRpc.readMessage(input).map {
      case Right(response: RpcResponse) =>
        assertEquals(response.id, ujson.Num(expectedId))
        assertEquals(response.error, None)
        response.result.getOrElse(fail("Expected a JSON-RPC result"))

      case other => fail(s"Expected a JSON-RPC response, got $other")
    }

  private val silentLogger: Logger[IO] = new Logger[IO]:

    def error(message: => String):                     IO[Unit] = IO.unit
    def warn(message:  => String):                     IO[Unit] = IO.unit
    def info(message:  => String):                     IO[Unit] = IO.unit
    def debug(message: => String):                     IO[Unit] = IO.unit
    def trace(message: => String):                     IO[Unit] = IO.unit
    def error(t:       Throwable)(message: => String): IO[Unit] = IO.unit
    def warn(t:        Throwable)(message: => String): IO[Unit] = IO.unit
    def info(t:        Throwable)(message: => String): IO[Unit] = IO.unit
    def debug(t:       Throwable)(message: => String): IO[Unit] = IO.unit
    def trace(t:       Throwable)(message: => String): IO[Unit] = IO.unit

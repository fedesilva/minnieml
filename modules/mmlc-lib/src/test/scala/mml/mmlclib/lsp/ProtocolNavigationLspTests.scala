package mml.mmlclib.lsp

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite
import org.typelevel.log4cats.Logger

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

class ProtocolNavigationLspTests extends BaseEffFunSuite:

  private val uri    = "file:///protocol-navigation.mml"
  private val source = """protocol Inspect 'T =
  fn inspect(value: 'T): Int;
  op %%(left: 'T, right: 'T): 'T 60 left;
;
instance Inspect Int =
  fn inspect(value) = value;;
  op %%(left, right) = left;;
;
instance Inspect Bool =
  fn inspect(value) = 0;;
  op %%(left, right) = right;;
;
let integer = inspect 1;
let boolean = inspect true;
let combined = 1 %% 2;"""

  /** Expected locations use LSP's zero-based coordinates and the source document URI. */
  private def location(line: Int, col: Int, length: Int): ujson.Value =
    ujson.Obj(
      "uri" -> uri,
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> line, "character" -> col),
        "end" -> ujson.Obj("line" -> line, "character" -> (col + length))
      )
    )

  private def request(
    id:          Int,
    method:      String,
    line:        Int,
    col:         Int,
    documentUri: String = uri
  ): ujson.Obj =
    ujson.Obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "method" -> method,
      "params" -> ujson.Obj(
        "textDocument" -> ujson.Obj("uri" -> documentUri),
        "position" -> ujson.Obj("line" -> line, "character" -> col),
        "context" -> ujson.Obj("includeDeclaration" -> false)
      )
    )

  test("initialize advertises navigation and all three requests return exact protocol locations"):
    val requests = List(
      request(1, "textDocument/definition", 12, 14),
      request(2, "textDocument/implementation", 0, 9),
      request(3, "textDocument/implementation", 1, 5),
      request(4, "textDocument/implementation", 2, 5),
      request(5, "textDocument/references", 4, 9),
      request(6, "textDocument/references", 5, 5),
      request(7, "textDocument/definition", 14, 17)
    )

    exchange(requests).map { responses =>
      val capabilities = result(responses.head)("capabilities")
      List("definitionProvider", "implementationProvider", "referencesProvider").foreach { key =>
        assertEquals(capabilities(key).bool, true)
      }
      val results = responses.tail.init.map(result)
      assertEquals(
        results,
        List(
          ujson.Arr(location(1, 5, 7)),
          ujson.Arr(location(4, 9, 7), location(8, 9, 7)),
          ujson.Arr(location(5, 5, 7), location(9, 5, 7)),
          ujson.Arr(location(6, 5, 2), location(10, 5, 2)),
          ujson.Arr(location(12, 14, 7), location(14, 17, 2)),
          ujson.Arr(location(12, 14, 7)),
          ujson.Arr(location(2, 5, 2))
        )
      )
    }

  test("implementation requests for absent documents or ordinary syntax have no destinations"):
    val absent   = request(1, "textDocument/implementation", 0, 9, "file:///absent.mml")
    val requests = List(absent, request(2, "textDocument/implementation", 12, 23))

    exchange(requests).map { responses =>
      assertEquals(responses.tail.init.map(result), List(ujson.Null, ujson.Null))
    }

  test("navigation accepts Zed selections at the end of returned destination names"):
    val namedSignature         = location(1, 5, 7)
    val operatorSignature      = location(2, 5, 2)
    val protocolDeclaration    = location(0, 9, 7)
    val namedImplementation    = location(5, 5, 7)
    val operatorImplementation = location(6, 5, 2)
    val instanceHeader         = location(4, 9, 7)

    def atEnd(id: Int, method: String, destination: ujson.Value): ujson.Obj =
      val end = destination("range")("end")
      request(id, method, end("line").num.toInt, end("character").num.toInt)

    val requests = List(
      request(1, "textDocument/definition", 12, 14),
      atEnd(2, "textDocument/implementation", namedSignature),
      atEnd(3, "textDocument/references", namedImplementation),
      request(4, "textDocument/definition", 14, 17),
      atEnd(5, "textDocument/implementation", operatorSignature),
      atEnd(6, "textDocument/references", operatorImplementation),
      request(7, "textDocument/definition", 4, 9),
      atEnd(8, "textDocument/implementation", protocolDeclaration),
      atEnd(9, "textDocument/references", instanceHeader)
    )

    exchange(requests).map { responses =>
      assertEquals(
        responses.tail.init.map(result),
        List(
          ujson.Arr(namedSignature),
          ujson.Arr(namedImplementation, location(9, 5, 7)),
          ujson.Arr(location(12, 14, 7)),
          ujson.Arr(operatorSignature),
          ujson.Arr(operatorImplementation, location(10, 5, 2)),
          ujson.Arr(location(14, 17, 2)),
          ujson.Arr(protocolDeclaration),
          ujson.Arr(instanceHeader, location(8, 9, 7)),
          ujson.Arr(location(12, 14, 7), location(14, 17, 2))
        )
      )
    }

  test("implementation requests require parameters"):
    val missing =
      ujson.Obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> "textDocument/implementation")

    exchange(List(missing)).map { responses =>
      assertEquals(responses(1).error.map(_.code), Some(RpcError.InvalidParams))
      assertEquals(responses(1).result, None)
    }

  private def result(response: RpcResponse): ujson.Value =
    assertEquals(response.error, None)
    response.result.getOrElse(fail("Expected successful JSON-RPC result"))

  /** Runs initialization, source loading, navigation requests, and shutdown over framed messages.
    */
  private def exchange(requests: List[ujson.Obj]): IO[List[RpcResponse]] =
    val initialize  = ujson.Obj("jsonrpc" -> "2.0", "id" -> 0, "method" -> "initialize")
    val initialized = ujson.Obj("jsonrpc" -> "2.0", "method" -> "initialized")
    val open = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method" -> "textDocument/didOpen",
      "params" -> ujson.Obj(
        "textDocument" -> ujson.Obj(
          "uri" -> uri,
          "languageId" -> "mml",
          "version" -> 1,
          "text" -> source
        )
      )
    )
    val shutdown = ujson.Obj("jsonrpc" -> "2.0", "id" -> 999, "method" -> "shutdown")
    val messages = List(initialize, initialized, open) ++ requests :+ shutdown
    val requestBytes = messages.flatMap { message =>
      val body = ujson.write(message).getBytes(UTF_8)
      s"Content-Length: ${body.length}\r\n\r\n".getBytes(UTF_8) ++ body
    }.toArray

    // Framed JSON-RPC uses mutable streams, scoped to one in-memory client connection.
    val streams = for
      input <- Resource.fromAutoCloseable(IO.blocking(new ByteArrayInputStream(requestBytes)))
      buffer <- Resource.fromAutoCloseable(IO.blocking(new ByteArrayOutputStream()))
      output <- Resource.fromAutoCloseable(IO.blocking(new PrintStream(buffer, true, UTF_8)))
    yield (input, buffer, output)

    streams.use { (input, buffer, output) =>
      for
        manager <- DocumentManager.create(CompilerConfig.default, silentLogger)
        _ <- LspHandler.create(manager, input, output, silentLogger).run
        bytes <- IO.blocking(buffer.toByteArray)
        responses <- Resource
          .fromAutoCloseable(IO.blocking(new ByteArrayInputStream(bytes)))
          .use { input =>
            (List(0) ++ requests.map(_("id").num.toInt) :+ 999).traverse { id =>
              readResponse(input).map { response =>
                assertEquals(response.id, ujson.Num(id))
                response
              }
            }
          }
      yield responses
    }

  private def readResponse(input: InputStream): IO[RpcResponse] =
    JsonRpc.readMessage(input).flatMap {
      case Right(response: RpcResponse) => IO.pure(response)
      case Right(notification: RpcNotification) =>
        assertEquals(notification.method, "textDocument/publishDiagnostics")
        readResponse(input)
      case other => fail(s"Expected response or diagnostics, got $other")
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

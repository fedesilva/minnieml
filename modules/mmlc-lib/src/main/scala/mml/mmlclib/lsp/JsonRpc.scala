package mml.mmlclib.lsp

import cats.effect.IO

import java.io.{InputStream, PrintStream}
import java.nio.charset.StandardCharsets

/** JSON-RPC message types for LSP communication. */
sealed trait RpcMessage

case class RpcRequest(
  jsonrpc: String,
  id:      ujson.Value,
  method:  String,
  params:  Option[ujson.Value]
) extends RpcMessage

case class RpcNotification(
  jsonrpc: String,
  method:  String,
  params:  Option[ujson.Value]
) extends RpcMessage

case class RpcResponse(
  jsonrpc: String,
  id:      ujson.Value,
  result:  Option[ujson.Value],
  error:   Option[RpcError]
) extends RpcMessage

case class RpcError(
  code:    Int,
  message: String,
  data:    Option[ujson.Value] = None
)

object RpcError:
  val ParseError:           Int = -32700
  val InvalidRequest:       Int = -32600
  val MethodNotFound:       Int = -32601
  val InvalidParams:        Int = -32602
  val InternalError:        Int = -32603
  val ServerNotInitialized: Int = -32002

object JsonRpc:

  /** Reads a single LSP message from the input stream. Content-Length is in bytes, so we read raw
    * bytes for the body to avoid byte/char mismatch with multi-byte UTF-8.
    */
  def readMessage(input: InputStream): IO[Either[String, RpcMessage]] =
    IO.blocking {
      readHeaders(input).flatMap { headers =>
        headers
          .find(_.toLowerCase.startsWith("content-length:"))
          .map(h => h.substring(h.indexOf(':') + 1).trim)
          .toRight(s"Missing Content-Length (headers: ${headers.mkString(", ")})") match
          case Left(err) => Left(err)
          case Right(lenStr) =>
            try
              val length = lenStr.toInt
              readContentBytes(input, length)
            catch case _: NumberFormatException => Left(s"Invalid Content-Length: $lenStr")
      }
    }.map(_.flatMap { content =>
      parseMessage(content).left.map { err =>
        val preview = if content.length > 200 then content.take(200) + "..." else content
        s"$err (body=${preview})"
      }
    })

  /** Read header lines until empty line (\\r\\n\\r\\n). Returns list of header strings. */
  private def readHeaders(input: InputStream): Either[String, List[String]] =
    val headers = List.newBuilder[String]
    val line    = new StringBuilder
    var prev    = 0
    var done    = false

    while !done do
      val b = input.read()
      if b == -1 then return Left("Connection closed while reading headers")
      else if b == '\n' && prev == '\r' then
        val headerLine = line.toString.stripSuffix("\r")
        if headerLine.isEmpty then done = true
        else
          headers += headerLine
          line.clear()
      else line.append(b.toChar)
      prev = b

    Right(headers.result())

  /** Read exactly `length` bytes and decode as UTF-8. */
  private def readContentBytes(input: InputStream, length: Int): Either[String, String] =
    val buffer = new Array[Byte](length)
    var read   = 0
    while read < length do
      val n = input.read(buffer, read, length - read)
      if n == -1 then return Left(s"Unexpected end of stream (read $read of $length bytes)")
      read += n
    Right(new String(buffer, StandardCharsets.UTF_8))

  private def parseMessage(content: String): Either[String, RpcMessage] =
    try
      val json = ujson.read(content)
      val obj  = json.obj

      val jsonrpc = obj.get("jsonrpc").map(_.str).getOrElse("2.0")
      val id      = obj.get("id")
      val method  = obj.get("method").map(_.str)
      val params  = obj.get("params")

      (id, method) match
        case (Some(id), Some(m)) =>
          Right(RpcRequest(jsonrpc, id, m, params))
        case (None, Some(m)) =>
          Right(RpcNotification(jsonrpc, m, params))
        case (Some(id), None) =>
          val result = obj.get("result")
          val error = obj.get("error").map { e =>
            val eObj = e.obj
            RpcError(
              code    = eObj("code").num.toInt,
              message = eObj("message").str,
              data    = eObj.get("data")
            )
          }
          Right(RpcResponse(jsonrpc, id, result, error))
        case _ =>
          Left("Invalid JSON-RPC message: missing method or id")
    catch case e: Exception => Left(s"JSON parse error: ${e.getMessage}")

  /** Writes a JSON-RPC response to the output stream. */
  def writeResponse(out: PrintStream, id: ujson.Value, result: ujson.Value): IO[Unit] =
    IO.blocking {
      val response = ujson.Obj(
        "jsonrpc" -> "2.0",
        "id" -> id,
        "result" -> result
      )
      writeMessage(out, response)
    }

  /** Writes a JSON-RPC error response to the output stream. */
  def writeError(out: PrintStream, id: ujson.Value, error: RpcError): IO[Unit] =
    IO.blocking {
      val errorObj = ujson.Obj(
        "code" -> error.code,
        "message" -> error.message
      )
      error.data.foreach(d => errorObj("data") = d)

      val response = ujson.Obj(
        "jsonrpc" -> "2.0",
        "id" -> id,
        "error" -> errorObj
      )
      writeMessage(out, response)
    }

  /** Writes a JSON-RPC notification to the output stream. */
  def writeNotification(out: PrintStream, method: String, params: ujson.Value): IO[Unit] =
    IO.blocking {
      val notification = ujson.Obj(
        "jsonrpc" -> "2.0",
        "method" -> method,
        "params" -> params
      )
      writeMessage(out, notification)
    }

  private def writeMessage(out: PrintStream, json: ujson.Value): Unit =
    val content = ujson.write(json)
    val bytes   = content.getBytes(StandardCharsets.UTF_8)
    out.print(s"Content-Length: ${bytes.length}\r\n\r\n")
    out.print(content)
    out.flush()

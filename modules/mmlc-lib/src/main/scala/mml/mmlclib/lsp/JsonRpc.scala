package mml.mmlclib.lsp

import cats.effect.IO
import java.io.{BufferedReader, PrintStream}
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

  private val ContentLengthHeader = "Content-Length: "

  /** Reads a single LSP message from the input stream. Blocks until a complete message is
    * available.
    */
  def readMessage(reader: BufferedReader): IO[Either[String, RpcMessage]] =
    IO.blocking {
      readContentLength(reader).flatMap { length =>
        skipHeaders(reader)
        readContent(reader, length)
      }
    }.map(_.flatMap(parseMessage))

  private def readContentLength(reader: BufferedReader): Either[String, Int] =
    val line = reader.readLine()
    if line == null then Left("Connection closed")
    else if line.startsWith(ContentLengthHeader) then
      try Right(line.substring(ContentLengthHeader.length).trim.toInt)
      catch case _: NumberFormatException => Left(s"Invalid Content-Length: $line")
    else Left(s"Expected Content-Length header, got: $line")

  private def skipHeaders(reader: BufferedReader): Unit =
    var line = reader.readLine()
    while line != null && line.nonEmpty do line = reader.readLine()

  private def readContent(reader: BufferedReader, length: Int): Either[String, String] =
    val buffer = new Array[Char](length)
    var read   = 0
    while read < length do
      val n = reader.read(buffer, read, length - read)
      if n == -1 then return Left("Unexpected end of stream")
      read += n
    Right(new String(buffer))

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

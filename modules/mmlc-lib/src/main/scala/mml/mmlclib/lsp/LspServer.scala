package mml.mmlclib.lsp

import cats.effect.{ExitCode, IO}
import mml.mmlclib.compiler.CompilerConfig

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets

/** LSP server entry point. Reads from stdin, writes to stdout. */
object LspServer:

  /** Run the LSP server on stdio. */
  def run(config: CompilerConfig): IO[ExitCode] =
    for
      documentManager <- DocumentManager.create(config)
      reader  = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
      output  = new PrintStream(System.out, false, StandardCharsets.UTF_8)
      handler = LspHandler.create(documentManager, reader, output)
      _ <- handler.run
    yield ExitCode.Success

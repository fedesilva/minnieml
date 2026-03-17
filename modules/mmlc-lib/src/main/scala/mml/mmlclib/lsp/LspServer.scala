package mml.mmlclib.lsp

import cats.effect.{ExitCode, IO}
import mml.mmlclib.compiler.CompilerConfig

import java.io.{BufferedInputStream, PrintStream}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/** LSP server entry point. Reads from stdin, writes to stdout. */
object LspServer:

  /** Run the LSP server on stdio. */
  def run(config: CompilerConfig): IO[ExitCode] =
    for
      logger <- LspLogging.create(config.outputDir)
      _ <- logger.info("LSP server starting")
      documentManager <- DocumentManager.create(config, logger)
      input   = new BufferedInputStream(System.in)
      output  = new PrintStream(System.out, false, StandardCharsets.UTF_8)
      handler = LspHandler.create(documentManager, input, output, logger)
      _ <- heartbeat.background.use(_ => handler.run)
      _ <- logger.info("LSP server exiting")
    yield ExitCode.Success

  /** Periodic tick to prevent cats-effect starvation warnings while idle. */
  private def heartbeat: IO[Nothing] =
    (IO.sleep(50.millis) *> IO.cede).foreverM

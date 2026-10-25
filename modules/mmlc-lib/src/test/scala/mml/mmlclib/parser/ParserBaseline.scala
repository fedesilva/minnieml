package mml.mmlclib.parser

import cats.data.EitherT
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** Measures warmed, uninstrumented parser batches and reports instrumentation separately. */
object ParserBaseline extends IOApp:

  private case class Settings(
    root:       String,
    warmups:    Int,
    samples:    Int,
    iterations: Int,
    paths:      List[String]
  )

  private case class Input(
    path:   String,
    name:   String,
    source: String,
    bytes:  Int,
    sha256: String
  )

  private case class Batch(result: ParserResult, checksum: Long)

  private val usage =
    "ParserBaseline <repo-root> <warmup-batches> <sample-batches> " +
      "<iterations-per-batch> <relative-source>..."

  private def settings(args: List[String]): Either[String, Settings] =

    def positive(value: String, label: String): Either[String, Int] =
      value.toIntOption.filter(_ > 0).toRight(s"$label must be a positive integer")

    args match
      case root :: warmups :: samples :: iterations :: paths if paths.nonEmpty =>
        for
          warmupCount <- positive(warmups, "warmup-batches")
          sampleCount <- positive(samples, "sample-batches")
          iterationCount <- positive(iterations, "iterations-per-batch")
        yield Settings(root, warmupCount, sampleCount, iterationCount, paths)

      case _ => usage.asLeft

  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def load(settings: Settings, path: String): IO[Either[String, Input]] =
    IO.blocking {
      val bytes = Files.readAllBytes(Path.of(settings.root).resolve(path))
      val name  = Path.of(path).getFileName.toString.stripSuffix(".mml")
      Input(path, name, new String(bytes, UTF_8), bytes.length, digest(bytes))
    }.attempt
      .map(_.leftMap(error => s"Cannot read $path: ${error.getMessage}"))

  private def parse(input: Input): (SourceInfo, ParserResult) =
    Parser.parseModuleWithInfo(input.source, input.name, input.path.some)

  private def recoveredErrors(info: SourceInfo, result: ParserResult): Int =
    result.fold(
      _ => 0,
      module =>
        ParsingErrorChecker
          .checkModule(CompilerState.empty(module, info, CompilerConfig.default))
          .errors
          .size
    )

  private def consume(result: ParserResult): Long =
    result.fold(
      error => error.message.length.toLong,
      module => module.members.size.toLong + module.spanOpt.fold(0L)(_.end.index.toLong)
    )

  private def batch(input: Input, iterations: Int): Batch =

    @tailrec
    def loop(remaining: Int, result: ParserResult, checksum: Long): Batch =
      if remaining == 0 then Batch(result, checksum)
      else
        val next = parse(input)._2
        loop(remaining - 1, next, checksum + consume(next))

    val first = parse(input)._2
    loop(iterations - 1, first, consume(first))

  private def checkBatch(
    input:      Input,
    settings:   Settings,
    reference:  ParserResult,
    batchValue: Batch
  ): Either[String, Unit] =
    val expectedChecksum = consume(reference) * settings.iterations.toLong

    Either.cond(
      batchValue.result.equals(reference) && batchValue.checksum == expectedChecksum,
      (),
      s"Parse result changed within the run: ${input.path}"
    )

  private def measure(input: Input, settings: Settings): EitherT[IO, String, Unit] =
    for
      reference <- EitherT.liftF(IO.delay(parse(input)))
      (info, result) = reference
      instrumented <- EitherT.liftF(IO.delay {
        Parser.parseModuleInstrumented(input.source, input.name, input.path.some)
      })
      (_, instrumentedResult, collector) = instrumented
      _ <- EitherT.cond[IO](
        result.equals(instrumentedResult),
        (),
        s"Instrumented and uninstrumented results differ: ${input.path}"
      )
      _ <- EitherT.liftF(
        IO.println(
          s"input=${input.path} bytes=${input.bytes} chars=${input.source.length} " +
            s"sha256=${input.sha256} outcome=${if result.isRight then "module" else "failure"} " +
            s"recovered_errors=${recoveredErrors(info, result)} " +
            s"raw_ast_sha256=${digest(result.toString.getBytes(UTF_8))}"
        )
      )
      _ <- (1 to settings.warmups).toList.traverse_ { _ =>
        EitherT(IO.delay(batch(input, settings.iterations)).map {
          checkBatch(input, settings, result, _)
        })
      }
      samples <- (1 to settings.samples).toList.traverse { index =>
        for
          measured <- EitherT.liftF(IO.delay(batch(input, settings.iterations)).timed)
          (elapsed, batchValue) = measured
          _ <- EitherT.fromEither[IO](checkBatch(input, settings, result, batchValue))
        yield s"sample=$index input=${input.path} elapsed_ns=${elapsed.toNanos} " +
          s"iterations=${settings.iterations} checksum=${batchValue.checksum}"
      }
      _ <- EitherT.liftF(samples.traverse_(IO.println))
      _ <- EitherT.liftF(collector.toCounters("parser").sortBy(_.name).traverse_ { counter =>
        IO.println(s"counter input=${input.path} name=${counter.name} value=${counter.value}")
      })
    yield ()

  private def environment(settings: Settings): IO[Unit] =
    IO.delay {
      val runtime = Runtime.getRuntime
      val jvmArgs = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.mkString(" ")
      val properties = List("java.version", "java.vm.name", "os.name", "os.version", "os.arch")
        .map(key => s"$key=${System.getProperty(key)}")
        .mkString(" ")

      s"environment $properties processors=${runtime.availableProcessors()} " +
        s"max_heap_bytes=${runtime.maxMemory()} pid=${ProcessHandle.current().pid()} " +
        s"jvm_args=$jvmArgs\n" +
        s"settings root=${settings.root} warmup_batches=${settings.warmups} " +
        s"sample_batches=${settings.samples} iterations=${settings.iterations}"
    }.flatMap(IO.println)

  def run(args: List[String]): IO[ExitCode] =
    val measurement = for
      config <- EitherT.fromEither[IO](settings(args))
      inputs <- config.paths.traverse(path => EitherT(load(config, path)))
      _ <- EitherT.liftF(environment(config))
      _ <- inputs.traverse_(measure(_, config))
    yield ()

    measurement.value.flatMap {
      case Left(error) => IO.println(error).as(ExitCode.Error)
      case Right(_) => IO.pure(ExitCode.Success)
    }

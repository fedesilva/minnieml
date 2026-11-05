package mml.mmlclib.parser

import cats.effect.{ExitCode, IO, IOApp}
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.util.error.print.diagnosticMessage
import upickle.default.{ReadWriter, write}

/** Child entry point for parser outcomes and the deadline harness's blocking control. */
object ParserDeadlineProbe extends IOApp:

  /** The missing grammar item reported by a truncated conditional. Token text crosses the child
    * process boundary so the parent can distinguish a missing keyword from a missing terminator.
    */
  enum MissingSyntax derives ReadWriter:
    case Expression
    case Token(value: String)

  enum Structure derives ReadWriter:
    case Binding(value: List[Structure])
    case Conditional(condition: List[Structure], ifTrue: List[Structure], ifFalse: List[Structure])
    case Bool(value: Boolean)
    case Integer(value: Long)
    case UnitValue(location: Option[(Int, Int)])
    case ParseError(
      location:   Option[(Int, Int)],
      reason:     MissingSyntax,
      failedCode: Option[String]
    )
    case Unexpected

  case class ConditionalOutcome(members: List[Structure], diagnosticCount: Int) derives ReadWriter

  /** Expression wrappers are transparent; conditional ownership and errors stay structural. */
  private def structure(term: Term): List[Structure] =
    val location = term.spanOpt.map(span => (span.start.index, span.end.index))
    term match
      case expression: Expr => expression.terms.flatMap(structure)

      case conditional: Cond =>
        List(
          Structure.Conditional(
            structure(conditional.cond),
            structure(conditional.ifTrue),
            structure(conditional.ifFalse)
          )
        )

      case error: ParseTermError =>
        val summary = error.reason match
          case SyntaxError.ExpectedExpression =>
            Structure.ParseError(location, MissingSyntax.Expression, error.failedCode)
          case SyntaxError.ExpectedToken(token) =>
            Structure.ParseError(location, MissingSyntax.Token(token), error.failedCode)
          case _ => Structure.Unexpected
        List(summary)

      case literal: LiteralBool => List(Structure.Bool(literal.value))
      case literal: LiteralInt => List(Structure.Integer(literal.value))
      case _:       LiteralUnit => List(Structure.UnitValue(location))
      case _ => List(Structure.Unexpected)

  private def parse(source: String, conditionalSummary: Boolean = false): IO[ExitCode] =
    IO.delay {
      val (info, result) = Parser.parseModuleWithInfo(source, "DeadlineProbe")

      result match
        case Left(error) => (11, s"fatal parse failure: ${diagnosticMessage(error)}")

        case Right(module) =>
          val state = ParsingErrorChecker.checkModule(
            CompilerState.empty(module, info, CompilerConfig.default)
          )
          val code = if state.errors.isEmpty then 0 else 10
          val message =
            if conditionalSummary then
              val members = module.members.map {
                case binding: Bnd => Structure.Binding(structure(binding.value))
                case _ => Structure.Unexpected
              }
              write(ConditionalOutcome(members, state.errors.size))
            else if state.errors.isEmpty then "clean module"
            else s"recovered parse errors: ${state.errors.size}"
          (code, message)
    }.flatMap { case (code, message) => IO.println(message).as(ExitCode(code)) }

  private def scan: IO[ExitCode] =
    IO.delay {
      val depth    = 20000
      val source   = "(" * depth + "/* nested /* ; */ ; */x" * depth + ")" * depth + "; next"
      val anchor   = Recovery.Anchor.Token(";")
      val actual   = Recovery.scan(source, 0, Recovery.Context(List(anchor)))
      val expected = Recovery.Result(source.lastIndexOf(';'), Recovery.Boundary.AtAnchor(anchor))

      if actual.equals(expected) then (0, "scan reached the exact outer anchor")
      else (12, s"scan mismatch: expected $expected, found $actual")
    }.flatMap { case (code, message) => IO.println(message).as(ExitCode(code)) }

  def run(args: List[String]): IO[ExitCode] =
    args match
      case "parse" :: source :: Nil => parse(source)
      case "conditional" :: source :: Nil => parse(source, conditionalSummary = true)
      case "scan" :: Nil => scan
      case "wait" :: Nil => IO.println("deadline probe ready").flatMap(_ => IO.never[ExitCode])
      case _ =>
        IO.println("Expected parse <source>, conditional <source>, scan, or wait").as(ExitCode(2))

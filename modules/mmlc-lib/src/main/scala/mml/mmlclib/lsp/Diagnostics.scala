package mml.mmlclib.lsp

import mml.mmlclib.api.CompilerError
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.LlvmCompilationError
import mml.mmlclib.codegen.emitter.CodeGenError
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.CompilationError
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.{SemanticError, TypeError}
import mml.mmlclib.util.error.print.{
  diagnosticMessage,
  entryPointMessage,
  expressionMessage,
  typeErrorMessage
}

/** Converts source-bearing semantic and type errors to LSP diagnostics. */
object Diagnostics:

  /** Extract diagnostics from compiler state. */
  def fromCompilerState(state: CompilerState): List[Diagnostic] =
    state.visibleErrors.toList.flatMap(fromErrorDiagnostics)

  /** Convert a single compilation error to a diagnostic. */
  def fromError(error: CompilationError): Option[Diagnostic] =
    fromErrorDiagnostics(error).headOption

  /** Convert a single compilation error to all related diagnostics. */
  def fromErrorAll(error: CompilationError): List[Diagnostic] =
    fromErrorDiagnostics(error)

  private def fromErrorDiagnostics(error: CompilationError): List[Diagnostic] =
    error match
      case semantic: SemanticError =>
        fromSpans(extractSemanticErrorSpans(semantic), formatSemanticError(semantic))
      case typeError: TypeError =>
        fromSpans(extractTypeErrorSpans(typeError), formatTypeError(typeError))

      case ParserError.Failure(_) => Nil
      case ParserError.Unknown(_) => Nil
      case _: CompilerError => Nil
      case _: CodeGenError => Nil
      case _: LlvmCompilationError => Nil
      // CompilationError is open; other families have no editor conversion contract.
      case _ => Nil

  /** Render messages only for source spans that can be shown in the editor. */
  private def fromSpans(spans: List[SrcSpan], message: => String): List[Diagnostic] =
    spans.map { span =>
      Diagnostic(
        range    = Range.fromSrcSpan(span),
        severity = DiagnosticSeverity.Error,
        message  = message
      )
    }

  private def extractSemanticErrorSpans(error: SemanticError): List[SrcSpan] =
    error match
      case SemanticError.UndefinedRef(ref, _, _) => ref.spanOpt.toList
      case SemanticError.UndefinedTypeRef(typeRef, _, _) => typeRef.spanOpt.toList
      case SemanticError.AmbiguousTypeRef(typeRef, _, _, _) => typeRef.spanOpt.toList
      case SemanticError.DuplicateName(_, dups, _, _) =>
        dups.iterator
          .collect { case fs: FromSource => fs.source.spanOpt }
          .collect { case Some(span) => span }
          .toList
      case SemanticError.InvalidExpression(expr, _, _) => expr.spanOpt.toList
      case SemanticError.DanglingTerms(terms, _, _) => terms.headOption.toList.flatMap(_.spanOpt)
      case SemanticError.MemberErrorFound(err, _) => err.spanOpt.toList
      case SemanticError.ParsingIdErrorFound(err, _) => err.spanOpt.toList
      case SemanticError.ParseTermErrorFound(err, _) => err.spanOpt.toList
      case SemanticError.ParseTypeErrorFound(err, _) => err.spanOpt.toList
      case SemanticError.InvalidExpressionFound(inv, _) => inv.spanOpt.toList
      case SemanticError.TypeCheckingError(typeErr) => extractTypeErrorSpans(typeErr)
      case SemanticError.ProtocolErrorFound(error, _) => error.source.spanOpt.toList
      case SemanticError.InvalidEntryPoint(error) => error.source.spanOpt.toList
      case SemanticError.VisibilityViolation(ref, _, _) => ref.spanOpt.toList
      case error: SemanticError.UseAfterMove => error.source.spanOpt.toList
      case SemanticError.ConsumingParamNotLastUse(_, ref, _) => ref.spanOpt.toList
      case SemanticError.BorrowedValuePassedToConsumingParam(_, ref, _) => ref.spanOpt.toList
      case SemanticError.PartialApplicationWithConsuming(fn, _, _) => fn.spanOpt.toList
      case SemanticError.ConditionalOwnershipMismatch(cond, _) => cond.spanOpt.toList
      case SemanticError.BorrowEscapeViaReturn(ref, _) => ref.spanOpt.toList
      case SemanticError.CapturedBorrowedHeapBinding(ref, _) => ref.spanOpt.toList
      case SemanticError.CapturedMovedHeapBinding(ref, _, _) => ref.spanOpt.toList
      case SemanticError.BorrowClosureEscapeViaReturn(lambda, _) =>
        lambda.spanOpt.toList

  private def extractTypeErrorSpans(error: TypeError): List[SrcSpan] =
    error match
      case TypeError.MissingParameterType(param, _, _) => param.spanOpt.toList
      case TypeError.MissingReturnType(decl, _) => declSpan(decl).toList
      case TypeError.RecursiveFunctionMissingReturnType(d, _) => declSpan(d).toList
      case TypeError.MissingOperatorParameterType(param, _, _) => param.spanOpt.toList
      case TypeError.MissingOperatorReturnType(decl, _) => declSpan(decl).toList
      case TypeError.TypeMismatch(node, _, _, _, _) => nodeSpan(node).toList
      case TypeError.UndersaturatedApplication(app, _, _, _) => app.spanOpt.toList
      case TypeError.OversaturatedApplication(app, _, _, _) => app.spanOpt.toList
      case TypeError.InvalidApplication(app, _, _, _) => app.spanOpt.toList
      case TypeError.InvalidSelection(ref, _, _) => ref.spanOpt.toList
      case TypeError.UnknownField(ref, _, _) => ref.spanOpt.toList
      case TypeError.ConditionalBranchTypeMismatch(c, _, _, _) => c.spanOpt.toList
      case TypeError.ConditionalBranchTypeUnknown(cond, _) => cond.spanOpt.toList
      case TypeError.UnresolvableType(node, _, _) => nodeSpan(node).toList
      case TypeError.IncompatibleTypes(node, _, _, _) => astNodeSpan(node).toList
      case TypeError.ConflictingLambdaParamInference(param, _, _, _) => param.spanOpt.toList
      case TypeError.UninferrableLambdaParam(param, _) => param.spanOpt.toList
      case TypeError.UntypedHoleInBinding(_, source, _) => source.spanOpt.toList

  private def declSpan(decl: Decl): Option[SrcSpan] =
    decl.spanOpt

  private def nodeSpan(node: Typeable): Option[SrcSpan] =
    node match
      case fs: FromSource => fs.source.spanOpt
      case _ => None

  private def astNodeSpan(node: AstNode): Option[SrcSpan] =
    node match
      case fs: FromSource => fs.source.spanOpt
      case _ => None

  private def formatSemanticError(error: SemanticError): String = error match
    case SemanticError.InvalidExpression(_, reason, _) =>
      s"Invalid expression: ${expressionMessage(reason)}"
    case SemanticError.DanglingTerms(_, reason, _) =>
      s"Dangling terms: ${expressionMessage(reason)}"
    case SemanticError.MemberErrorFound(error, _) => s"Member error: ${diagnosticMessage(error)}"
    case SemanticError.ParsingIdErrorFound(error, _) =>
      s"Parsing error: ${diagnosticMessage(error)}"
    case SemanticError.ParseTermErrorFound(error, _) =>
      s"Parsing error: ${diagnosticMessage(error)}"
    case SemanticError.InvalidEntryPoint(error) =>
      s"Invalid entry point: ${entryPointMessage(error)}"
    case other => diagnosticMessage(other)

  private def formatTypeError(error: TypeError): String = typeErrorMessage(error)

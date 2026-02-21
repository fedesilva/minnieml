package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.CompilationError
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.{SemanticError, TypeError}

/** Converts compiler errors to LSP diagnostics. */
object Diagnostics:

  /** Extract diagnostics from compiler state. */
  def fromCompilerState(state: CompilerState): List[Diagnostic] =
    state.errors.toList.flatMap(fromErrorDiagnostics)

  /** Convert a single compilation error to a diagnostic. */
  def fromError(error: CompilationError): Option[Diagnostic] =
    fromErrorDiagnostics(error).headOption

  /** Convert a single compilation error to all related diagnostics. */
  def fromErrorAll(error: CompilationError): List[Diagnostic] =
    fromErrorDiagnostics(error)

  private def fromErrorDiagnostics(error: CompilationError): List[Diagnostic] =
    extractSpans(error).map { span =>
      Diagnostic(
        range    = Range.fromSrcSpan(span),
        severity = DiagnosticSeverity.Error,
        message  = formatErrorMessage(error)
      )
    }

  private def extractSpans(error: CompilationError): List[SrcSpan] =
    error match
      case ParserError.Failure(_) => Nil
      case ParserError.Unknown(_) => Nil

      case SemanticError.UndefinedRef(ref, _, _) => List(ref.span)
      case SemanticError.UndefinedTypeRef(typeRef, _, _) => List(typeRef.span)
      case SemanticError.DuplicateName(_, dups, _) =>
        dups.iterator
          .collect { case fs: FromSource => fs.source.spanOpt }
          .collect { case Some(span) => span }
          .toList
      case SemanticError.InvalidExpression(expr, _, _) => List(expr.span)
      case SemanticError.DanglingTerms(terms, _, _) => terms.headOption.toList.map(_.span)
      case SemanticError.MemberErrorFound(err, _) => List(err.span)
      case SemanticError.ParsingIdErrorFound(err, _) => List(err.span)
      case SemanticError.InvalidExpressionFound(inv, _) => List(inv.span)
      case SemanticError.TypeCheckingError(typeErr) => extractTypeErrorSpans(typeErr)
      case SemanticError.InvalidEntryPoint(_, span) => List(span)
      case SemanticError.UseAfterMove(ref, _, _) => List(ref.span)
      case SemanticError.ConsumingParamNotLastUse(_, ref, _) => List(ref.span)
      case SemanticError.PartialApplicationWithConsuming(fn, _, _) => List(fn.span)
      case SemanticError.ConditionalOwnershipMismatch(cond, _) => List(cond.span)
      case SemanticError.BorrowEscapeViaReturn(ref, _) => List(ref.span)

      case te: TypeError => extractTypeErrorSpans(te)

      case _ => Nil

  private def extractTypeErrorSpans(error: TypeError): List[SrcSpan] =
    error match
      case TypeError.MissingParameterType(param, _, _) => List(param.span)
      case TypeError.MissingReturnType(decl, _) => declSpan(decl).toList
      case TypeError.RecursiveFunctionMissingReturnType(d, _) => declSpan(d).toList
      case TypeError.MissingOperatorParameterType(param, _, _) => List(param.span)
      case TypeError.MissingOperatorReturnType(decl, _) => declSpan(decl).toList
      case TypeError.TypeMismatch(node, _, _, _, _) => nodeSpan(node).toList
      case TypeError.UndersaturatedApplication(app, _, _, _) => List(app.span)
      case TypeError.OversaturatedApplication(app, _, _, _) => List(app.span)
      case TypeError.InvalidApplication(app, _, _, _) => List(app.span)
      case TypeError.InvalidSelection(ref, _, _) => List(ref.span)
      case TypeError.UnknownField(ref, _, _) => List(ref.span)
      case TypeError.ConditionalBranchTypeMismatch(c, _, _, _) => List(c.span)
      case TypeError.ConditionalBranchTypeUnknown(cond, _) => List(cond.span)
      case TypeError.UnresolvableType(node, _, _) => nodeSpan(node).toList
      case TypeError.IncompatibleTypes(node, _, _, _, _) => astNodeSpan(node).toList
      case TypeError.UntypedHoleInBinding(_, span, _) => List(span)

  private def declSpan(decl: Decl): Option[SrcSpan] =
    decl match
      case bnd: Bnd => Some(bnd.span)
      case td:  TypeDef => Some(td.span)
      case ta:  TypeAlias => Some(ta.span)
      case ts:  TypeStruct => Some(ts.span)

  private def nodeSpan(node: Typeable): Option[SrcSpan] =
    node match
      case fs: FromSource => fs.source.spanOpt
      case _ => None

  private def astNodeSpan(node: AstNode): Option[SrcSpan] =
    node match
      case fs: FromSource => fs.source.spanOpt
      case _ => None

  private def formatErrorMessage(error: CompilationError): String =
    error match
      case ParserError.Failure(msg) => s"Parse error: $msg"
      case ParserError.Unknown(msg) => s"Unknown parse error: $msg"

      case SemanticError.UndefinedRef(ref, _, _) =>
        s"Undefined reference: ${ref.name}"
      case SemanticError.UndefinedTypeRef(typeRef, _, _) =>
        s"Undefined type: ${typeRef.name}"
      case SemanticError.DuplicateName(name, _, _) =>
        s"Duplicate definition: $name"
      case SemanticError.InvalidExpression(_, msg, _) =>
        s"Invalid expression: $msg"
      case SemanticError.DanglingTerms(_, msg, _) =>
        s"Dangling terms: $msg"
      case SemanticError.MemberErrorFound(err, _) =>
        s"Member error: ${err.message}"
      case SemanticError.ParsingIdErrorFound(err, _) =>
        s"Parsing error: ${err.message}"
      case SemanticError.InvalidExpressionFound(_, _) =>
        "Invalid expression"
      case SemanticError.TypeCheckingError(typeErr) =>
        formatTypeError(typeErr)
      case SemanticError.InvalidEntryPoint(msg, _) =>
        s"Invalid entry point: $msg"
      case e: SemanticError.UseAfterMove => e.message
      case e: SemanticError.ConsumingParamNotLastUse => e.message
      case e: SemanticError.PartialApplicationWithConsuming => e.message
      case e: SemanticError.ConditionalOwnershipMismatch => e.message
      case e: SemanticError.BorrowEscapeViaReturn => e.message

      case te: TypeError => formatTypeError(te)

      case other => other.toString

  private def formatTypeError(error: TypeError): String =
    error match
      case TypeError.MissingParameterType(param, _, _) =>
        s"Missing type annotation for parameter: ${param.name}"
      case TypeError.MissingReturnType(_, _) =>
        "Missing return type annotation"
      case TypeError.RecursiveFunctionMissingReturnType(_, _) =>
        "Recursive function requires return type annotation"
      case TypeError.MissingOperatorParameterType(param, _, _) =>
        s"Missing type annotation for operator parameter: ${param.name}"
      case TypeError.MissingOperatorReturnType(_, _) =>
        "Missing return type annotation for operator"
      case TypeError.TypeMismatch(_, expected, actual, _, _) =>
        s"Type mismatch: expected ${AstLookup.formatType(Some(expected))}, " +
          s"got ${AstLookup.formatType(Some(actual))}"
      case TypeError.UndersaturatedApplication(_, exp, act, _) =>
        s"Too few arguments: expected $exp, got $act"
      case TypeError.OversaturatedApplication(_, exp, act, _) =>
        s"Too many arguments: expected $exp, got $act"
      case TypeError.InvalidApplication(_, fnType, argType, _) =>
        s"Cannot apply ${AstLookup.formatType(Some(fnType))} " +
          s"to ${AstLookup.formatType(Some(argType))}"
      case TypeError.InvalidSelection(ref, baseType, _) =>
        s"Cannot select '${ref.name}' from ${AstLookup.formatType(Some(baseType))}"
      case TypeError.UnknownField(ref, struct, _) =>
        s"Unknown field '${ref.name}' in struct ${struct.name}"
      case TypeError.ConditionalBranchTypeMismatch(_, t, f, _) =>
        s"Branch type mismatch: then-branch is ${AstLookup.formatType(Some(t))}, " +
          s"else-branch is ${AstLookup.formatType(Some(f))}"
      case TypeError.ConditionalBranchTypeUnknown(_, _) =>
        "Cannot determine type of conditional branches"
      case TypeError.UnresolvableType(_, ctx, _) =>
        ctx match
          case Some(c) => s"Cannot resolve type in context: $c"
          case None => "Cannot resolve type"
      case TypeError.IncompatibleTypes(_, t1, t2, ctx, _) =>
        s"Incompatible types in $ctx: ${AstLookup.formatType(Some(t1))} " +
          s"vs ${AstLookup.formatType(Some(t2))}"
      case TypeError.UntypedHoleInBinding(name, _, _) =>
        s"Typed hole in binding '$name' requires type annotation"

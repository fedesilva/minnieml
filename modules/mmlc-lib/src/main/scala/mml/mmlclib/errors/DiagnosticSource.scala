package mml.mmlclib.errors

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.{OwnershipError, SemanticError, TypeError}

/** Source origins for front-end diagnostics. Recovery wrappers use derived origins to preserve
  * display ranges without identifying another written use. Origins may lack spans; errors outside
  * the supported front-end families return no origins.
  */
def diagnosticSources(error: CompilationError): List[SourceOrigin] = error match

  case error: TypeError => List(typeErrorContext(error)._1)
  case SemanticError.TypeCheckingError(error) => diagnosticSources(error)
  case SemanticError.UndefinedRef(ref, _, _) => List(ref.source)
  case SemanticError.UndefinedTypeRef(ref, _, _) => List(ref.source)
  case SemanticError.AmbiguousTypeRef(ref, _, _, _) => List(ref.source)
  case SemanticError.DuplicateName(_, duplicates, _, _) =>
    duplicates.collect { case source: FromSource => source.source }
  case SemanticError.InvalidExpression(expr, OwnershipError.InvalidExpressionShape, _) =>
    List(expr.source.derived)
  case SemanticError.InvalidExpression(expr, _, _) => List(expr.source)
  case SemanticError.DanglingTerms(terms, _, _) => terms.headOption.map(_.source).toList
  case SemanticError.MemberErrorFound(error, _) => List(error.source)
  case SemanticError.ParsingIdErrorFound(error, _) => List(error.source)
  case SemanticError.ParseTermErrorFound(error, _) => List(error.source)
  case SemanticError.ParseTypeErrorFound(error, _) => List(error.source)
  case SemanticError.InvalidExpressionFound(error, _) => List(error.source.derived)
  case SemanticError.ProtocolErrorFound(error, _) => List(error.source)
  case SemanticError.InvalidEntryPoint(error) => List(error.source)
  case SemanticError.VisibilityViolation(ref, _, _) => List(ref.source)
  case error: SemanticError.UseAfterMove => List(error.source)
  case SemanticError.ConsumingParamNotLastUse(_, ref, _) => List(ref.source)
  case SemanticError.BorrowedValuePassedToConsumingParam(_, ref, _) => List(ref.source)
  case SemanticError.PartialApplicationWithConsuming(fn, _, _) => List(fn.source)
  case SemanticError.ConditionalOwnershipMismatch(cond, _) => List(cond.source)
  case SemanticError.BorrowEscapeViaReturn(ref, _) => List(ref.source)
  case SemanticError.CapturedBorrowedHeapBinding(ref, _) => List(ref.source)
  case SemanticError.CapturedMovedHeapBinding(ref, _, _) => List(ref.source)
  case SemanticError.BorrowClosureEscapeViaReturn(lambda, _) => List(lambda.source)
  case _ => Nil

/** Source and compiler phase retained by a type failure, independent of its wording. */
def typeErrorContext(error: TypeError): (SourceOrigin, String) =
  def origin(node: AstNode): SourceOrigin = node match
    case source: FromSource => source.source
    case _ => SourceOrigin.Synth
  error match
    case TypeError.MissingParameterType(param, _, phase) => (origin(param), phase)
    case TypeError.MissingReturnType(decl, phase) => (origin(decl), phase)
    case TypeError.RecursiveFunctionMissingReturnType(decl, phase) => (origin(decl), phase)
    case TypeError.MissingOperatorParameterType(param, _, phase) => (origin(param), phase)
    case TypeError.MissingOperatorReturnType(decl, phase) => (origin(decl), phase)
    case TypeError.TypeMismatch(node, _, _, phase, _) => (origin(node), phase)
    case TypeError.UndersaturatedApplication(app, _, _, phase) => (origin(app), phase)
    case TypeError.OversaturatedApplication(app, _, _, phase) => (origin(app), phase)
    case TypeError.InvalidApplication(app, _, _, phase) => (origin(app), phase)
    case TypeError.InvalidSelection(ref, _, phase) => (origin(ref), phase)
    case TypeError.UnknownField(ref, _, phase) => (origin(ref), phase)
    case TypeError.ConditionalBranchTypeMismatch(cond, _, _, phase) => (origin(cond), phase)
    case TypeError.ConditionalBranchTypeUnknown(cond, phase) => (origin(cond), phase)
    case TypeError.UnresolvableType(node, _, phase) => (origin(node), phase)
    case TypeError.IncompatibleTypes(node, _, _, phase) => (origin(node), phase)
    case TypeError.ConflictingLambdaParamInference(param, _, _, phase) => (origin(param), phase)
    case TypeError.UninferrableLambdaParam(param, phase) => (origin(param), phase)
    case TypeError.UntypedHoleInBinding(_, source, phase) => (source, phase)

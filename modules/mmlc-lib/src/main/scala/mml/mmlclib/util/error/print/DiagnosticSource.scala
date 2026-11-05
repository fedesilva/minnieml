package mml.mmlclib.util.error.print

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.TypeError

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

package mml.mmlclib.util.error.print

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.*

private[print] def showType(t: Type): String = t match
  case TypeRef(_, name, _, _) => name
  case ts: TypeStruct => ts.name
  case TypeFn(_, params, ret) =>
    s"(${params.toList.map(showType).mkString(", ")}) -> ${showType(ret)}"
  case TypeUnit(_) => "Unit"
  case TypeGroup(_, types) => s"(${types.map(showType).mkString(", ")})"
  case TypeTuple(_, elems) => s"(${elems.map(showType).mkString(", ")})"
  case NativePrimitive(_, llvm, _, _) => llvm
  case NativePointer(_, llvm, _, _) => s"*$llvm"
  case TypeVariable(_, name) => name
  case _ => t.getClass.getSimpleName

/** Shared wording for type-system failures. */
def typeErrorMessage(error: TypeError): String =
  import TypeError.*
  error match
    case MissingParameterType(param, _, _) =>
      s"Missing type annotation for parameter '${param.name}'"
    case MissingReturnType(decl, _) =>
      s"Missing return type for '${decl.name}'"
    case RecursiveFunctionMissingReturnType(decl, _) =>
      s"Recursive function '${decl.name}' requires explicit return type"
    case MissingOperatorParameterType(param, _, _) =>
      s"Missing type annotation for operator parameter '${param.name}'"
    case MissingOperatorReturnType(decl, _) =>
      s"Missing return type for operator '${decl.name}'"
    case TypeMismatch(_, expected, actual, _, expectedBy) =>
      val ctx = expectedBy.map(by => s" (expected by $by)").getOrElse("")
      s"Type mismatch: expected ${showType(expected)}, got ${showType(actual)}$ctx"
    case UndersaturatedApplication(_, expected, actual, _) =>
      s"Too few arguments: expected $expected, got $actual"
    case OversaturatedApplication(_, expected, actual, _) =>
      s"Too many arguments: expected $expected, got $actual"
    case InvalidApplication(_, fnType, argType, _) =>
      s"Cannot apply ${showType(fnType)} to ${showType(argType)}"
    case InvalidSelection(ref, baseType, _) =>
      s"Cannot select '${ref.name}' from ${showType(baseType)}"
    case UnknownField(ref, struct, _) =>
      s"Unknown field '${ref.name}' in struct ${struct.name}"
    case ConditionalBranchTypeMismatch(_, trueType, falseType, _) =>
      s"Conditional branches have different types: ${showType(trueType)} vs ${showType(falseType)}"
    case ConditionalBranchTypeUnknown(_, _) =>
      "Cannot determine type of conditional branches"
    case UnresolvableType(_, context, _) =>
      context match
        case Some(UnresolvableTypeContext.NamedValue(name)) =>
          s"Cannot resolve type for '$name'"
        case Some(UnresolvableTypeContext.Argument) =>
          "Cannot resolve type for argument"
        case Some(UnresolvableTypeContext.Function) =>
          "Cannot resolve function type"
        case None =>
          "Cannot resolve type"
    case IncompatibleTypes(_, type1, type2, _) =>
      s"Incompatible types: ${showType(type1)} and ${showType(type2)}"
    case ConflictingLambdaParamInference(param, firstType, nextType, _) =>
      s"Conflicting types inferred for lambda parameter '${param.name}': " +
        s"${showType(firstType)} vs ${showType(nextType)}. Add a type annotation."
    case UninferrableLambdaParam(param, _) =>
      s"Cannot infer type for lambda parameter '${param.name}'. Add a type annotation."
    case UntypedHoleInBinding(name, _, _) =>
      s"Typed hole in '$name' requires type annotation"

/** Shared wording for semantic failures. */
def semanticErrorMessage(error: SemanticError): String =
  import SemanticError.*
  error match
    case UndefinedRef(ref, _, _) =>
      s"Undefined reference: '${ref.name}'"
    case UndefinedTypeRef(typeRef, _, _) =>
      s"Undefined type: '${typeRef.name}'"
    case AmbiguousTypeRef(typeRef, _, _, _) =>
      s"Ambiguous type: '${typeRef.name}'"
    case DuplicateName(name, duplicates, _, owner) =>
      val label = owner.fold(name) { binding =>
        val parameters = duplicates.map(_.name).distinct.mkString(", ")
        s"${mml.mmlclib.ast.queries.DeclarationQueries.sourceName(binding)} parameters: $parameters"
      }
      s"Duplicate definition: '$label'"
    case InvalidExpression(_, reason, _) =>
      expressionMessage(reason)
    case DanglingTerms(_, reason, _) =>
      expressionMessage(reason)
    case MemberErrorFound(error, _) =>
      s"Parse error in member: ${diagnosticMessage(error)}"
    case ParsingIdErrorFound(error, _) =>
      s"Parse error in identifier: ${diagnosticMessage(error)}"
    case ParseTermErrorFound(error, _) =>
      s"Parse error in expression: ${diagnosticMessage(error)}"
    case ParseTypeErrorFound(error, _) =>
      s"Parse error in type: ${diagnosticMessage(error)}"
    case InvalidExpressionFound(_, _) =>
      "Invalid expression"
    case TypeCheckingError(error) =>
      diagnosticMessage(error)
    case ProtocolErrorFound(error, _) =>
      diagnosticMessage(error)
    case InvalidEntryPoint(error) =>
      entryPointMessage(error)
    case VisibilityViolation(ref, decl, _) =>
      s"'${ref.name}' is not visible here (declared in '${decl.name}')"
    case error: UseAfterMove =>
      error.movedAt.spanOpt match
        case Some(span) =>
          s"Use of '${error.ref.name}' after move at ${span.start.line}:${span.start.col}"
        case None =>
          s"Use of '${error.ref.name}' after move"
    case ConsumingParamNotLastUse(param, ref, _) =>
      s"Consuming parameter '${param.name}' must be the last use of '${ref.name}'"
    case BorrowedValuePassedToConsumingParam(param, ref, _) =>
      s"Cannot pass borrowed value '${ref.name}' to consuming parameter '${param.name}'"
    case PartialApplicationWithConsuming(_, param, _) =>
      s"Cannot partially apply function with consuming parameter '${param.name}'"
    case ConditionalOwnershipMismatch(_, _) =>
      "Conditional branches have different ownership states"
    case BorrowEscapeViaReturn(ref, _) =>
      s"Cannot return borrowed value '${ref.name}' from a function that returns a heap type"
    case CapturedBorrowedHeapBinding(ref, _) =>
      s"Cannot capture borrowed heap binding '${ref.name}' in a closure"
    case CapturedMovedHeapBinding(ref, movedAt, _) =>
      movedAt.spanOpt match
        case Some(span) =>
          s"Cannot capture moved heap binding '${ref.name}' in a closure; it was already moved at ${span.start.line}:${span.start.col}"
        case None =>
          s"Cannot capture moved heap binding '${ref.name}' in a closure"
    case BorrowClosureEscapeViaReturn(_, _) =>
      "Cannot return a borrow-capturing closure; use ~{ ... } for a move closure"

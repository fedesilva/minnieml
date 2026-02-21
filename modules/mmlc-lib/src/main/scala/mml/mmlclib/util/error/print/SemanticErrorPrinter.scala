package mml.mmlclib.util.error.print

import mml.mmlclib.ast.{Decl, FromSource}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.{SemanticError, TypeError, UnresolvableTypeContext}
// Removed Ordering import

/** Pretty printer for semantic errors */
object SemanticErrorPrinter:
  private def spanOf(node: FromSource): Option[mml.mmlclib.ast.SrcSpan] =
    node.source.spanOpt

  private def locationOf(node: FromSource): String =
    spanOf(node).map(LocationPrinter.printSpan).getOrElse("<unknown>")

  /** Pretty print a list of semantic errors
    *
    * @param errors
    *   List of semantic errors
    * @param sourceInfo
    *   Optional source info with the original source text
    * @return
    *   A human-readable error message
    */
  def prettyPrint(errors: List[SemanticError], sourceInfo: Option[SourceInfo] = None): String =
    if errors.isEmpty then "No semantic errors"
    else
      val errorMessages = errors.map(error => prettyPrintSingle(error, sourceInfo))
      s"${Console.RED}Semantic errors:${Console.RESET}\n${errorMessages.mkString("\n\n")}"

  /** Pretty print a single semantic error
    *
    * @param error
    *   The semantic error to pretty print
    * @param sourceInfo
    *   Optional source info with the original source text
    * @return
    *   A human-readable error message
    */
  private def prettyPrintSingle(error: SemanticError, sourceInfo: Option[SourceInfo]): String =
    // Extract AST information when applicable
    val astInfo = error match
      case SemanticError.UndefinedRef(ref, _, _) =>
        val resolved = ref.resolvedId
          .map(id => s"Resolved to: $id")
          .getOrElse("Not resolved")
        val candidates =
          if ref.candidateIds.isEmpty then "No candidates found"
          else s"Candidates: [${ref.candidateIds.mkString(", ")}]"
        s"Info: '$resolved', $candidates"

      case SemanticError.DanglingTerms(terms, _, _) =>
        val termInfos = terms
          .map {
            case ref: mml.mmlclib.ast.Ref =>
              val resolved = ref.resolvedId
                .map(id => s"Resolved to: $id")
                .getOrElse("Not resolved")
              val candidates =
                if ref.candidateIds.isEmpty then "No candidates found"
                else s"Candidates: [${ref.candidateIds.mkString(", ")}]"
              s"'${ref.name}': $resolved, $candidates"
            case term => s"Term: ${term.getClass.getSimpleName}"
          }
          .mkString("\n")
        s"${Console.YELLOW}Term info:${Console.RESET}\n$termInfos"

      case _ => "" // No special AST info for other error types

    // Generate the base error message without source code snippets
    val baseMessage = error match
      case SemanticError.UndefinedRef(ref, member, phase) =>
        val location = locationOf(ref)
        val memberName = member match
          case d: Decl => d.name
          case _ => "unknown"
        s"${Console.RED}Undefined reference '${ref.name}' at $location in $memberName [phase: $phase]${Console.RESET}"

      case SemanticError.UndefinedTypeRef(typeRef, member, phase) =>
        val location = locationOf(typeRef)
        val memberName = member match
          case d: Decl => d.name
          case _ => "unknown"
        s"${Console.RED}Undefined type reference '${typeRef.name}' at $location in $memberName [phase: $phase]${Console.RESET}"

      case SemanticError.DuplicateName(name, duplicates, phase) =>
        // Sort duplicates by their starting index using sortBy
        val sortedDuplicates = duplicates
          .collect { case d: FromSource => d }
          .flatMap(d => spanOf(d).map(s => (d, s)))
          .sortBy(_._2.start.index)
        val locations = sortedDuplicates // Use sorted list
          .map { case (_, s) => LocationPrinter.printSpan(s) }
          .mkString(" ") // Use space separator
        s"${Console.RED}Duplicate name '$name' defined at: $locations [phase: $phase]${Console.RESET}"

      case SemanticError.InvalidExpression(expr, message, phase) =>
        val location = locationOf(expr)
        s"${Console.RED}Invalid expression at $location: $message [phase: $phase]${Console.RESET}"

      case SemanticError.MemberErrorFound(error, phase) =>
        val location = locationOf(error)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Parser error at $location: ${error.message} [phase: $phase]\n$snippet${Console.RESET}"

      case SemanticError.ParsingIdErrorFound(error, phase) =>
        val location = locationOf(error)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Invalid identifier at $location: ${error.message} [phase: $phase]\n$snippet${Console.RESET}"

      case SemanticError.DanglingTerms(terms, message, phase) =>
        val locations = terms.map(t => locationOf(t)).mkString(", ")
        s"${Console.RED}$message at $locations [phase: $phase]${Console.RESET}"

      case SemanticError.InvalidExpressionFound(invalidExpr, phase) =>
        val location = locationOf(invalidExpr)
        s"${Console.RED}Invalid expression found at $location [phase: $phase]${Console.RESET}"

      case SemanticError.InvalidEntryPoint(message, source) =>
        val location = source.spanOpt.map(LocationPrinter.printSpan).getOrElse("[synthetic]")
        s"${Console.RED}$message at $location${Console.RESET}"

      case SemanticError.UseAfterMove(ref, movedAt, phase) =>
        val location      = locationOf(ref)
        val movedLocation = movedAt.spanOpt.map(LocationPrinter.printSpan).getOrElse("[synthetic]")
        s"${Console.RED}Use of '${ref.name}' after move at $location [phase: $phase]${Console.RESET}\nMoved at: $movedLocation"

      case SemanticError.ConsumingParamNotLastUse(param, ref, phase) =>
        val location = locationOf(ref)
        s"${Console.RED}Consuming parameter '${param.name}' must be the last use of '${ref.name}' at $location [phase: $phase]${Console.RESET}"

      case SemanticError.PartialApplicationWithConsuming(fn, param, phase) =>
        val location = locationOf(fn)
        s"${Console.RED}Cannot partially apply function with consuming parameter '${param.name}' at $location [phase: $phase]${Console.RESET}"

      case SemanticError.ConditionalOwnershipMismatch(cond, phase) =>
        val location = locationOf(cond)
        s"${Console.RED}Conditional branches have different ownership states at $location [phase: $phase]${Console.RESET}"

      case SemanticError.BorrowEscapeViaReturn(ref, phase) =>
        val location = locationOf(ref)
        s"${Console.RED}Cannot return borrowed value '${ref.name}' at $location [phase: $phase]${Console.RESET}"

      case SemanticError.TypeCheckingError(error) =>
        prettyPrintTypeError(error)

    // Add AST info and source code snippets if source code is available
    sourceInfo match
      case Some(info) =>
        // Pass both the source code and the error to extractSnippetsFromError
        val snippets = SourceCodeExtractor.extractSnippetsFromError(info, error)
        val fullMessage =
          if astInfo.nonEmpty then s"$baseMessage\n$astInfo\n$snippets"
          else s"$baseMessage\n$snippets"
        fullMessage
      case None =>
        if astInfo.nonEmpty then s"$baseMessage\n$astInfo"
        else baseMessage

  /** Pretty print a type error with proper formatting
    *
    * @param error
    *   The type error to pretty print
    * @return
    *   A human-readable error message
    */
  def prettyPrintTypeError(error: TypeError): String =
    def formatTypeSpec(typeSpec: mml.mmlclib.ast.Type): String = typeSpec match
      case mml.mmlclib.ast.TypeRef(_, name, _, _) => name
      case ts: mml.mmlclib.ast.TypeStruct => ts.name
      case mml.mmlclib.ast.TypeFn(_, params, returnType) =>
        val paramStr = params.map(formatTypeSpec).mkString(", ")
        s"($paramStr) -> ${formatTypeSpec(returnType)}"
      case mml.mmlclib.ast.TypeUnit(_) => "()"
      case other => other.getClass.getSimpleName

    error match
      case TypeError.MissingParameterType(param, decl, phase) =>
        val location = locationOf(param)
        s"${Console.RED}Missing parameter type for '${param.name}' in function '${decl.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.MissingReturnType(decl, phase) =>
        val location = locationOf(decl.asInstanceOf[FromSource])
        s"${Console.RED}Missing return type for function '${decl.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.RecursiveFunctionMissingReturnType(decl, phase) =>
        val location = locationOf(decl.asInstanceOf[FromSource])
        s"${Console.RED}Missing return type for self-recursive function '${decl.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.MissingOperatorParameterType(param, decl, phase) =>
        val location = locationOf(param)
        s"${Console.RED}Missing parameter type for '${param.name}' in operator '${decl.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.MissingOperatorReturnType(decl, phase) =>
        val location = locationOf(decl.asInstanceOf[FromSource])
        s"${Console.RED}Missing return type for operator '${decl.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.TypeMismatch(node, expected, actual, phase, expectedBy) =>
        val location    = locationOf(node.asInstanceOf[FromSource])
        val expectedStr = formatTypeSpec(expected)
        val actualStr   = formatTypeSpec(actual)
        val expectation =
          expectedBy match
            case Some(name) => s"'$name' expected '$expectedStr'"
            case None => s"expected '$expectedStr'"
        s"${Console.RED}Type mismatch at $location: $expectation, got '$actualStr'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.UndersaturatedApplication(app, expectedArgs, actualArgs, phase) =>
        val location = locationOf(app)
        s"${Console.RED}Under-saturated function application at $location: expected $expectedArgs arguments, got $actualArgs${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.OversaturatedApplication(app, expectedArgs, actualArgs, phase) =>
        val location = locationOf(app)
        s"${Console.RED}Over-saturated function application at $location: expected $expectedArgs arguments, got $actualArgs${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.InvalidApplication(app, fnType, argType, phase) =>
        val location   = locationOf(app)
        val fnTypeStr  = formatTypeSpec(fnType)
        val argTypeStr = formatTypeSpec(argType)
        s"${Console.RED}Invalid function application at $location: cannot apply function of type '$fnTypeStr' to argument of type '$argTypeStr'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.InvalidSelection(ref, baseType, phase) =>
        val location    = locationOf(ref)
        val baseTypeStr = formatTypeSpec(baseType)
        s"${Console.RED}Invalid field selection at $location: '${ref.name}' is not available on type '$baseTypeStr'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.UnknownField(ref, struct, phase) =>
        val location = locationOf(ref)
        s"${Console.RED}Unknown field at $location: '${struct.name}' has no field '${ref.name}'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.ConditionalBranchTypeMismatch(cond, trueType, falseType, phase) =>
        val location     = locationOf(cond)
        val trueTypeStr  = formatTypeSpec(trueType)
        val falseTypeStr = formatTypeSpec(falseType)
        s"${Console.RED}Conditional branch type mismatch at $location: 'then' branch has type '$trueTypeStr', 'else' branch has type '$falseTypeStr'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.ConditionalBranchTypeUnknown(cond, phase) =>
        val location = locationOf(cond)
        s"${Console.RED}Unable to determine conditional branch types at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.UnresolvableType(node, context, phase) =>
        val location = node match
          case fs: FromSource => locationOf(fs)
          case _ => "<unknown>"
        val nameSuffix = context match
          case Some(UnresolvableTypeContext.NamedValue(name)) => s" for '$name'"
          case _ => ""
        s"${Console.RED}Unable to infer type$nameSuffix at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.IncompatibleTypes(node, type1, type2, context, phase) =>
        val location = locationOf(node.asInstanceOf[FromSource])
        val type1Str = formatTypeSpec(type1)
        val type2Str = formatTypeSpec(type2)
        s"${Console.RED}Incompatible types at $location in $context: '$type1Str' and '$type2Str'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.UntypedHoleInBinding(bindingName, source, phase) =>
        val location = source.spanOpt.map(LocationPrinter.printSpan).getOrElse("[synthetic]")
        s"${Console.RED}Untyped hole '???' in binding '${bindingName}' at $location - " +
          s"type cannot be inferred${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

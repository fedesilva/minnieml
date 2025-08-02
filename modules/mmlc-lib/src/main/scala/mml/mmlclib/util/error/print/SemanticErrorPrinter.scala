package mml.mmlclib.util.error.print

import mml.mmlclib.ast.{Decl, FromSource}
import mml.mmlclib.semantic.{SemanticError, TypeError}
// Removed Ordering import

/** Pretty printer for semantic errors */
object SemanticErrorPrinter:

  /** Pretty print a list of semantic errors
    *
    * @param errors
    *   List of semantic errors
    * @param sourceCode
    *   Optional source code string
    * @return
    *   A human-readable error message
    */
  def prettyPrint(errors: List[SemanticError], sourceCode: Option[String] = None): String =
    if errors.isEmpty then "No semantic errors"
    else
      val errorMessages = errors.map(error => prettyPrintSingle(error, sourceCode))
      s"${Console.RED}Semantic errors:${Console.RESET}\n${errorMessages.mkString("\n\n")}"

  /** Pretty print a single semantic error
    *
    * @param error
    *   The semantic error to pretty print
    * @param sourceCode
    *   Optional source code string
    * @return
    *   A human-readable error message
    */
  private def prettyPrintSingle(error: SemanticError, sourceCode: Option[String]): String =
    // Extract AST information when applicable
    val astInfo = error match
      case SemanticError.UndefinedRef(ref, _, _) =>
        val resolved = ref.resolvedAs
          .map(r => s"Resolved as: ${r.getClass.getSimpleName}(${r.name})")
          .getOrElse("Not resolved")
        val candidates =
          if ref.candidates.isEmpty then "No candidates found"
          else {
            val candidateList =
              ref.candidates.map(c => s"${c.getClass.getSimpleName}(${c.name})").mkString(", ")
            s"Candidates: [$candidateList]"
          }
        s"Info: '$resolved', $candidates"

      case SemanticError.DanglingTerms(terms, _, _) =>
        val termInfos = terms
          .map {
            case ref: mml.mmlclib.ast.Ref =>
              val resolved = ref.resolvedAs
                .map(r => s"Resolved as: ${r.getClass.getSimpleName}(${r.name})")
                .getOrElse("Not resolved")
              val candidates =
                if ref.candidates.isEmpty then "No candidates found"
                else {
                  val candidateList = ref.candidates
                    .map(c => s"${c.getClass.getSimpleName}(${c.name})")
                    .mkString(", ")
                  s"Candidates: [$candidateList]"
                }
              s"'${ref.name}': $resolved, $candidates"
            case term => s"Term: ${term.getClass.getSimpleName}"
          }
          .mkString("\n")
        s"${Console.YELLOW}Term info:${Console.RESET}\n$termInfos"

      case _ => "" // No special AST info for other error types

    // Generate the base error message without source code snippets
    val baseMessage = error match
      case SemanticError.UndefinedRef(ref, member, phase) =>
        val location = LocationPrinter.printSpan(ref.span)
        val memberName = member match
          case d: Decl => d.name
          case _ => "unknown"
        s"${Console.RED}Undefined reference '${ref.name}' at $location in $memberName [phase: $phase]${Console.RESET}"

      case SemanticError.UndefinedTypeRef(typeRef, member, phase) =>
        val location = LocationPrinter.printSpan(typeRef.span)
        val memberName = member match
          case d: Decl => d.name
          case _ => "unknown"
        s"${Console.RED}Undefined type reference '${typeRef.name}' at $location in $memberName [phase: $phase]${Console.RESET}"

      case SemanticError.DuplicateName(name, duplicates, phase) =>
        // Sort duplicates by their starting index using sortBy
        val sortedDuplicates = duplicates
          .collect { case d: FromSource => d }
          .sortBy(_.span.start.index) // Use sortBy
        val locations = sortedDuplicates // Use sorted list
          .map(d => LocationPrinter.printSpan(d.span))
          .mkString(" ") // Use space separator
        s"${Console.RED}Duplicate name '$name' defined at: $locations [phase: $phase]${Console.RESET}"

      case SemanticError.InvalidExpression(expr, message, phase) =>
        val location = LocationPrinter.printSpan(expr.span)
        s"${Console.RED}Invalid expression at $location: $message [phase: $phase]${Console.RESET}"

      case SemanticError.MemberErrorFound(error, phase) =>
        val location = LocationPrinter.printSpan(error.span)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Parser error at $location: ${error.message} [phase: $phase]\n$snippet${Console.RESET}"

      case SemanticError.ParsingIdErrorFound(error, phase) =>
        val location = LocationPrinter.printSpan(error.span)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Invalid identifier at $location: ${error.message} [phase: $phase]\n$snippet${Console.RESET}"

      case SemanticError.DanglingTerms(terms, message, phase) =>
        val locations = terms.map(t => LocationPrinter.printSpan(t.span)).mkString(", ")
        s"${Console.RED}$message at $locations [phase: $phase]${Console.RESET}"

      case SemanticError.InvalidExpressionFound(invalidExpr, phase) =>
        val location = LocationPrinter.printSpan(invalidExpr.span)
        s"${Console.RED}Invalid expression found at $location [phase: $phase]${Console.RESET}"

      case SemanticError.TypeCheckingError(error) =>
        prettyPrintTypeError(error)

    // Add AST info and source code snippets if source code is available
    sourceCode match
      case Some(src) =>
        // Pass both the source code and the error to extractSnippetsFromError
        val snippets = SourceCodeExtractor.extractSnippetsFromError(src, error)
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
    def formatTypeSpec(typeSpec: mml.mmlclib.ast.TypeSpec): String = typeSpec match
      case mml.mmlclib.ast.TypeRef(_, name, _) => name
      case mml.mmlclib.ast.TypeFn(_, params, returnType) =>
        val paramStr = params.map(formatTypeSpec).mkString(", ")
        s"($paramStr) -> ${formatTypeSpec(returnType)}"
      case mml.mmlclib.ast.TypeUnit(_) => "()"
      case other => other.getClass.getSimpleName

    error match
      case TypeError.MissingParameterType(param, fnDef, phase) =>
        val location = LocationPrinter.printSpan(param.span)
        s"${Console.RED}Missing parameter type for '${param.name}' in function '${fnDef.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.MissingReturnType(fnDef, phase) =>
        val location = LocationPrinter.printSpan(fnDef.span)
        s"${Console.RED}Missing return type for function '${fnDef.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.MissingOperatorParameterType(param, opDef, phase) =>
        val location = LocationPrinter.printSpan(param.span)
        s"${Console.RED}Missing parameter type for '${param.name}' in operator '${opDef.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.MissingOperatorReturnType(opDef, phase) =>
        val location = LocationPrinter.printSpan(opDef.span)
        s"${Console.RED}Missing return type for operator '${opDef.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.TypeMismatch(node, expected, actual, phase) =>
        val location    = LocationPrinter.printSpan(node.asInstanceOf[FromSource].span)
        val expectedStr = formatTypeSpec(expected)
        val actualStr   = formatTypeSpec(actual)
        s"${Console.RED}Type mismatch at $location: expected '$expectedStr', got '$actualStr'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.UndersaturatedApplication(app, expectedArgs, actualArgs, phase) =>
        val location = LocationPrinter.printSpan(app.span)
        s"${Console.RED}Under-saturated function application at $location: expected $expectedArgs arguments, got $actualArgs${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.OversaturatedApplication(app, expectedArgs, actualArgs, phase) =>
        val location = LocationPrinter.printSpan(app.span)
        s"${Console.RED}Over-saturated function application at $location: expected $expectedArgs arguments, got $actualArgs${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.InvalidApplication(app, fnType, argType, phase) =>
        val location   = LocationPrinter.printSpan(app.span)
        val fnTypeStr  = formatTypeSpec(fnType)
        val argTypeStr = formatTypeSpec(argType)
        s"${Console.RED}Invalid function application at $location: cannot apply function of type '$fnTypeStr' to argument of type '$argTypeStr'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.ConditionalBranchTypeMismatch(cond, trueType, falseType, phase) =>
        val location     = LocationPrinter.printSpan(cond.span)
        val trueTypeStr  = formatTypeSpec(trueType)
        val falseTypeStr = formatTypeSpec(falseType)
        s"${Console.RED}Conditional branch type mismatch at $location: 'then' branch has type '$trueTypeStr', 'else' branch has type '$falseTypeStr'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.ConditionalBranchTypeUnknown(cond, phase) =>
        val location = LocationPrinter.printSpan(cond.span)
        s"${Console.RED}Unable to determine conditional branch types at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.UnresolvableType(typeRef, node, phase) =>
        val location = LocationPrinter.printSpan(typeRef.span)
        s"${Console.RED}Unresolvable type reference '${typeRef.name}' at $location${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.IncompatibleTypes(node, type1, type2, context, phase) =>
        val location = LocationPrinter.printSpan(node.asInstanceOf[FromSource].span)
        val type1Str = formatTypeSpec(type1)
        val type2Str = formatTypeSpec(type2)
        s"${Console.RED}Incompatible types at $location in $context: '$type1Str' and '$type2Str'${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case TypeError.UntypedHoleInBinding(bnd, phase) =>
        val location = LocationPrinter.printSpan(bnd.span)
        s"${Console.RED}Untyped hole '???' in binding '${bnd.name}' at $location - type cannot be inferred${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintCandidates(candidates: List[Resolvable]): String =
  if candidates.isEmpty then "[]"
  else s"[${candidates.map(c => s"${c.getClass.getSimpleName} ${c.name}").mkString(", ")}]"

def prettyPrintTerm(
  term:            Term,
  indent:          Int,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  val indentStr = "  " * indent
  term match {
    case Placeholder(sp, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}Placeholder $spanStr$typeStr"

    case ref: Ref =>
      val spanStr = if showSourceSpans then printSourceOrigin(ref.source) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(ref.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(ref.typeAsc)}\n" +
            s"${indentStr}  resolvedId: ${ref.resolvedId.getOrElse("None")}"
        else ""

      val qualifierStr = ref.qualifier match
        case Some(qualifier) =>
          val qualifierTerm =
            prettyPrintTerm(qualifier, indent + 2, showSourceSpans, showTypes)
          s"\n${indentStr}  qualifier:\n$qualifierTerm"
        case None => ""

      // Add resolvedId display if not showing types and resolvedId is present
      val resolvedStr =
        if !showTypes then ref.resolvedId.fold("")(id => s"\n${indentStr}  resolvedId: $id")
        else ""

      s"${indentStr}Ref ${ref.name} $spanStr$typeStr$resolvedStr$qualifierStr\n" +
        s"${indentStr}  candidateIds: ${ref.candidateIds.mkString(", ")}"

    case Hole(sp, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}Hole $spanStr$typeStr"

    case e: Expr => prettyPrintExpr(e, indent, showSourceSpans, showTypes)

    case TermGroup(sp, inner, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then s"\n${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}" else ""

      s"${indentStr}GroupTerm$spanStr$typeStr\n" +
        prettyPrintExpr(inner, indent + 1, showSourceSpans, showTypes)

    case Cond(sp, cond, ifTrue, ifFalse, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}Cond $spanStr$typeStr\n" +
        s"${indentStr}  cond:\n${prettyPrintExpr(cond, indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  ifTrue:\n${prettyPrintExpr(ifTrue, indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  ifFalse:\n${prettyPrintExpr(ifFalse, indent + 2, showSourceSpans, showTypes)}"

    case Tuple(sp, elements, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      val tupleIndentStr          = "  " * indent // Indent for the Tuple line itself
      val elementContentIndentStr = "  " * (indent + 1) // Indent for content *within* the Expr

      s"${tupleIndentStr}Tuple $spanStr$typeStr\n" +
        elements.toList.zipWithIndex
          .map { case (e, i) =>
            // Generate the expression string with indent + 1 so children are indented correctly
            val exprStrRaw = prettyPrintExpr(e, indent + 1, showSourceSpans, showTypes)
            val lines      = exprStrRaw.linesIterator.toList

            if lines.isEmpty then s"${tupleIndentStr} $i:<empty expr>" // Handle empty case
            else
              // First line from prettyPrintExpr starts with elementContentIndentStr
              val firstLineRaw = lines.head
              val restOfLines  = lines.tail

              // Remove the elementContentIndentStr prefix from the first line
              // Example: "      Expr[...]" becomes "Expr[...]"
              val strippedFirstLine = firstLineRaw.stripPrefix(elementContentIndentStr)

              // Prepend the index marker using the tuple's indent level
              // Example: "    " + " 0:" + "Expr[...]" becomes "     0:Expr[...]"
              val finalFirstLine = s"${tupleIndentStr} $i:$strippedFirstLine"

              // Join back with the rest of the lines (which are already correctly indented)
              (finalFirstLine :: restOfLines).mkString("\n")
          }
          .mkString("\n")

    case App(sp, fn, arg, typeAsc, typeSpec) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""
      // Recursively pretty-print the function part
      val fnStr  = prettyPrintTerm(fn, indent + 2, showSourceSpans, showTypes)
      val argStr = prettyPrintExpr(arg, indent + 2, showSourceSpans, showTypes)
      s"${indentStr}App $spanStr$typeStr\n" +
        s"${indentStr}  fn:\n$fnStr\n" +
        s"${indentStr}  arg:\n$argStr"

    case yielded: YieldOwnership =>
      val spanStr = if showSourceSpans then printSourceOrigin(yielded.source) else ""
      val typeStr =
        if showTypes then s" typeSpec=${prettyPrintTypeSpec(yielded.typeSpec)}" else ""
      val valueStr = prettyPrintExpr(yielded.value, indent + 2, showSourceSpans, showTypes)
      val ownsStr  = prettyPrintExpr(yielded.owns, indent + 2, showSourceSpans, showTypes)
      s"${indentStr}YieldOwnership $spanStr$typeStr\n" +
        s"${indentStr}  value:\n$valueStr\n" +
        s"${indentStr}  owns:\n$ownsStr"

    case binding: BindOwnership =>
      val spanStr = if showSourceSpans then printSourceOrigin(binding.source) else ""
      val typeStr =
        if showTypes then s" typeSpec=${prettyPrintTypeSpec(binding.typeSpec)}" else ""
      val paramsStr = List(binding.valueParam, binding.witnessParam)
        .map { param =>
          s"${indentStr}    ${param.name}: ${prettyPrintTypeSpec(param.typeSpec)}" +
            s" [${param.id.getOrElse("None")}]"
        }
        .mkString("\n")
      val initializerStr =
        prettyPrintExpr(binding.initializer, indent + 2, showSourceSpans, showTypes)
      val bodyStr = prettyPrintExpr(binding.body, indent + 2, showSourceSpans, showTypes)
      s"${indentStr}BindOwnership $spanStr$typeStr\n" +
        s"${indentStr}  params:\n$paramsStr\n" +
        s"${indentStr}  initializer:\n$initializerStr\n" +
        s"${indentStr}  body:\n$bodyStr"

    case Lambda(sp, params, body, captures, typeSpec, typeAsc, meta, isMove) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""
      val metaStr = meta match
        case Some(info) =>
          s"\n${indentStr}  meta: LambdaMeta(isTailRecursive=${info.isTailRecursive})"
        case None => s"\n${indentStr} meta: None"
      val paramsStr =
        if params.isEmpty then s"${indentStr}  params: []"
        else
          s"${indentStr}  params:\n" +
            params
              .map { p =>
                val consumingPrefix = if p.consuming then "~" else ""
                s"${indentStr}    $consumingPrefix${p.name}: ${prettyPrintTypeSpec(p.typeSpec)}"
              }
              .mkString("\n")
      val bodyStr = prettyPrintExpr(body, indent + 2, showSourceSpans, showTypes)
      val capturesStr = captures.map { capture =>
        val ownership = capture match
          case Capture.CapturedConditional(_, witness, cloneFnId) =>
            s" witness=${witness.resolvedId.getOrElse(witness.name)} clone=$cloneFnId"
          case Capture.CapturedLiteral(_, cloneFnId) => s" clone=$cloneFnId"
          case Capture.OwnedClosure(_, targetId) => s" destructor=$targetId"
          case _ => ""
        val identity = capture.ref.resolvedId.getOrElse(capture.ref.name)
        s"\n${indentStr}    ${capture.productPrefix} $identity$ownership"
      }.mkString
      val captureDetails =
        if captures.isEmpty then "" else s"\n${indentStr}  captures:$capturesStr"
      s"${indentStr}Lambda $spanStr$typeStr$metaStr\n" +
        s"$paramsStr$captureDetails\n" +
        s"${indentStr}  body:\n$bodyStr"

    // Literal values
    case lit: LiteralInt =>
      val spanStr = if showSourceSpans then printSourceOrigin(lit.source) else ""
      s"${indentStr}LiteralInt ${lit.value} $spanStr"

    case lit: LiteralString =>
      val spanStr = if showSourceSpans then printSourceOrigin(lit.source) else ""
      s"""${indentStr}LiteralString "${lit.value}" $spanStr"""

    case lit: LiteralBool =>
      val spanStr = if showSourceSpans then printSourceOrigin(lit.source) else ""
      s"${indentStr}LiteralBool ${lit.value} $spanStr"

    case lit: LiteralFloat =>
      val spanStr = if showSourceSpans then printSourceOrigin(lit.source) else ""
      s"${indentStr}LiteralFloat ${lit.value} $spanStr"

    case lit: LiteralUnit =>
      val spanStr = if showSourceSpans then printSourceOrigin(lit.source) else ""
      s"${indentStr}LiteralUnit $spanStr"

    case DataConstructor(sp, typeSpec) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}" else ""
      s"${indentStr}DataConstructor $spanStr$typeStr"

    case d: Destruction =>
      val details = d match
        case c: DestroyClosure => s" target=${c.targetId}"
        case c: DisarmClosureEnvironment => s" target=${c.targetId}"
        case e: DestroyClosureEnvironment =>
          s" layout=${e.layoutId} fields=${e.fields.mkString("[", ", ", "]")}"
        case _: DispatchClosureDestructor => ""
      val operand    = prettyPrintTerm(d.operand, indent + 1, showSourceSpans, showTypes)
      val resultType = if showTypes then s" typeSpec=${prettyPrintTypeSpec(d.typeSpec)}" else ""
      s"$indentStr${d.getClass.getSimpleName}$details$resultType\n$operand"

    case native @ NativeImpl(sp, typeSpec, typeAsc, nativeTpl, memEffect, nativeSymbol) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""
      val attributes = List(
        nativeTpl.map(t => s"\"$t\""),
        memEffect.map(e => s"mem=${e.toString.toLowerCase}"),
        nativeSymbol.map(n => s"name=\"$n\"")
      ).flatten
      val annotation = native.annotationName
      val attrsStr   = if attributes.isEmpty then "" else attributes.mkString("[", ", ", "]")

      s"$indentStr$annotation$attrsStr $spanStr$typeStr"

    case ParseTermError(sp, message, failedCode, _) =>
      val spanStr = if showSourceSpans then printSourceOrigin(sp) else ""
      s"${indentStr}ParseTermError $spanStr\n" +
        s"""${indentStr}  "$message"""" +
        failedCode.map(code => s"\n${indentStr}  $code").getOrElse("")

    case inv: InvalidExpression =>
      val spanStr = if showSourceSpans then printSourceOrigin(inv.source) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(inv.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(inv.typeAsc)}"
        else ""
      s"${indentStr}InvalidExpression $spanStr$typeStr\n" +
        s"${indentStr}  original:\n" +
        prettyPrintExpr(inv.originalExpr, indent + 2, showSourceSpans, showTypes)
  }

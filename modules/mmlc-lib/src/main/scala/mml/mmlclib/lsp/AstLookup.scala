package mml.mmlclib.lsp

import mml.mmlclib.ast.*

/** Result of looking up a position in the AST. */
case class LookupResult(
  typeSpec: Option[Type],
  name:     Option[String],
  span:     SrcSpan
)

object AstLookup:

  /** Find the deepest AST node at the given position (1-based line and column). Returns type info
    * if available.
    */
  def findAt(module: Module, line: Int, col: Int): Option[LookupResult] =
    findInMembers(module.members, line, col)

  /** Find the definition span for the symbol or type at the given position. */
  def findDefinitionAt(module: Module, line: Int, col: Int): List[SrcSpan] =
    val spans = findDefinitionInMembers(module.members, line, col, module)
    spans.filter(isValidSpan).distinct

  /** Find all references to the symbol or type at the given position. */
  def findReferencesAt(
    module:             Module,
    line:               Int,
    col:                Int,
    includeDeclaration: Boolean
  ): List[SrcSpan] =
    val target = findReferenceTargetInMembers(module.members, line, col, module)
    target
      .map(collectReferences(module, _, includeDeclaration))
      .getOrElse(Nil)
      .filter(isValidSpan)
      .distinct

  private def containsPosition(span: SrcSpan, line: Int, col: Int): Boolean =
    val afterStart = line > span.start.line ||
      (line == span.start.line && col >= span.start.col)
    // End is exclusive: span [28, 38) contains columns 28-37, not 38
    val beforeEnd = line < span.end.line ||
      (line == span.end.line && col < span.end.col)
    afterStart && beforeEnd

  private def isValidSpan(span: SrcSpan): Boolean =
    span.start.line > 0 && span.start.col > 0 && span.end.line > 0 && span.end.col > 0

  private def containsOrInvalid(span: SrcSpan, line: Int, col: Int): Boolean =
    !isValidSpan(span) || containsPosition(span, line, col)

  private sealed trait ReferenceTarget
  private case class ValueTarget(id: String)                            extends ReferenceTarget
  private case class TypeTarget(id: String)                             extends ReferenceTarget
  private case class FieldTarget(structName: String, fieldName: String) extends ReferenceTarget

  private def findInMembers(members: List[Member], line: Int, col: Int): Option[LookupResult] =
    members.collectFirst {
      case m if memberContains(m, line, col) => findInMember(m, line, col)
    }.flatten

  private def memberContains(m: Member, line: Int, col: Int): Boolean =
    m match
      case bnd: Bnd =>
        // Bnd span is just the name, but we also need to check value span for params/body
        containsPosition(bnd.span, line, col) || containsPosition(bnd.value.span, line, col)
      case fs: FromSource => fs.source.spanOpt.exists(containsPosition(_, line, col))
      case _ => false

  private def findInMember(member: Member, line: Int, col: Int): Option[LookupResult] =
    member match
      case bnd: Bnd =>
        // Only fall back to bnd info if position is OUTSIDE the value's span
        // (i.e., on the binding name itself). If inside value span, return what findInExpr returns
        // (None for comments/whitespace).
        if containsPosition(bnd.value.span, line, col) then findInExpr(bnd.value, line, col)
        else if bnd.source == SourceOrigin.Synth then None
        else Some(LookupResult(bnd.typeSpec, Some(bnd.name), bnd.span))

      case td: TypeDef =>
        Some(LookupResult(td.typeSpec, Some(td.name), td.span))

      case ta: TypeAlias =>
        Some(LookupResult(ta.typeSpec, Some(ta.name), ta.span))

      case ts: TypeStruct =>
        // Only fall back to struct info if not inside any field span
        findInFields(ts.fields.toList, line, col) match
          case some @ Some(_) => some
          case None =>
            // Check if we're inside any field's span (comment between fields)
            val insideField = ts.fields.exists(f => containsPosition(f.span, line, col))
            if insideField then None
            else Some(LookupResult(Some(ts), Some(ts.name), ts.span))

      case dm: DuplicateMember =>
        findInMember(dm.originalMember, line, col)

      case im: InvalidMember =>
        findInMember(im.originalMember, line, col)

      case _ => None

  private def findInFields(fields: List[Field], line: Int, col: Int): Option[LookupResult] =
    fields.collectFirst {
      case f if containsPosition(f.span, line, col) =>
        LookupResult(Some(f.typeSpec), Some(f.name), f.span)
    }

  private def findInExpr(expr: Expr, line: Int, col: Int): Option[LookupResult] =
    if isValidSpan(expr.span) && !containsPosition(expr.span, line, col) then None
    else
      // Only return results for actual terms, not for whitespace/comments within the expr span
      expr.terms.collectFirst {
        case t if termContains(t, line, col) => findInTerm(t, line, col)
      }.flatten

  private def termContains(t: Term, line: Int, col: Int): Boolean =
    containsOrInvalid(t.span, line, col)

  private def findInTerm(term: Term, line: Int, col: Int): Option[LookupResult] =
    term match
      case ref: Ref =>
        ref.qualifier match
          case Some(q) if containsPosition(q.span, line, col) => findInTerm(q, line, col)
          case _ => Some(LookupResult(ref.typeSpec, Some(ref.name), ref.span))

      case app: App =>
        findInApp(app, line, col)

      case cond: Cond =>
        findInExpr(cond.cond, line, col)
          .orElse(findInExpr(cond.ifTrue, line, col))
          .orElse(findInExpr(cond.ifFalse, line, col))

      case lambda: Lambda =>
        // Check body first - params may have overly broad spans
        findInExpr(lambda.body, line, col)
          .orElse(findInParams(lambda.params, line, col))

      case group: TermGroup =>
        findInExpr(group.inner, line, col)

      case tuple: Tuple =>
        tuple.elements.toList.collectFirst {
          case e if containsPosition(e.span, line, col) => findInExpr(e, line, col)
        }.flatten

      case expr: Expr =>
        findInExpr(expr, line, col)

      case lit: LiteralInt =>
        Some(LookupResult(lit.typeSpec, None, lit.span))

      case lit: LiteralString =>
        Some(LookupResult(lit.typeSpec, None, lit.span))

      case lit: LiteralBool =>
        Some(LookupResult(lit.typeSpec, None, lit.span))

      case lit: LiteralFloat =>
        Some(LookupResult(lit.typeSpec, None, lit.span))

      case lit: LiteralUnit =>
        Some(LookupResult(lit.typeSpec, None, lit.span))

      case hole: Hole =>
        Some(LookupResult(hole.typeSpec, Some("???"), hole.span))

      case ph: Placeholder =>
        Some(LookupResult(ph.typeSpec, Some("_"), ph.span))

      case dc: DataConstructor =>
        Some(LookupResult(dc.typeSpec, None, dc.span))

      case dd: DataDestructor =>
        Some(LookupResult(dd.typeSpec, None, dd.span))

      case ni: NativeImpl =>
        Some(LookupResult(ni.typeSpec, Some("@native"), ni.span))

      case inv: InvalidExpression =>
        findInExpr(inv.originalExpr, line, col)

      case _: TermError =>
        None

  private def findInApp(app: App, line: Int, col: Int): Option[LookupResult] =
    // Check arg first - fn may have overly broad span (e.g., lambda from let binding)
    val argResult =
      if containsOrInvalid(app.arg.span, line, col) then findInExpr(app.arg, line, col)
      else None

    argResult.orElse {
      app.fn match
        case ref: Ref if containsOrInvalid(ref.span, line, col) =>
          Some(LookupResult(ref.typeSpec, Some(ref.name), ref.span))
        case innerApp: App if containsOrInvalid(innerApp.span, line, col) =>
          findInApp(innerApp, line, col)
        case lambda: Lambda if containsOrInvalid(lambda.span, line, col) =>
          findInExpr(lambda.body, line, col)
            .orElse(findInParams(lambda.params, line, col))
        case _ => None
    }

  private def findInParams(params: List[FnParam], line: Int, col: Int): Option[LookupResult] =
    params.collectFirst {
      case p if containsPosition(p.span, line, col) && p.source.isFromSource =>
        LookupResult(p.typeSpec, Some(p.name), p.span)
    }

  private def findDefinitionInMembers(
    members: List[Member],
    line:    Int,
    col:     Int,
    module:  Module
  ): List[SrcSpan] =
    members
      .collectFirst {
        case m if memberContains(m, line, col) => findDefinitionInMember(m, line, col, module)
      }
      .getOrElse(Nil)

  private def findDefinitionInMember(
    member: Member,
    line:   Int,
    col:    Int,
    module: Module
  ): List[SrcSpan] =
    member match
      case bnd: Bnd =>
        val typeDefs = bnd.typeAsc.toList.flatMap(findDefinitionInType(_, line, col, module))
        if typeDefs.nonEmpty then typeDefs
        else if containsPosition(bnd.value.span, line, col) then
          val exprDefs = findDefinitionInExpr(bnd.value, line, col, module)
          if exprDefs.nonEmpty then exprDefs else spanForResolvable(bnd, module).toList
        else spanForResolvable(bnd, module).toList

      case td: TypeDef =>
        val typeDefs = td.typeSpec.toList.flatMap(findDefinitionInType(_, line, col, module))
        if typeDefs.nonEmpty then typeDefs else spanForResolvable(td, module).toList

      case ta: TypeAlias =>
        val typeDefs = findDefinitionInType(ta.typeRef, line, col, module)
        if typeDefs.nonEmpty then typeDefs else spanForResolvable(ta, module).toList

      case ts: TypeStruct =>
        val fieldDefs = findDefinitionInFields(ts.fields.toList, line, col, module)
        if fieldDefs.nonEmpty then fieldDefs else spanForResolvable(ts, module).toList

      case dm: DuplicateMember =>
        findDefinitionInMember(dm.originalMember, line, col, module)

      case im: InvalidMember =>
        findDefinitionInMember(im.originalMember, line, col, module)

      case _ => Nil

  private def findDefinitionInFields(
    fields: List[Field],
    line:   Int,
    col:    Int,
    module: Module
  ): List[SrcSpan] =
    fields
      .collectFirst {
        case field if containsPosition(field.span, line, col) =>
          val typeDefs = findDefinitionInType(field.typeSpec, line, col, module)
          if typeDefs.nonEmpty then typeDefs else List(field.span)
      }
      .getOrElse(Nil)

  private def findDefinitionInExpr(
    expr:   Expr,
    line:   Int,
    col:    Int,
    module: Module
  ): List[SrcSpan] =
    if isValidSpan(expr.span) && !containsPosition(expr.span, line, col) then Nil
    else
      expr.terms
        .collectFirst {
          case t if termContains(t, line, col) => findDefinitionInTerm(t, line, col, module)
        }
        .getOrElse(Nil)

  private def findDefinitionInTerm(
    term:   Term,
    line:   Int,
    col:    Int,
    module: Module
  ): List[SrcSpan] =
    term match
      case ref: Ref =>
        findDefinitionInRef(ref, line, col, module)

      case app: App =>
        findDefinitionInApp(app, line, col, module)

      case cond: Cond =>
        val condDefs = findDefinitionInExpr(cond.cond, line, col, module)
        if condDefs.nonEmpty then condDefs
        else
          val trueDefs = findDefinitionInExpr(cond.ifTrue, line, col, module)
          if trueDefs.nonEmpty then trueDefs
          else findDefinitionInExpr(cond.ifFalse, line, col, module)

      case lambda: Lambda =>
        val typeDefs = lambda.typeAsc.toList.flatMap(findDefinitionInType(_, line, col, module))
        if typeDefs.nonEmpty then typeDefs
        else
          val bodyDefs = findDefinitionInExpr(lambda.body, line, col, module)
          if bodyDefs.nonEmpty then bodyDefs
          else findDefinitionInParams(lambda.params, line, col, module)

      case group: TermGroup =>
        findDefinitionInExpr(group.inner, line, col, module)

      case tuple: Tuple =>
        tuple.elements.toList
          .collectFirst {
            case e if containsPosition(e.span, line, col) =>
              findDefinitionInExpr(e, line, col, module)
          }
          .getOrElse(Nil)

      case expr: Expr =>
        findDefinitionInExpr(expr, line, col, module)

      case inv: InvalidExpression =>
        findDefinitionInExpr(inv.originalExpr, line, col, module)

      case _: TermError =>
        Nil

      case _ =>
        Nil

  private def findDefinitionInRef(
    ref:    Ref,
    line:   Int,
    col:    Int,
    module: Module
  ): List[SrcSpan] =
    ref.qualifier match
      case Some(qualifier) if containsPosition(qualifier.span, line, col) =>
        findDefinitionInTerm(qualifier, line, col, module)
      case Some(qualifier) =>
        val baseType = qualifier.typeSpec
        baseType.flatMap(resolveStructType(_, module)) match
          case Some(struct) =>
            struct.fields.find(_.name == ref.name).map(_.span).toList
          case None =>
            Nil
      case None =>
        val resolved =
          ref.resolvedId
            .flatMap(module.resolvables.lookup)
            .toList
            .flatMap(spanForResolvable(_, module))
        if resolved.nonEmpty then resolved
        else
          ref.candidateIds.flatMap(module.resolvables.lookup).flatMap(spanForResolvable(_, module))

  private def findDefinitionInApp(
    app:    App,
    line:   Int,
    col:    Int,
    module: Module
  ): List[SrcSpan] =
    val argResult =
      if containsOrInvalid(app.arg.span, line, col) then
        findDefinitionInExpr(app.arg, line, col, module)
      else Nil

    if argResult.nonEmpty then argResult
    else
      app.fn match
        case ref: Ref if containsOrInvalid(ref.span, line, col) =>
          findDefinitionInRef(ref, line, col, module)
        case innerApp: App if containsOrInvalid(innerApp.span, line, col) =>
          findDefinitionInApp(innerApp, line, col, module)
        case lambda: Lambda if containsOrInvalid(lambda.span, line, col) =>
          val typeDefs = lambda.typeAsc.toList.flatMap(findDefinitionInType(_, line, col, module))
          if typeDefs.nonEmpty then typeDefs
          else
            val bodyDefs = findDefinitionInExpr(lambda.body, line, col, module)
            if bodyDefs.nonEmpty then bodyDefs
            else findDefinitionInParams(lambda.params, line, col, module)
        case _ => Nil

  private def findDefinitionInParams(
    params: List[FnParam],
    line:   Int,
    col:    Int,
    module: Module
  ): List[SrcSpan] =
    params
      .collectFirst {
        case p if containsPosition(p.span, line, col) && p.source.isFromSource =>
          val typeDefs = p.typeAsc.toList.flatMap(findDefinitionInType(_, line, col, module))
          if typeDefs.nonEmpty then typeDefs else spanForResolvable(p, module).toList
      }
      .getOrElse(Nil)

  private def findDefinitionInType(
    typeSpec: Type,
    line:     Int,
    col:      Int,
    module:   Module
  ): List[SrcSpan] =
    if !containsPosition(typeSpec.span, line, col) then Nil
    else
      typeSpec match
        case tr: TypeRef =>
          definitionForTypeRef(tr, module).toList

        case TypeFn(_, params, ret) =>
          params
            .collectFirst {
              case t if containsPosition(t.span, line, col) =>
                findDefinitionInType(t, line, col, module)
            }
            .getOrElse(findDefinitionInType(ret, line, col, module))

        case TypeTuple(_, elements) =>
          elements
            .collectFirst {
              case t if containsPosition(t.span, line, col) =>
                findDefinitionInType(t, line, col, module)
            }
            .getOrElse(Nil)

        case TypeOpenRecord(_, fields) =>
          fields
            .collectFirst {
              case (_, t) if containsPosition(t.span, line, col) =>
                findDefinitionInType(t, line, col, module)
            }
            .getOrElse(Nil)

        case ts: TypeStruct =>
          findDefinitionInFields(ts.fields.toList, line, col, module)

        case NativeStruct(_, fields, _, _) =>
          fields
            .collectFirst {
              case (_, t) if containsPosition(t.span, line, col) =>
                findDefinitionInType(t, line, col, module)
            }
            .getOrElse(Nil)

        case TypeApplication(_, base, args) =>
          val baseDefs = findDefinitionInType(base, line, col, module)
          if baseDefs.nonEmpty then baseDefs
          else
            args
              .collectFirst {
                case t if containsPosition(t.span, line, col) =>
                  findDefinitionInType(t, line, col, module)
              }
              .getOrElse(Nil)

        case Union(_, types) =>
          types
            .collectFirst {
              case t if containsPosition(t.span, line, col) =>
                findDefinitionInType(t, line, col, module)
            }
            .getOrElse(Nil)

        case Intersection(_, types) =>
          types
            .collectFirst {
              case t if containsPosition(t.span, line, col) =>
                findDefinitionInType(t, line, col, module)
            }
            .getOrElse(Nil)

        case TypeGroup(_, types) =>
          types
            .collectFirst {
              case t if containsPosition(t.span, line, col) =>
                findDefinitionInType(t, line, col, module)
            }
            .getOrElse(Nil)

        case TypeRefinement(_, _, expr) =>
          if containsPosition(expr.span, line, col) then
            findDefinitionInExpr(expr, line, col, module)
          else Nil

        case InvalidType(_, orig) =>
          findDefinitionInType(orig, line, col, module)

        case _ =>
          Nil

  private def definitionForTypeRef(ref: TypeRef, module: Module): Option[SrcSpan] =
    ref.resolvedId.flatMap(module.resolvables.lookupType) match
      case Some(resolvable: FromSource) => resolvable.source.spanOpt
      case _ =>
        module.members.collectFirst {
          case ta: TypeAlias if ta.name == ref.name => ta.span
          case td: TypeDef if td.name == ref.name => td.span
          case ts: TypeStruct if ts.name == ref.name => ts.span
        }

  private def spanForResolvable(resolvable: Resolvable, module: Module): Option[SrcSpan] =
    resolvable match
      case bnd: Bnd if bnd.source == SourceOrigin.Synth =>
        bnd.meta match
          case Some(m) if m.origin == BindingOrigin.Constructor =>
            module.members.collectFirst {
              case ts: TypeStruct if ts.name == m.originalName => ts.nameNode.span
            }
          case _ => None
      case param: FnParam if param.source == SourceOrigin.Synth => None
      case fs:    FromSource => fs.source.spanOpt
      case _ => None

  private def resolveStructType(typeSpec: Type, module: Module): Option[TypeStruct] =
    unwrapTypeGroup(resolveAliasChain(typeSpec, module)) match
      case ts: TypeStruct => Some(ts)
      case TypeRef(_, name, resolvedId, _) =>
        resolvedId
          .flatMap(module.resolvables.lookupType)
          .collect { case ts: TypeStruct => ts }
          .orElse(module.members.collectFirst { case ts: TypeStruct if ts.name == name => ts })
      case _ => None

  private def unwrapTypeGroup(typeSpec: Type): Type =
    typeSpec match
      case TypeGroup(_, types) if types.size == 1 => unwrapTypeGroup(types.head)
      case other => other

  private def resolveAliasChain(typeSpec: Type, module: Module): Type = typeSpec match
    case tr @ TypeRef(_, name, resolvedId, _) =>
      val resolved = resolvedId
        .flatMap(module.resolvables.lookupType)
        .orElse(module.members.collectFirst {
          case ta: TypeAlias if ta.name == name => ta
          case td: TypeDef if td.name == name => td
          case ts: TypeStruct if ts.name == name => ts
        })
      resolved match
        case Some(ta: TypeAlias) =>
          ta.typeSpec match
            case Some(resolvedSpec) => resolvedSpec
            case None => resolveAliasChain(ta.typeRef, module)
        case Some(td: TypeDef) =>
          TypeRef(tr.span, td.name, td.id)
        case Some(ts: TypeStruct) =>
          TypeRef(tr.span, ts.name, ts.id)
        case _ => tr
    case other => other

  private def findReferenceTargetInMembers(
    members: List[Member],
    line:    Int,
    col:     Int,
    module:  Module
  ): Option[ReferenceTarget] =
    members.collectFirst {
      case m if memberContains(m, line, col) => findReferenceTargetInMember(m, line, col, module)
    }.flatten

  private def findReferenceTargetInMember(
    member: Member,
    line:   Int,
    col:    Int,
    module: Module
  ): Option[ReferenceTarget] =
    member match
      case bnd: Bnd =>
        bnd.typeAsc match
          case Some(t) if containsPosition(t.span, line, col) =>
            findReferenceTargetInType(t, line, col, module)
          case _ =>
            if containsPosition(bnd.value.span, line, col) then
              findReferenceTargetInExpr(bnd.value, line, col, module)
            else valueTargetFromResolvable(bnd)

      case td: TypeDef =>
        td.typeSpec match
          case Some(t) if containsPosition(t.span, line, col) =>
            findReferenceTargetInType(t, line, col, module)
          case _ => td.id.map(TypeTarget(_))

      case ta: TypeAlias =>
        if containsPosition(ta.typeRef.span, line, col) then
          findReferenceTargetInType(ta.typeRef, line, col, module)
        else ta.id.map(TypeTarget(_))

      case ts: TypeStruct =>
        findReferenceTargetInFields(ts.fields.toList, line, col, module, ts.name)
          .orElse(ts.id.map(TypeTarget(_)))

      case dm: DuplicateMember =>
        findReferenceTargetInMember(dm.originalMember, line, col, module)

      case im: InvalidMember =>
        findReferenceTargetInMember(im.originalMember, line, col, module)

      case _ => None

  private def findReferenceTargetInFields(
    fields:     List[Field],
    line:       Int,
    col:        Int,
    module:     Module,
    structName: String
  ): Option[ReferenceTarget] =
    fields.collectFirst {
      case field if containsPosition(field.span, line, col) =>
        if containsPosition(field.typeSpec.span, line, col) then
          findReferenceTargetInType(field.typeSpec, line, col, module)
        else Some(FieldTarget(structName, field.name))
    }.flatten

  private def findReferenceTargetInExpr(
    expr:   Expr,
    line:   Int,
    col:    Int,
    module: Module
  ): Option[ReferenceTarget] =
    if isValidSpan(expr.span) && !containsPosition(expr.span, line, col) then None
    else
      expr.terms.collectFirst {
        case t if termContains(t, line, col) => findReferenceTargetInTerm(t, line, col, module)
      }.flatten

  private def findReferenceTargetInTerm(
    term:   Term,
    line:   Int,
    col:    Int,
    module: Module
  ): Option[ReferenceTarget] =
    term match
      case ref: Ref =>
        ref.typeAsc match
          case Some(t) if containsPosition(t.span, line, col) =>
            findReferenceTargetInType(t, line, col, module)
          case _ =>
            ref.qualifier match
              case Some(q) if containsPosition(q.span, line, col) =>
                findReferenceTargetInTerm(q, line, col, module)
              case Some(q) =>
                q.typeSpec.flatMap(resolveStructType(_, module)) match
                  case Some(struct) =>
                    struct.fields
                      .find(_.name == ref.name)
                      .map(field => FieldTarget(struct.name, field.name))
                  case None => None
              case None =>
                val resolved =
                  ref.resolvedId
                    .flatMap(module.resolvables.lookup)
                    .flatMap(valueTargetFromResolvable)
                resolved.orElse {
                  val candidates = ref.candidateIds.flatMap(module.resolvables.lookup)
                  if candidates.size == 1 then valueTargetFromResolvable(candidates.head)
                  else None
                }

      case app: App =>
        findReferenceTargetInApp(app, line, col, module)

      case cond: Cond =>
        findReferenceTargetInExpr(cond.cond, line, col, module) match
          case some @ Some(_) => some
          case None =>
            findReferenceTargetInExpr(cond.ifTrue, line, col, module) match
              case some @ Some(_) => some
              case None => findReferenceTargetInExpr(cond.ifFalse, line, col, module)

      case lambda: Lambda =>
        findReferenceTargetInExpr(lambda.body, line, col, module) match
          case some @ Some(_) => some
          case None => findReferenceTargetInParams(lambda.params, line, col, module)

      case group: TermGroup =>
        findReferenceTargetInExpr(group.inner, line, col, module)

      case tuple: Tuple =>
        tuple.elements.toList.collectFirst {
          case e if containsPosition(e.span, line, col) =>
            findReferenceTargetInExpr(e, line, col, module)
        }.flatten

      case expr: Expr =>
        findReferenceTargetInExpr(expr, line, col, module)

      case inv: InvalidExpression =>
        findReferenceTargetInExpr(inv.originalExpr, line, col, module)

      case _: TermError =>
        None

      case _ =>
        None

  private def findReferenceTargetInApp(
    app:    App,
    line:   Int,
    col:    Int,
    module: Module
  ): Option[ReferenceTarget] =
    if containsOrInvalid(app.arg.span, line, col) then
      findReferenceTargetInExpr(app.arg, line, col, module)
    else
      app.fn match
        case ref: Ref if containsOrInvalid(ref.span, line, col) =>
          findReferenceTargetInTerm(ref, line, col, module)
        case innerApp: App if containsOrInvalid(innerApp.span, line, col) =>
          findReferenceTargetInApp(innerApp, line, col, module)
        case lambda: Lambda if containsOrInvalid(lambda.span, line, col) =>
          findReferenceTargetInTerm(lambda, line, col, module)
        case _ => None

  private def findReferenceTargetInParams(
    params: List[FnParam],
    line:   Int,
    col:    Int,
    module: Module
  ): Option[ReferenceTarget] =
    params.collectFirst {
      case p if containsPosition(p.span, line, col) =>
        p.typeAsc match
          case Some(t) if containsPosition(t.span, line, col) =>
            findReferenceTargetInType(t, line, col, module)
          case _ => valueTargetFromResolvable(p)
    }.flatten

  private def findReferenceTargetInType(
    typeSpec: Type,
    line:     Int,
    col:      Int,
    module:   Module
  ): Option[ReferenceTarget] =
    if !containsPosition(typeSpec.span, line, col) then None
    else
      typeSpec match
        case tr: TypeRef =>
          typeTargetFromRef(tr, module)

        case TypeFn(_, params, ret) =>
          params
            .collectFirst {
              case t if containsPosition(t.span, line, col) =>
                findReferenceTargetInType(t, line, col, module)
            }
            .flatten
            .orElse(findReferenceTargetInType(ret, line, col, module))

        case TypeTuple(_, elements) =>
          elements.collectFirst {
            case t if containsPosition(t.span, line, col) =>
              findReferenceTargetInType(t, line, col, module)
          }.flatten

        case TypeOpenRecord(_, fields) =>
          fields.collectFirst {
            case (_, t) if containsPosition(t.span, line, col) =>
              findReferenceTargetInType(t, line, col, module)
          }.flatten

        case ts: TypeStruct =>
          findReferenceTargetInFields(ts.fields.toList, line, col, module, ts.name)

        case NativeStruct(_, fields, _, _) =>
          fields.collectFirst {
            case (_, t) if containsPosition(t.span, line, col) =>
              findReferenceTargetInType(t, line, col, module)
          }.flatten

        case TypeApplication(_, base, args) =>
          findReferenceTargetInType(base, line, col, module).orElse {
            args.collectFirst {
              case t if containsPosition(t.span, line, col) =>
                findReferenceTargetInType(t, line, col, module)
            }.flatten
          }

        case Union(_, types) =>
          types.collectFirst {
            case t if containsPosition(t.span, line, col) =>
              findReferenceTargetInType(t, line, col, module)
          }.flatten

        case Intersection(_, types) =>
          types.collectFirst {
            case t if containsPosition(t.span, line, col) =>
              findReferenceTargetInType(t, line, col, module)
          }.flatten

        case TypeGroup(_, types) =>
          types.collectFirst {
            case t if containsPosition(t.span, line, col) =>
              findReferenceTargetInType(t, line, col, module)
          }.flatten

        case TypeRefinement(_, _, expr) =>
          findReferenceTargetInExpr(expr, line, col, module)

        case InvalidType(_, orig) =>
          findReferenceTargetInType(orig, line, col, module)

        case _ =>
          None

  private def collectReferences(
    module:             Module,
    target:             ReferenceTarget,
    includeDeclaration: Boolean
  ): List[SrcSpan] =
    module.members.flatMap(collectReferencesInMember(_, target, includeDeclaration, module))

  private def collectReferencesInMember(
    member:             Member,
    target:             ReferenceTarget,
    includeDeclaration: Boolean,
    module:             Module
  ): List[SrcSpan] =
    member match
      case bnd: Bnd =>
        val declSpan =
          if includeDeclaration then
            target match
              case ValueTarget(targetId) if bnd.id.contains(targetId) =>
                spanForResolvable(bnd, module).toList
              case _ => Nil
          else Nil
        val typeRefs  = bnd.typeAsc.toList.flatMap(collectReferencesInType(_, target, module))
        val valueRefs = collectReferencesInExpr(bnd.value, target, includeDeclaration, module)
        declSpan ++ typeRefs ++ valueRefs

      case td: TypeDef =>
        val declSpan =
          if includeDeclaration then
            target match
              case TypeTarget(targetId) if td.id.contains(targetId) =>
                spanForResolvable(td, module).toList
              case _ => Nil
          else Nil
        val typeRefs = td.typeSpec.toList.flatMap(collectReferencesInType(_, target, module))
        declSpan ++ typeRefs

      case ta: TypeAlias =>
        val declSpan =
          if includeDeclaration then
            target match
              case TypeTarget(targetId) if ta.id.contains(targetId) =>
                spanForResolvable(ta, module).toList
              case _ => Nil
          else Nil
        val typeRefs = collectReferencesInType(ta.typeRef, target, module)
        declSpan ++ typeRefs

      case ts: TypeStruct =>
        val declSpan =
          if includeDeclaration then
            target match
              case TypeTarget(targetId) if ts.id.contains(targetId) =>
                spanForResolvable(ts, module).toList
              case _ => Nil
          else Nil
        val fieldDecls =
          if includeDeclaration then
            target match
              case FieldTarget(structName, fieldName) if structName == ts.name =>
                ts.fields.toList.collect { case field if field.name == fieldName => field.span }
              case _ => Nil
          else Nil
        val fieldTypes =
          ts.fields.toList.flatMap(field => collectReferencesInType(field.typeSpec, target, module))
        declSpan ++ fieldDecls ++ fieldTypes

      case dm: DuplicateMember =>
        collectReferencesInMember(dm.originalMember, target, includeDeclaration, module)

      case im: InvalidMember =>
        collectReferencesInMember(im.originalMember, target, includeDeclaration, module)

      case _ => Nil

  private def collectReferencesInExpr(
    expr:               Expr,
    target:             ReferenceTarget,
    includeDeclaration: Boolean,
    module:             Module
  ): List[SrcSpan] =
    expr.terms.flatMap(collectReferencesInTerm(_, target, includeDeclaration, module))

  private def collectReferencesInTerm(
    term:               Term,
    target:             ReferenceTarget,
    includeDeclaration: Boolean,
    module:             Module
  ): List[SrcSpan] =
    term match
      case ref: Ref =>
        val qualifierRefs =
          ref.qualifier.toList.flatMap(
            collectReferencesInTerm(_, target, includeDeclaration, module)
          )
        val typeRefs = ref.typeAsc.toList.flatMap(collectReferencesInType(_, target, module))
        val refSpan  = if matchesReferenceTarget(ref, target, module) then List(ref.span) else Nil
        qualifierRefs ++ typeRefs ++ refSpan

      case app: App =>
        collectReferencesInApp(app, target, includeDeclaration, module)

      case cond: Cond =>
        collectReferencesInExpr(cond.cond, target, includeDeclaration, module) ++
          collectReferencesInExpr(cond.ifTrue, target, includeDeclaration, module) ++
          collectReferencesInExpr(cond.ifFalse, target, includeDeclaration, module)

      case lambda: Lambda =>
        collectReferencesInParams(lambda.params, target, includeDeclaration, module) ++
          collectReferencesInExpr(lambda.body, target, includeDeclaration, module)

      case group: TermGroup =>
        collectReferencesInExpr(group.inner, target, includeDeclaration, module)

      case tuple: Tuple =>
        tuple.elements.toList.flatMap(
          collectReferencesInExpr(_, target, includeDeclaration, module)
        )

      case expr: Expr =>
        collectReferencesInExpr(expr, target, includeDeclaration, module)

      case inv: InvalidExpression =>
        collectReferencesInExpr(inv.originalExpr, target, includeDeclaration, module)

      case _ =>
        Nil

  private def collectReferencesInApp(
    app:                App,
    target:             ReferenceTarget,
    includeDeclaration: Boolean,
    module:             Module
  ): List[SrcSpan] =
    val argRefs = collectReferencesInExpr(app.arg, target, includeDeclaration, module)
    val fnRefs = app.fn match
      case ref: Ref =>
        collectReferencesInTerm(ref, target, includeDeclaration, module)
      case innerApp: App =>
        collectReferencesInApp(innerApp, target, includeDeclaration, module)
      case lambda: Lambda =>
        collectReferencesInTerm(lambda, target, includeDeclaration, module)
    argRefs ++ fnRefs

  private def collectReferencesInParams(
    params:             List[FnParam],
    target:             ReferenceTarget,
    includeDeclaration: Boolean,
    module:             Module
  ): List[SrcSpan] =
    params.flatMap { param =>
      val declSpan =
        if includeDeclaration then
          target match
            case ValueTarget(targetId) if param.id.contains(targetId) =>
              spanForResolvable(param, module).toList
            case _ => Nil
        else Nil
      val typeRefs = param.typeAsc.toList.flatMap(collectReferencesInType(_, target, module))
      declSpan ++ typeRefs
    }

  private def collectReferencesInType(
    typeSpec: Type,
    target:   ReferenceTarget,
    module:   Module
  ): List[SrcSpan] =
    typeSpec match
      case tr: TypeRef =>
        if matchesTypeTarget(tr, target, module) then List(tr.span) else Nil

      case TypeFn(_, params, ret) =>
        params.flatMap(collectReferencesInType(_, target, module)) ++
          collectReferencesInType(ret, target, module)

      case TypeTuple(_, elements) =>
        elements.flatMap(collectReferencesInType(_, target, module))

      case TypeOpenRecord(_, fields) =>
        fields.flatMap { case (_, t) => collectReferencesInType(t, target, module) }

      case ts: TypeStruct =>
        ts.fields.toList.flatMap(field => collectReferencesInType(field.typeSpec, target, module))

      case NativeStruct(_, fields, _, _) =>
        fields.flatMap { case (_, t) => collectReferencesInType(t, target, module) }

      case TypeApplication(_, base, args) =>
        collectReferencesInType(base, target, module) ++
          args.flatMap(collectReferencesInType(_, target, module))

      case Union(_, types) =>
        types.flatMap(collectReferencesInType(_, target, module))

      case Intersection(_, types) =>
        types.flatMap(collectReferencesInType(_, target, module))

      case TypeGroup(_, types) =>
        types.flatMap(collectReferencesInType(_, target, module))

      case TypeRefinement(_, _, expr) =>
        collectReferencesInExpr(expr, target, includeDeclaration = false, module)

      case InvalidType(_, orig) =>
        collectReferencesInType(orig, target, module)

      case _ =>
        Nil

  private def matchesReferenceTarget(
    ref:    Ref,
    target: ReferenceTarget,
    module: Module
  ): Boolean =
    target match
      case ValueTarget(targetId) =>
        ref.qualifier.isEmpty && (
          ref.resolvedId.contains(targetId) ||
            (ref.resolvedId.isEmpty && ref.candidateIds.contains(targetId))
        )

      case FieldTarget(structName, fieldName) =>
        ref.qualifier match
          case Some(qualifier) =>
            ref.name == fieldName &&
            qualifier.typeSpec.flatMap(resolveStructType(_, module)).exists(_.name == structName)
          case None => false

      case _ => false

  private def matchesTypeTarget(
    typeRef: TypeRef,
    target:  ReferenceTarget,
    module:  Module
  ): Boolean =
    target match
      case TypeTarget(targetId) =>
        typeRef.resolvedId.contains(targetId) ||
        (typeRef.resolvedId.isEmpty && module.resolvables
          .lookupType(targetId)
          .exists(_.name == typeRef.name))
      case _ => false

  private def valueTargetFromResolvable(resolvable: Resolvable): Option[ValueTarget] =
    resolvable match
      case bnd:   Bnd if bnd.source == SourceOrigin.Synth => None
      case param: FnParam if param.source == SourceOrigin.Synth => None
      case _ => resolvable.id.map(ValueTarget(_))

  private def typeTargetFromRef(ref: TypeRef, module: Module): Option[ReferenceTarget] =
    ref.resolvedId.map(TypeTarget(_)).orElse {
      module.members
        .collectFirst {
          case ta: TypeAlias if ta.name == ref.name => ta.id
          case td: TypeDef if td.name == ref.name => td.id
          case ts: TypeStruct if ts.name == ref.name => ts.id
        }
        .flatten
        .map(TypeTarget(_))
    }

  /** Collect all symbols in a module for workspace/symbol. Uses indexes, skips stdlib. */
  def collectSymbols(module: Module, uri: String): List[SymbolInformation] =
    val valueSymbols = module.resolvables.resolvables.values.toList.collect {
      case bnd: Bnd if !isStdlib(bnd.id) && bnd.source.isFromSource =>
        val isFunction = bnd.value.terms.headOption.exists(_.isInstanceOf[Lambda])
        val isOperator = bnd.name.exists(c => !c.isLetterOrDigit && c != '_')
        val kind =
          if isOperator then SymbolKind.Operator
          else if isFunction then SymbolKind.Function
          else SymbolKind.Variable
        SymbolInformation(bnd.name, kind, Location(uri, Range.fromSrcSpan(bnd.span)))
    }

    val typeSymbols = module.resolvables.resolvableTypes.values.toList.collect {
      case ts: TypeStruct if !isStdlib(ts.id) =>
        SymbolInformation(ts.name, SymbolKind.Struct, Location(uri, Range.fromSrcSpan(ts.span)))
      case td: TypeDef if !isStdlib(td.id) =>
        SymbolInformation(
          td.name,
          SymbolKind.TypeParameter,
          Location(uri, Range.fromSrcSpan(td.span))
        )
      case ta: TypeAlias if !isStdlib(ta.id) =>
        SymbolInformation(
          ta.name,
          SymbolKind.TypeParameter,
          Location(uri, Range.fromSrcSpan(ta.span))
        )
    }

    valueSymbols ++ typeSymbols

  private def isStdlib(id: Option[String]): Boolean =
    id.exists(_.startsWith("stdlib::"))

  /** Format a type for display in hover. */
  def formatType(typeSpec: Option[Type]): String =
    typeSpec match
      case None => "unknown"
      case Some(t) => formatTypeInner(t)

  private def formatTypeInner(t: Type): String =
    t match
      case TypeRef(_, name, _, _) => name

      case TypeFn(_, params, ret) =>
        val paramStr = params.map(formatTypeInner).mkString(" -> ")
        val retStr   = formatTypeInner(ret)
        if params.isEmpty then s"() -> $retStr"
        else s"$paramStr -> $retStr"

      case TypeTuple(_, elements) =>
        elements.map(formatTypeInner).mkString("(", ", ", ")")

      case TypeOpenRecord(_, fields) =>
        val fieldStr = fields.map { case (n, t) => s"$n: ${formatTypeInner(t)}" }.mkString(", ")
        s"{ $fieldStr }"

      case ts: TypeStruct =>
        ts.name

      case TypeUnit(_) => "()"

      case TypeVariable(_, name) => name

      case TypeScheme(_, vars, body) =>
        if vars.isEmpty then formatTypeInner(body)
        else s"∀${vars.mkString(" ")}. ${formatTypeInner(body)}"

      case Union(_, types) =>
        types.map(formatTypeInner).mkString(" | ")

      case Intersection(_, types) =>
        types.map(formatTypeInner).mkString(" & ")

      case TypeApplication(_, base, args) =>
        s"${formatTypeInner(base)} ${args.map(formatTypeInner).mkString(" ")}"

      case TypeGroup(_, types) =>
        types.map(formatTypeInner).mkString("(", " ", ")")

      case NativePrimitive(_, llvm, _, _) => s"@native[t=$llvm]"

      case NativePointer(_, llvm, _, _) => s"@native[t=*$llvm]"

      case NativeStruct(_, fields, _, _) =>
        val fieldStr = fields.map { case (n, t) => s"$n: ${formatTypeInner(t)}" }.mkString(", ")
        s"@native { $fieldStr }"

      case TypeRefinement(_, id, _) =>
        id.map(i => s"$i refined").getOrElse("refined")

      case InvalidType(_, orig) =>
        s"invalid(${formatTypeInner(orig)})"

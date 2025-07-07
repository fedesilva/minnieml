package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object TypeResolver:

  private val phaseName = "mml.mmlclib.semantic.TypeResolver"

  /** Resolve all type references in a module, accumulating errors in the state. */
  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState =
    val (errors, members) =
      state.module.members.foldLeft((List.empty[SemanticError], List.empty[Member])) {
        case ((accErrors, accMembers), member) =>
          // Create a temporary module with accumulated members for lookups
          val currentModule =
            state.module.copy(members = accMembers ++ state.module.members.dropWhile(_ != member))
          resolveMember(member, currentModule) match
            case Left(errs) =>
              // Important: Use the rewritten member with InvalidType nodes, not the original
              val rewrittenMember = rewriteMemberWithInvalidTypes(member, currentModule)
              (accErrors ++ errs, accMembers :+ rewrittenMember)
            case Right(updated) => (accErrors, accMembers :+ updated)
      }
    state.addErrors(errors).withModule(state.module.copy(members = members))

  /** Compute the typeSpec for a TypeAlias by following the resolved typeRef chain */
  private def computeTypeSpecForAlias(typeRef: TypeSpec, module: Module): Option[TypeSpec] =
    typeRef match
      case tr: TypeRef if tr.resolvedAs.isDefined =>
        tr.resolvedAs.get match
          case td: TypeDef => td.typeSpec
          case ta: TypeAlias =>
            ta.typeSpec match
              case Some(spec) => Some(spec)
              case None =>
                // If the alias doesn't have a typeSpec yet, try to follow its typeRef
                ta.typeRef match
                  case innerRef: TypeRef => computeTypeSpecForAlias(innerRef, module)
                  case _ => None
      case _ => None

  /** Rewrite a member to use InvalidType nodes for undefined type references */
  private def rewriteMemberWithInvalidTypes(member: Member, module: Module): Member =
    member match
      case bnd: Bnd =>
        bnd.copy(
          value   = rewriteExprWithInvalidTypes(bnd.value, member, module),
          typeAsc = bnd.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case fnDef: FnDef =>
        fnDef.copy(
          params = fnDef.params.map(p =>
            p.copy(typeAsc = p.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module)))
          ),
          body    = rewriteExprWithInvalidTypes(fnDef.body, member, module),
          typeAsc = fnDef.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case bin: BinOpDef =>
        bin.copy(
          param1 = bin.param1.copy(
            typeAsc = bin.param1.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
          ),
          param2 = bin.param2.copy(
            typeAsc = bin.param2.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
          ),
          body    = rewriteExprWithInvalidTypes(bin.body, member, module),
          typeAsc = bin.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case unary: UnaryOpDef =>
        unary.copy(
          param = unary.param.copy(
            typeAsc = unary.param.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
          ),
          body    = rewriteExprWithInvalidTypes(unary.body, member, module),
          typeAsc = unary.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case alias: TypeAlias =>
        val rewrittenTypeRef = rewriteTypeSpecWithInvalidTypes(alias.typeRef, module)
        val computedTypeSpec = computeTypeSpecForAlias(rewrittenTypeRef, module)
        alias.copy(typeRef = rewrittenTypeRef, typeSpec = computedTypeSpec)
      case typeDef: TypeDef =>
        typeDef.copy(
          typeSpec = typeDef.typeSpec.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case _ => member

  /** Rewrite type spec to use InvalidType for undefined references */
  private def rewriteTypeSpecWithInvalidTypes(
    typeSpec: TypeSpec,
    module:   Module
  ): TypeSpec =
    typeSpec match
      case typeRef: TypeRef =>
        val candidates = lookupTypeRefs(typeRef, module)
        candidates match
          case Nil =>
            // Return InvalidType instead of failing
            InvalidType(typeRef.span, typeRef)
          case single :: Nil =>
            typeRef.copy(resolvedAs = Some(single))
          case multiple =>
            // Also use InvalidType for ambiguous references
            InvalidType(typeRef.span, typeRef)
      case ns: NativeStruct =>
        // Recursively handle struct fields
        val rewrittenFields = ns.fields.map { case (fieldName, fieldType) =>
          fieldName -> rewriteTypeSpecWithInvalidTypes(fieldType, module)
        }
        ns.copy(fields = rewrittenFields)

      case tf: TypeFn =>
        // Recursively handle parameter types and return type
        val rewrittenParams = tf.paramTypes.map(rewriteTypeSpecWithInvalidTypes(_, module))
        val rewrittenReturn = rewriteTypeSpecWithInvalidTypes(tf.returnType, module)
        tf.copy(paramTypes = rewrittenParams, returnType = rewrittenReturn)

      case ta: TypeApplication =>
        // Recursively handle base type and type arguments
        val rewrittenBase = rewriteTypeSpecWithInvalidTypes(ta.base, module)
        val rewrittenArgs = ta.args.map(rewriteTypeSpecWithInvalidTypes(_, module))
        ta.copy(base = rewrittenBase, args = rewrittenArgs)

      case tt: TypeTuple =>
        // Recursively handle element types
        val rewrittenElements = tt.elements.map(rewriteTypeSpecWithInvalidTypes(_, module))
        tt.copy(elements = rewrittenElements)

      case ts: TypeStruct =>
        // Recursively handle field types
        val rewrittenFields = ts.fields.map { case (fieldName, fieldType) =>
          fieldName -> rewriteTypeSpecWithInvalidTypes(fieldType, module)
        }
        ts.copy(fields = rewrittenFields)

      case u: Union =>
        // Recursively handle union member types
        val rewrittenTypes = u.types.map(rewriteTypeSpecWithInvalidTypes(_, module))
        u.copy(types = rewrittenTypes)

      case i: Intersection =>
        // Recursively handle intersection member types
        val rewrittenTypes = i.types.map(rewriteTypeSpecWithInvalidTypes(_, module))
        i.copy(types = rewrittenTypes)

      case tg: TypeGroup =>
        // Recursively handle grouped types
        val rewrittenTypes = tg.types.map(rewriteTypeSpecWithInvalidTypes(_, module))
        tg.copy(types = rewrittenTypes)

      case other =>
        // TypeUnit, TypeRefinement (contains Expr, not TypeSpec), and others don't need rewriting
        other

  /** Rewrite expression to handle type ascriptions with invalid types */
  private def rewriteExprWithInvalidTypes(expr: Expr, member: Member, module: Module): Expr =
    expr.copy(
      terms   = expr.terms.map(rewriteTermWithInvalidTypes(_, member, module)),
      typeAsc = expr.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
    )

  /** Rewrite term to handle type ascriptions with invalid types */
  private def rewriteTermWithInvalidTypes(term: Term, member: Member, module: Module): Term =
    term match
      case ref: Ref =>
        ref.copy(typeAsc = ref.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module)))
      case group: TermGroup =>
        group.copy(
          inner   = rewriteExprWithInvalidTypes(group.inner, member, module),
          typeAsc = group.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case e: Expr =>
        rewriteExprWithInvalidTypes(e, member, module)
      case t: Tuple =>
        t.copy(
          elements = t.elements.map(rewriteExprWithInvalidTypes(_, member, module)),
          typeAsc  = t.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case cond: Cond =>
        cond.copy(
          cond    = rewriteExprWithInvalidTypes(cond.cond, member, module),
          ifTrue  = rewriteExprWithInvalidTypes(cond.ifTrue, member, module),
          ifFalse = rewriteExprWithInvalidTypes(cond.ifFalse, member, module),
          typeAsc = cond.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case app: App =>
        app.copy(typeAsc = app.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module)))
      case placeholder: Placeholder =>
        placeholder.copy(typeAsc =
          placeholder.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case hole: Hole =>
        hole.copy(typeAsc = hole.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module)))
      case native: NativeImpl =>
        native.copy(typeAsc = native.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module)))
      case lit: LiteralValue =>
        // Rewrite typeSpec for literals with invalid types
        val rewrittenTypeSpec = lit.typeSpec.map(rewriteTypeSpecWithInvalidTypes(_, module))
        lit match {
          case l: LiteralInt => l.copy(typeSpec = rewrittenTypeSpec)
          case l: LiteralString => l.copy(typeSpec = rewrittenTypeSpec)
          case l: LiteralBool => l.copy(typeSpec = rewrittenTypeSpec)
          case l: LiteralUnit => l.copy(typeSpec = rewrittenTypeSpec)
          case l: LiteralFloat => l.copy(typeSpec = rewrittenTypeSpec)
        }
      case _ => term

  /** Resolve type references in a module member. */
  private def resolveMember(member: Member, module: Module): Either[List[SemanticError], Member] =
    member match
      case bnd: Bnd =>
        for
          updatedValue <- resolveExpr(bnd.value, member, module)
          updatedTypeAsc <- bnd.typeAsc.traverse(resolveTypeSpec(_, member, module))
        yield bnd.copy(value = updatedValue, typeAsc = updatedTypeAsc)

      case fnDef: FnDef =>
        for
          updatedParams <- fnDef.params.traverse(resolveParam(_, member, module))
          updatedBody <- resolveExpr(fnDef.body, member, module)
          updatedTypeAsc <- fnDef.typeAsc.traverse(resolveTypeSpec(_, member, module))
        yield fnDef.copy(params = updatedParams, body = updatedBody, typeAsc = updatedTypeAsc)

      case opDef: OpDef =>
        opDef match
          case bin: BinOpDef =>
            for
              updatedParam1 <- resolveParam(bin.param1, member, module)
              updatedParam2 <- resolveParam(bin.param2, member, module)
              updatedBody <- resolveExpr(bin.body, member, module)
              updatedTypeAsc <- bin.typeAsc.traverse(resolveTypeSpec(_, member, module))
            yield bin.copy(
              param1  = updatedParam1,
              param2  = updatedParam2,
              body    = updatedBody,
              typeAsc = updatedTypeAsc
            )
          case unary: UnaryOpDef =>
            for
              updatedParam <- resolveParam(unary.param, member, module)
              updatedBody <- resolveExpr(unary.body, member, module)
              updatedTypeAsc <- unary.typeAsc.traverse(resolveTypeSpec(_, member, module))
            yield unary.copy(
              param   = updatedParam,
              body    = updatedBody,
              typeAsc = updatedTypeAsc
            )

      case alias: TypeAlias =>
        resolveTypeSpec(alias.typeRef, member, module).map { updatedTypeRef =>
          // Compute the typeSpec by following the resolved typeRef chain
          val computedTypeSpec = computeTypeSpecForAlias(updatedTypeRef, module)
          alias.copy(typeRef = updatedTypeRef, typeSpec = computedTypeSpec)
        }

      case typeDef: TypeDef =>
        // Resolve type references within the TypeDef's typeSpec (e.g., for NativeStruct fields)
        typeDef.typeSpec
          .traverse(resolveTypeSpec(_, member, module))
          .map(updatedTypeSpec => typeDef.copy(typeSpec = updatedTypeSpec))

      case _ =>
        member.asRight[List[SemanticError]]

  /** Resolve type references in a function parameter. */
  private def resolveParam(
    param:  FnParam,
    member: Member,
    module: Module
  ): Either[List[SemanticError], FnParam] =
    param.typeAsc
      .traverse(resolveTypeSpec(_, member, module))
      .map(updatedTypeAsc => param.copy(typeAsc = updatedTypeAsc))

  /** Returns all type declarations (TypeDef, TypeAlias) whose name matches the type reference.
    */
  private def lookupTypeRefs(typeRef: TypeRef, module: Module): List[ResolvableType] =
    module.members.collect {
      case typeDef:   TypeDef if typeDef.name == typeRef.name => typeDef
      case typeAlias: TypeAlias if typeAlias.name == typeRef.name => typeAlias
    }

  /** Resolve type references in a type specification.
    *
    * Currently only handles TypeRef nodes, as that's all the parser produces. In the future, this
    * will need to handle other TypeSpec variants recursively.
    */
  private def resolveTypeSpec(
    typeSpec: TypeSpec,
    member:   Member,
    module:   Module
  ): Either[List[SemanticError], TypeSpec] =
    typeSpec match
      case typeRef: TypeRef =>
        val candidates = lookupTypeRefs(typeRef, module)
        candidates match
          case Nil => List(SemanticError.UndefinedTypeRef(typeRef, member, phaseName)).asLeft
          case single :: Nil => typeRef.copy(resolvedAs = Some(single)).asRight
          case multiple =>
            // This shouldn't happen with proper duplicate checking, but let's be defensive
            List(SemanticError.DuplicateName(typeRef.name, multiple, phaseName)).asLeft

      case ns: NativeStruct =>
        // Resolve TypeSpecs in all struct fields
        ns.fields.toList
          .traverse { case (fieldName, fieldType) =>
            resolveTypeSpec(fieldType, member, module).map(fieldName -> _)
          }
          .map(resolvedFields => ns.copy(fields = resolvedFields.toMap))

      case tf: TypeFn =>
        // Resolve TypeSpecs in parameter types and return type
        for
          resolvedParams <- tf.paramTypes.traverse(resolveTypeSpec(_, member, module))
          resolvedReturn <- resolveTypeSpec(tf.returnType, member, module)
        yield tf.copy(paramTypes = resolvedParams, returnType = resolvedReturn)

      case ta: TypeApplication =>
        // Resolve base type and type arguments
        for
          resolvedBase <- resolveTypeSpec(ta.base, member, module)
          resolvedArgs <- ta.args.traverse(resolveTypeSpec(_, member, module))
        yield ta.copy(base = resolvedBase, args = resolvedArgs)

      case tt: TypeTuple =>
        // Resolve element types
        tt.elements
          .traverse(resolveTypeSpec(_, member, module))
          .map(resolvedElements => tt.copy(elements = resolvedElements))

      case ts: TypeStruct =>
        // Resolve field types
        ts.fields
          .traverse { case (fieldName, fieldType) =>
            resolveTypeSpec(fieldType, member, module).map(fieldName -> _)
          }
          .map(resolvedFields => ts.copy(fields = resolvedFields))

      case u: Union =>
        // Resolve union member types
        u.types
          .traverse(resolveTypeSpec(_, member, module))
          .map(resolvedTypes => u.copy(types = resolvedTypes))

      case i: Intersection =>
        // Resolve intersection member types
        i.types
          .traverse(resolveTypeSpec(_, member, module))
          .map(resolvedTypes => i.copy(types = resolvedTypes))

      case tg: TypeGroup =>
        // Resolve grouped types
        tg.types
          .traverse(resolveTypeSpec(_, member, module))
          .map(resolvedTypes => tg.copy(types = resolvedTypes))

      case other =>
        // TypeUnit, TypeRefinement (contains Expr, not TypeSpec), and others don't need resolution
        other.asRight[List[SemanticError]]

  /** Resolve type references in an expression.
    *
    * This includes type ascriptions on terms and nested expressions.
    */
  private def resolveExpr(
    expr:   Expr,
    member: Member,
    module: Module
  ): Either[List[SemanticError], Expr] =
    for
      updatedTerms <- expr.terms.traverse(resolveTerm(_, member, module))
      updatedTypeAsc <- expr.typeAsc.traverse(resolveTypeSpec(_, member, module))
    yield expr.copy(terms = updatedTerms, typeAsc = updatedTypeAsc)

  /** Resolve type references in a term.
    */
  private def resolveTerm(
    term:   Term,
    member: Member,
    module: Module
  ): Either[List[SemanticError], Term] =
    term match
      case ref: Ref =>
        ref.typeAsc
          .traverse(resolveTypeSpec(_, member, module))
          .map(updatedTypeAsc => ref.copy(typeAsc = updatedTypeAsc))

      case group: TermGroup =>
        for
          updatedInner <- resolveExpr(group.inner, member, module)
          updatedTypeAsc <- group.typeAsc.traverse(resolveTypeSpec(_, member, module))
        yield group.copy(inner = updatedInner, typeAsc = updatedTypeAsc)

      case e: Expr =>
        resolveExpr(e, member, module)

      case t: Tuple =>
        for
          updatedElements <- t.elements.traverse(resolveExpr(_, member, module))
          updatedTypeAsc <- t.typeAsc.traverse(resolveTypeSpec(_, member, module))
        yield t.copy(elements = updatedElements, typeAsc = updatedTypeAsc)

      case cond: Cond =>
        for
          updatedCond <- resolveExpr(cond.cond, member, module)
          updatedIfTrue <- resolveExpr(cond.ifTrue, member, module)
          updatedIfFalse <- resolveExpr(cond.ifFalse, member, module)
          updatedTypeAsc <- cond.typeAsc.traverse(resolveTypeSpec(_, member, module))
        yield cond.copy(
          cond    = updatedCond,
          ifTrue  = updatedIfTrue,
          ifFalse = updatedIfFalse,
          typeAsc = updatedTypeAsc
        )

      case app: App =>
        app.typeAsc
          .traverse(resolveTypeSpec(_, member, module))
          .map(updatedTypeAsc => app.copy(typeAsc = updatedTypeAsc))

      case placeholder: Placeholder =>
        placeholder.typeAsc
          .traverse(resolveTypeSpec(_, member, module))
          .map(updatedTypeAsc => placeholder.copy(typeAsc = updatedTypeAsc))

      case hole: Hole =>
        hole.typeAsc
          .traverse(resolveTypeSpec(_, member, module))
          .map(updatedTypeAsc => hole.copy(typeAsc = updatedTypeAsc))

      case native: NativeImpl =>
        native.typeAsc
          .traverse(resolveTypeSpec(_, member, module))
          .map(updatedTypeAsc => native.copy(typeAsc = updatedTypeAsc))

      case lit: LiteralValue =>
        // Resolve typeSpec for literals
        lit.typeSpec match {
          case Some(ts) =>
            resolveTypeSpec(ts, member, module).map { resolvedTypeSpec =>
              lit match {
                case l: LiteralInt => l.copy(typeSpec = Some(resolvedTypeSpec))
                case l: LiteralString => l.copy(typeSpec = Some(resolvedTypeSpec))
                case l: LiteralBool => l.copy(typeSpec = Some(resolvedTypeSpec))
                case l: LiteralUnit => l.copy(typeSpec = Some(resolvedTypeSpec))
                case l: LiteralFloat => l.copy(typeSpec = Some(resolvedTypeSpec))
              }
            }
          case None => Right(lit)
        }

      case _ =>
        // Other terms don't have type ascriptions that need resolution
        term.asRight[List[SemanticError]]

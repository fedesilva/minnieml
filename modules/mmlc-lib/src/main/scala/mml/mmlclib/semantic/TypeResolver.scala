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
          resolveMember(member, state.module) match
            case Left(errs) =>
              // Important: Use the rewritten member with InvalidType nodes, not the original
              val rewrittenMember = rewriteMemberWithInvalidTypes(member, state.module)
              (accErrors ++ errs, accMembers :+ rewrittenMember)
            case Right(updated) => (accErrors, accMembers :+ updated)
      }
    state.addErrors(errors).withModule(state.module.copy(members = members))

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
        alias.copy(typeRef = rewriteTypeSpecWithInvalidTypes(alias.typeRef, module))
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
      case other =>
        // For now, other type specs don't contain TypeRefs that need resolution
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
        resolveTypeSpec(alias.typeRef, member, module).map(updatedTypeRef =>
          alias.copy(typeRef = updatedTypeRef)
        )

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

      case other =>
        // For now, other type specs don't contain TypeRefs that need resolution
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

      case _ =>
        // Literals don't have type ascriptions that need resolution
        term.asRight[List[SemanticError]]

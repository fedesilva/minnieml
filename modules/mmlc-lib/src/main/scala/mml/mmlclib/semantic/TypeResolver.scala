package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object TypeResolver:

  /** Resolve all type references in a module. Returns either a list of errors or a new module with
    * resolved type references.
    *
    * This phase assumes that all references are checked by the duplicate name checker.
    */
  def rewriteModule(module: Module): Either[List[SemanticError], Module] =
    val updatedMembers = module.members.map(member => resolveMember(member, module))
    val errors         = updatedMembers.collect { case Left(errs) => errs }.flatten
    if errors.nonEmpty then errors.asLeft
    else module.copy(members = updatedMembers.collect { case Right(member) => member }).asRight

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
          case Nil => List(SemanticError.UndefinedTypeRef(typeRef, member)).asLeft
          case single :: Nil => typeRef.copy(resolvedAs = Some(single)).asRight
          case multiple =>
            // This shouldn't happen with proper duplicate checking, but let's be defensive
            List(SemanticError.DuplicateName(typeRef.name, multiple)).asLeft

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

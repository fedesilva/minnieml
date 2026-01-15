package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

object TypeResolver:

  private val phaseName = "mml.mmlclib.semantic.TypeResolver"

  /** Resolve all type references in a module, accumulating errors in the state. */
  def rewriteModule(state: CompilerState): CompilerState =
    // Phase 1: Build an initial type map from all TypeDef and TypeAlias members
    val initialTypeMap = buildTypeMap(state.module.members)

    // Phase 2: Resolve the type definitions themselves using the initial map
    // This ensures that TypeAlias objects in the map have resolved typeRefs
    // Run twice so TypeStruct/NativeStruct fields resolve against computed type alias specs.
    val resolvedTypeMap = resolveTypeMap(resolveTypeMap(initialTypeMap))

    // Phase 3: Resolve all members using the resolved type map
    val (errors, members) =
      state.module.members.map { member =>
        resolveMemberWithMap(member, resolvedTypeMap) match
          case Left(errs) =>
            // Important: Use the rewritten member with InvalidType nodes, not the original
            val rewrittenMember =
              rewriteMemberWithInvalidTypes(member, state.module.copy(members = Nil))
            (errs, rewrittenMember)
          case Right(updated) => (Nil, updated)
      }.unzip

    // Phase 4: Update the resolvables index with resolved members
    val updatedResolvables = members.foldLeft(state.module.resolvables) { (idx, member) =>
      member match
        case bnd: Bnd => idx.updated(bnd)
        case td:  TypeDef => idx.updatedType(td)
        case ta:  TypeAlias => idx.updatedType(ta)
        case ts:  TypeStruct => idx.updatedType(ts)
        case _ => idx
    }

    state
      .addErrors(errors.flatten)
      .withModule(state.module.copy(members = members, resolvables = updatedResolvables))

  /** Build a map of all type names to their definitions */
  private def buildTypeMap(members: List[Member]): Map[String, ResolvableType] =
    members.collect {
      case td: TypeDef => td.name -> td
      case ta: TypeAlias => ta.name -> ta
      case ts: TypeStruct => ts.name -> ts
    }.toMap

  /** Resolve type references within the type map itself */
  private def resolveTypeMap(
    typeMap: Map[String, ResolvableType]
  ): Map[String, ResolvableType] =
    typeMap.map { case (name, resolvable) =>
      resolvable match
        case td: TypeDef =>
          // Resolve TypeDef's typeSpec if it contains TypeRefs (e.g., in NativeStruct fields)
          val resolvedTypeSpec = td.typeSpec.flatMap { spec =>
            resolveTypeSpecWithMap(spec, td, typeMap).toOption
          }
          name -> td.copy(typeSpec = resolvedTypeSpec)

        case ta: TypeAlias =>
          // Resolve the TypeAlias's typeRef and compute its typeSpec
          val resolvedTypeRef =
            resolveTypeSpecWithMap(ta.typeRef, ta, typeMap).toOption.getOrElse(ta.typeRef)
          val computedTypeSpec = computeTypeSpecForAliasWithMap(resolvedTypeRef, typeMap)
          name -> ta.copy(typeRef = resolvedTypeRef, typeSpec = computedTypeSpec)

        case ts: TypeStruct =>
          val resolvedFields = ts.fields.map { field =>
            val resolvedType =
              resolveTypeSpecWithMap(field.typeSpec, ts, typeMap).toOption.getOrElse(field.typeSpec)
            field.copy(typeSpec = resolvedType)
          }
          name -> ts.copy(fields = resolvedFields)

    }

  /** Compute the typeSpec for a TypeAlias by following the resolved typeRef chain */
  private def computeTypeSpecForAlias(typeRef: Type, module: Module): Option[Type] =
    typeRef match
      case tr: TypeRef =>
        tr.resolvedId.flatMap(module.resolvables.lookupType) match
          case Some(td: TypeDef) =>
            // Return TypeRef to the TypeDef, not its native typeSpec
            // This ensures type aliases resolve to MML types rather than native representations
            Some(TypeRef(tr.span, td.name, td.id))
          case Some(ta: TypeAlias) =>
            ta.typeSpec match
              case Some(spec) => Some(spec)
              case None =>
                // If the alias doesn't have a typeSpec yet, try to follow its typeRef
                ta.typeRef match
                  case innerRef: TypeRef => computeTypeSpecForAlias(innerRef, module)
                  case _ => None
          case Some(ts: TypeStruct) =>
            Some(TypeRef(tr.span, ts.name, ts.id))
          case _ => None
      case _ => None

  /** Compute the typeSpec for a TypeAlias using pre-built type map */
  private def computeTypeSpecForAliasWithMap(
    typeRef: Type,
    typeMap: Map[String, ResolvableType]
  ): Option[Type] =
    typeRef match
      case tr: TypeRef =>
        // Look up by name in typeMap (during resolution, name matches the resolved target)
        typeMap.get(tr.name) match
          case Some(td: TypeDef) =>
            // Return TypeRef to the TypeDef, not its native typeSpec
            // This ensures type aliases resolve to MML types rather than native representations
            Some(TypeRef(tr.span, td.name, td.id))
          case Some(ta: TypeAlias) =>
            ta.typeSpec match
              case Some(spec) => Some(spec)
              case None =>
                // If the alias doesn't have a typeSpec yet, try to follow its typeRef
                ta.typeRef match
                  case innerRef: TypeRef => computeTypeSpecForAliasWithMap(innerRef, typeMap)
                  case _ => None
          case Some(ts: TypeStruct) =>
            Some(TypeRef(tr.span, ts.name, ts.id))
          case _ => None
      case _ => None

  /** Rewrite a member to use InvalidType nodes for undefined type references */
  private def rewriteMemberWithInvalidTypes(member: Member, module: Module): Member =
    member match
      case bnd: Bnd =>
        // Handle Bnd with Lambda - rewrite lambda params and body
        val rewrittenValue = bnd.value.terms match
          case (lambda: Lambda) :: rest =>
            val rewrittenParams = lambda.params.map(p =>
              p.copy(typeAsc = p.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module)))
            )
            val rewrittenBody    = rewriteExprWithInvalidTypes(lambda.body, member, module)
            val rewrittenTypeAsc = lambda.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
            val rewrittenLambda = lambda.copy(
              params  = rewrittenParams,
              body    = rewrittenBody,
              typeAsc = rewrittenTypeAsc
            )
            val rewrittenRest = rest.map(rewriteTermWithInvalidTypes(_, member, module))
            bnd.value.copy(terms = rewrittenLambda :: rewrittenRest)
          case _ =>
            rewriteExprWithInvalidTypes(bnd.value, member, module)

        bnd.copy(
          value   = rewrittenValue,
          typeAsc = bnd.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
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
    typeSpec: Type,
    module:   Module
  ): Type =
    typeSpec match
      case typeRef: TypeRef =>
        val candidates = lookupTypeRefs(typeRef, module)
        candidates match
          case Nil =>
            // Return InvalidType instead of failing
            InvalidType(typeRef.span, typeRef)
          case single :: Nil =>
            typeRef.copy(resolvedId = single.id)
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
        val rewrittenFields = ts.fields.map { field =>
          field.copy(typeSpec = rewriteTypeSpecWithInvalidTypes(field.typeSpec, module))
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
        val updatedQualifier =
          ref.qualifier.map(rewriteTermWithInvalidTypes(_, member, module))
        ref.copy(
          typeAsc   = ref.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module)),
          qualifier = updatedQualifier
        )
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
        val newFn  = rewriteAppFnWithInvalidTypes(app.fn, member, module)
        val newArg = rewriteExprWithInvalidTypes(app.arg, member, module)
        app.copy(
          fn      = newFn,
          arg     = newArg,
          typeAsc = app.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case lambda: Lambda =>
        val newBody = rewriteExprWithInvalidTypes(lambda.body, member, module)
        lambda.copy(
          body    = newBody,
          typeAsc = lambda.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
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

  /** Rewrite App.fn to handle type ascriptions with invalid types */
  private def rewriteAppFnWithInvalidTypes(
    fn:     Ref | App | Lambda,
    member: Member,
    module: Module
  ): Ref | App | Lambda =
    fn match
      case ref: Ref =>
        val updatedQualifier =
          ref.qualifier.map(rewriteTermWithInvalidTypes(_, member, module))
        ref.copy(
          typeAsc   = ref.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module)),
          qualifier = updatedQualifier
        )
      case app: App =>
        val newFn  = rewriteAppFnWithInvalidTypes(app.fn, member, module)
        val newArg = rewriteExprWithInvalidTypes(app.arg, member, module)
        app.copy(
          fn      = newFn,
          arg     = newArg,
          typeAsc = app.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )
      case lambda: Lambda =>
        val newBody = rewriteExprWithInvalidTypes(lambda.body, member, module)
        lambda.copy(
          body    = newBody,
          typeAsc = lambda.typeAsc.map(rewriteTypeSpecWithInvalidTypes(_, module))
        )

  /** Resolve type references in a module member using pre-built type map. */
  private def resolveMemberWithMap(
    member:  Member,
    typeMap: Map[String, ResolvableType]
  ): Either[List[SemanticError], Member] =
    member match
      case bnd: Bnd =>
        // Handle Bnd with Lambda (functions/operators) - resolve lambda params and body
        val resolvedValue = bnd.value.terms match
          case (lambda: Lambda) :: rest =>
            for
              updatedParams <- lambda.params.traverse(resolveParamWithMap(_, member, typeMap))
              updatedBody <- resolveExpr(lambda.body, member, typeMap)
              updatedLambdaTypeAsc <- lambda.typeAsc.traverse(
                resolveTypeSpecWithMap(_, member, typeMap)
              )
              updatedLambda = lambda.copy(
                params  = updatedParams,
                body    = updatedBody,
                typeAsc = updatedLambdaTypeAsc
              )
              updatedRest <- rest.traverse(resolveTerm(_, member, typeMap))
            yield bnd.value.copy(terms = updatedLambda :: updatedRest)
          case _ =>
            resolveExpr(bnd.value, member, typeMap)

        for
          updatedValue <- resolvedValue
          updatedTypeAsc <- bnd.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield bnd.copy(value = updatedValue, typeAsc = updatedTypeAsc)

      case alias: TypeAlias =>
        resolveTypeSpecWithMap(alias.typeRef, member, typeMap).map { updatedTypeRef =>
          // Compute the typeSpec by following the resolved typeRef chain
          val computedTypeSpec = computeTypeSpecForAliasWithMap(updatedTypeRef, typeMap)
          alias.copy(typeRef = updatedTypeRef, typeSpec = computedTypeSpec)
        }

      case typeDef: TypeDef =>
        // Resolve type references within the TypeDef's typeSpec (e.g., for NativeStruct fields)
        typeDef.typeSpec
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(updatedTypeSpec => typeDef.copy(typeSpec = updatedTypeSpec))

      case typeStruct: TypeStruct =>
        typeStruct.fields
          .traverse(field =>
            resolveTypeSpecWithMap(field.typeSpec, member, typeMap)
              .map(resolvedType => field.copy(typeSpec = resolvedType))
          )
          .map(updatedFields => typeStruct.copy(fields = updatedFields))

      case _ =>
        member.asRight[List[SemanticError]]

  /** Resolve type references in a function parameter using pre-built type map. */
  private def resolveParamWithMap(
    param:   FnParam,
    member:  Member,
    typeMap: Map[String, ResolvableType]
  ): Either[List[SemanticError], FnParam] =
    param.typeAsc
      .traverse(resolveTypeSpecWithMap(_, member, typeMap))
      .map(updatedTypeAsc => param.copy(typeAsc = updatedTypeAsc))

  /** Returns all type declarations (TypeDef, TypeAlias) whose name matches the type reference.
    */
  private def lookupTypeRefs(typeRef: TypeRef, module: Module): List[ResolvableType] =
    module.members.collect {
      case typeDef:    TypeDef if typeDef.name == typeRef.name => typeDef
      case typeAlias:  TypeAlias if typeAlias.name == typeRef.name => typeAlias
      case typeStruct: TypeStruct if typeStruct.name == typeRef.name => typeStruct
    }

  /** Resolve type references in a type specification using pre-built type map. */
  private def resolveTypeSpecWithMap(
    typeSpec: Type,
    member:   Member,
    typeMap:  Map[String, ResolvableType]
  ): Either[List[SemanticError], Type] =
    typeSpec match
      case typeRef: TypeRef =>
        typeMap.get(typeRef.name) match
          case None => List(SemanticError.UndefinedTypeRef(typeRef, member, phaseName)).asLeft
          case Some(resolved) => typeRef.copy(resolvedId = resolved.id).asRight

      case ns: NativeStruct =>
        // Resolve TypeSpecs in all struct fields
        ns.fields.toList
          .traverse { case (fieldName, fieldType) =>
            resolveTypeSpecWithMap(fieldType, member, typeMap).map(fieldName -> _)
          }
          .map(resolvedFields => ns.copy(fields = resolvedFields))

      case tf: TypeFn =>
        // Resolve TypeSpecs in parameter types and return type
        for
          resolvedParams <- tf.paramTypes.traverse(resolveTypeSpecWithMap(_, member, typeMap))
          resolvedReturn <- resolveTypeSpecWithMap(tf.returnType, member, typeMap)
        yield tf.copy(paramTypes = resolvedParams, returnType = resolvedReturn)

      case ta: TypeApplication =>
        // Resolve base type and type arguments
        for
          resolvedBase <- resolveTypeSpecWithMap(ta.base, member, typeMap)
          resolvedArgs <- ta.args.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield ta.copy(base = resolvedBase, args = resolvedArgs)

      case tt: TypeTuple =>
        // Resolve element types
        tt.elements
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(resolvedElements => tt.copy(elements = resolvedElements))

      case ts: TypeStruct =>
        // Resolve field types
        ts.fields
          .traverse(field =>
            resolveTypeSpecWithMap(field.typeSpec, member, typeMap)
              .map(resolvedType => field.copy(typeSpec = resolvedType))
          )
          .map(resolvedFields => ts.copy(fields = resolvedFields))

      case u: Union =>
        // Resolve union member types
        u.types
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(resolvedTypes => u.copy(types = resolvedTypes))

      case i: Intersection =>
        // Resolve intersection member types
        i.types
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(resolvedTypes => i.copy(types = resolvedTypes))

      case tg: TypeGroup =>
        // Resolve grouped types
        tg.types
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(resolvedTypes => tg.copy(types = resolvedTypes))

      case scheme: TypeScheme =>
        // Resolve body type (type variables don't need resolution)
        resolveTypeSpecWithMap(scheme.bodyType, member, typeMap)
          .map(resolvedBody => scheme.copy(bodyType = resolvedBody))

      case inv: InvalidType =>
        // Try to resolve the original type
        resolveTypeSpecWithMap(inv.originalType, member, typeMap)
          .map(resolvedOriginal => inv.copy(originalType = resolvedOriginal))

      case other =>
        // TypeUnit, TypeVariable, TypeRefinement (contains Expr, not TypeSpec), NativePrimitive, NativePointer don't need resolution
        other.asRight[List[SemanticError]]

  /** Resolve type references in an expression using the type map. */
  private def resolveExpr(
    expr:    Expr,
    member:  Member,
    typeMap: Map[String, ResolvableType]
  ): Either[List[SemanticError], Expr] =
    for
      updatedTerms <- expr.terms.traverse(resolveTerm(_, member, typeMap))
      updatedTypeAsc <- expr.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
    yield expr.copy(terms = updatedTerms, typeAsc = updatedTypeAsc)

  /** Resolve type references in a term using the type map. */
  private def resolveTerm(
    term:    Term,
    member:  Member,
    typeMap: Map[String, ResolvableType]
  ): Either[List[SemanticError], Term] =
    term match
      case ref: Ref =>
        for
          updatedQualifier <- ref.qualifier.traverse(resolveTerm(_, member, typeMap))
          updatedTypeAsc <- ref.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield ref.copy(typeAsc = updatedTypeAsc, qualifier = updatedQualifier)

      case group: TermGroup =>
        for
          updatedInner <- resolveExpr(group.inner, member, typeMap)
          updatedTypeAsc <- group.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield group.copy(inner = updatedInner, typeAsc = updatedTypeAsc)

      case e: Expr =>
        resolveExpr(e, member, typeMap)

      case t: Tuple =>
        for
          updatedElements <- t.elements.traverse(resolveExpr(_, member, typeMap))
          updatedTypeAsc <- t.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield t.copy(elements = updatedElements, typeAsc = updatedTypeAsc)

      case cond: Cond =>
        for
          updatedCond <- resolveExpr(cond.cond, member, typeMap)
          updatedIfTrue <- resolveExpr(cond.ifTrue, member, typeMap)
          updatedIfFalse <- resolveExpr(cond.ifFalse, member, typeMap)
          updatedTypeAsc <- cond.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield cond.copy(
          cond    = updatedCond,
          ifTrue  = updatedIfTrue,
          ifFalse = updatedIfFalse,
          typeAsc = updatedTypeAsc
        )

      case app: App =>
        for
          updatedFn <- resolveAppFn(app.fn, member, typeMap)
          updatedArg <- resolveExpr(app.arg, member, typeMap)
          updatedTypeAsc <- app.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield app.copy(fn = updatedFn, arg = updatedArg, typeAsc = updatedTypeAsc)

      case lambda: Lambda =>
        for
          updatedBody <- resolveExpr(lambda.body, member, typeMap)
          updatedTypeAsc <- lambda.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield lambda.copy(body = updatedBody, typeAsc = updatedTypeAsc)

      case placeholder: Placeholder =>
        placeholder.typeAsc
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(updatedTypeAsc => placeholder.copy(typeAsc = updatedTypeAsc))

      case hole: Hole =>
        hole.typeAsc
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(updatedTypeAsc => hole.copy(typeAsc = updatedTypeAsc))

      case native: NativeImpl =>
        native.typeAsc
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(updatedTypeAsc => native.copy(typeAsc = updatedTypeAsc))

      case lit: LiteralValue =>
        // Resolve typeSpec for literals
        lit.typeSpec match {
          case Some(ts) =>
            resolveTypeSpecWithMap(ts, member, typeMap).map { resolvedTypeSpec =>
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

  /** Resolve type references in App.fn (which can be Ref | App | Lambda) */
  private def resolveAppFn(
    fn:      Ref | App | Lambda,
    member:  Member,
    typeMap: Map[String, ResolvableType]
  ): Either[List[SemanticError], Ref | App | Lambda] =
    fn match
      case ref: Ref =>
        ref.typeAsc
          .traverse(resolveTypeSpecWithMap(_, member, typeMap))
          .map(updatedTypeAsc => ref.copy(typeAsc = updatedTypeAsc))
      case app: App =>
        for
          updatedFn <- resolveAppFn(app.fn, member, typeMap)
          updatedArg <- resolveExpr(app.arg, member, typeMap)
          updatedTypeAsc <- app.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield app.copy(fn = updatedFn, arg = updatedArg, typeAsc = updatedTypeAsc)
      case lambda: Lambda =>
        for
          updatedBody <- resolveExpr(lambda.body, member, typeMap)
          updatedTypeAsc <- lambda.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield lambda.copy(body = updatedBody, typeAsc = updatedTypeAsc)

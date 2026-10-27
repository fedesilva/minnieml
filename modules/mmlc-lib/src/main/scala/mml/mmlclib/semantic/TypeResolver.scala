package mml.mmlclib.semantic

import cats.data.State
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

object TypeResolver:

  private val phaseName = "mml.mmlclib.semantic.TypeResolver"

  /** Parser annotation caches share the exact reference instance with its written occurrence.
    * Structural equality would also merge independent references with equal contents.
    */
  private final class TypeOccurrence(val ref: TypeRef):

    override def hashCode(): Int = System.identityHashCode(ref)

    override def equals(other: Any): Boolean = other match
      case occurrence: TypeOccurrence => ref eq occurrence.ref
      case _ => false

  private case class ResolvedTypes(
    diagnostics:       DiagnosticStore,
    writtenReferences: Map[TypeOccurrence, Type] = Map.empty
  )

  private type Resolution[A] = State[ResolvedTypes, A]
  private type TypeMap       = Map[String, List[ResolvableType]]

  /** Resolve each type occurrence, preserving both independent failures and valid siblings. */
  def rewriteModule(state: CompilerState): CompilerState =
    val typeMap    = buildTypeMap(state.module.members)
    val resolution = state.module.members.traverse(resolveMemberWithMap(_, typeMap))
    val (resolved, resolvedMembers) = resolution.run(ResolvedTypes(state.diagnostics)).value
    val resolvedTypes = ResolvablesIndex().updatedAllTypes(
      resolvedMembers.collect { case declaration: ResolvableType => declaration }
    )
    val members = resolvedMembers.map {
      case alias: TypeAlias =>
        alias.copy(typeSpec = computeTypeSpecForAlias(alias.typeRef, resolvedTypes, Set.empty))
      case member => member
    }
    val module = ResolvablesIndexer.refresh(state.module.copy(members = members))
    state.copy(diagnostics = resolved.diagnostics).withModule(module)

  private def buildTypeMap(members: List[Member]): TypeMap =
    members.collect { case declaration: ResolvableType => declaration }.groupBy(_.name)

  /** Alias metadata retains nominal targets; native representation remains on the declaration. */
  @scala.annotation.tailrec
  private def computeTypeSpecForAlias(
    tpe:   Type,
    index: ResolvablesIndex,
    seen:  Set[String]
  ): Option[Type] =
    tpe match
      case ref: TypeRef if !ref.resolvedId.exists(seen.contains) =>
        ref.resolvedId.flatMap(index.lookupType) match
          case Some(alias: TypeAlias) =>
            computeTypeSpecForAlias(alias.typeRef, index, seen ++ ref.resolvedId)
          case Some(definition: TypeDef) =>
            TypeRef(ref.source, definition.name, definition.id).some
          case Some(struct: TypeStruct) =>
            TypeRef(ref.source, struct.name, struct.id).some
          case _ => none
      case _ => none

  /** Resolve type references in a module member using pre-built type map. */
  private def resolveMemberWithMap(
    member:  Member,
    typeMap: TypeMap
  ): Resolution[Member] =
    member match
      case bnd: Bnd => resolveBindingWithMap(bnd, typeMap).widen

      case protocol: Protocol =>
        protocol.signatures
          .traverse(resolveSignatureWithMap(_, typeMap))
          .map(signatures => protocol.copy(signatures = signatures))

      case instance: ProtocolInstance =>
        for
          concreteType <- resolveTypeSpecWithMap(instance.concreteType, instance, typeMap)
          members <- instance.members.traverse { member =>
            resolveBindingWithMap(member.binding, typeMap)
              .map(binding => member.copy(binding = binding))
          }
        yield instance.copy(concreteType = concreteType, members = members)

      case alias: TypeAlias =>
        resolveTypeSpecWithMap(alias.typeRef, member, typeMap)
          .map(updatedTypeRef => alias.copy(typeRef = updatedTypeRef))

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
        member.pure[Resolution]

  private def resolveBindingWithMap(binding: Bnd, typeMap: TypeMap): Resolution[Bnd] =
    for
      value <- resolveExpr(binding.value, binding, typeMap)
      typeAsc <- binding.typeAsc.traverse(resolveTypeSpecWithMap(_, binding, typeMap))
      typeSpec <- binding.typeSpec.traverse(resolveTypeSpecWithMap(_, binding, typeMap))
    yield binding.copy(value = value, typeAsc = typeAsc, typeSpec = typeSpec)

  private def resolveSignatureWithMap(
    signature: ProtocolSignature,
    typeMap:   TypeMap
  ): Resolution[ProtocolSignature] =
    for
      params <- signature.params.traverse(resolveParamWithMap(_, signature, typeMap))
      returnType <- resolveTypeSpecWithMap(signature.returnType, signature, typeMap)
    yield signature.copy(params = params, returnType = returnType)

  /** Resolve type references in a function parameter using pre-built type map. */
  private def resolveParamWithMap(
    param:   FnParam,
    member:  Member,
    typeMap: TypeMap
  ): Resolution[FnParam] =
    param.typeAsc
      .traverse(resolveTypeSpecWithMap(_, member, typeMap))
      .map(updatedTypeAsc => param.copy(typeAsc = updatedTypeAsc))

  private def resolveTypeReference(
    ref:     TypeRef,
    member:  Member,
    typeMap: TypeMap
  ): Resolution[Type] =
    def resolve: Resolution[Type] = typeMap.getOrElse(ref.name, Nil) match
      case Nil =>
        report(SemanticError.UndefinedTypeRef(ref, member, phaseName))
          .map(causes => UndefinedTypeError(ref.source, ref, causes))
      case resolved :: Nil => ref.copy(resolvedId = resolved.id).pure[Resolution]
      case candidates =>
        report(SemanticError.AmbiguousTypeRef(ref, candidates, member, phaseName))
          .map(causes => AmbiguousTypeError(ref.source, ref, candidates, causes))

    if !ref.source.isFromSource then resolve
    else
      val occurrence = TypeOccurrence(ref)
      State.inspect[ResolvedTypes, Option[Type]](_.writtenReferences.get(occurrence)).flatMap {
        case Some(resolved) => resolved.pure[Resolution]
        case None =>
          resolve.flatTap { resolved =>
            State.modify(current =>
              current.copy(
                writtenReferences = current.writtenReferences.updated(occurrence, resolved)
              )
            )
          }
      }

  private def report(error: SemanticError): Resolution[Vector[DiagnosticId]] =
    State { current =>
      val (diagnostics, roots) = current.diagnostics.emit(error)
      (current.copy(diagnostics = diagnostics), roots)
    }

  /** Resolve type references in a type specification using pre-built type map. */
  private def resolveTypeSpecWithMap(
    typeSpec: Type,
    member:   Member,
    typeMap:  TypeMap
  ): Resolution[Type] =
    typeSpec match
      case typeRef: TypeRef => resolveTypeReference(typeRef, member, typeMap)

      case refinement: TypeRefinement =>
        resolveExpr(refinement.expr, member, typeMap).map(expr => refinement.copy(expr = expr))

      case other =>
        TypeTraversal.traverseChildren(other)(resolveTypeSpecWithMap(_, member, typeMap))

  /** Resolve type references in an expression using the type map. */
  private def resolveExpr(
    expr:    Expr,
    member:  Member,
    typeMap: TypeMap
  ): Resolution[Expr] =
    for
      updatedTerms <- expr.terms.traverse(resolveTerm(_, member, typeMap))
      updatedTypeAsc <- expr.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
      updatedTypeSpec <- expr.typeSpec.traverse(resolveTypeSpecWithMap(_, member, typeMap))
    yield expr.copy(
      terms    = updatedTerms,
      typeAsc  = updatedTypeAsc,
      typeSpec = updatedTypeSpec
    )

  /** Resolve type references in a term using the type map. */
  private def resolveTerm(
    term:    Term,
    member:  Member,
    typeMap: TypeMap
  ): Resolution[Term] =
    term match
      case ref: Ref => resolveRef(ref, member, typeMap).widen

      case group: TermGroup =>
        for
          updatedInner <- resolveExpr(group.inner, member, typeMap)
          updatedTypeAsc <- group.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield group.copy(inner = updatedInner, typeAsc = updatedTypeAsc)

      case e: Expr =>
        resolveExpr(e, member, typeMap).widen

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
          updatedTypeSpec <- cond.typeSpec.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield cond.copy(
          cond     = updatedCond,
          ifTrue   = updatedIfTrue,
          ifFalse  = updatedIfFalse,
          typeAsc  = updatedTypeAsc,
          typeSpec = updatedTypeSpec
        )

      case app: App => resolveApp(app, member, typeMap).widen

      case lambda: Lambda => resolveLambda(lambda, member, typeMap).widen

      case invalid: InvalidExpression =>
        for
          original <- resolveExpr(invalid.originalExpr, member, typeMap)
          typeAsc <- invalid.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
          typeSpec <- invalid.typeSpec.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield invalid.copy(originalExpr = original, typeAsc = typeAsc, typeSpec = typeSpec)

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
        for
          spec <- lit.typeSpec.traverse(resolveTypeSpecWithMap(_, member, typeMap))
          ascription <- lit.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
        yield lit match
          case value: LiteralInt => value.copy(typeSpec = spec, typeAsc = ascription)
          case value: LiteralString => value.copy(typeSpec = spec, typeAsc = ascription)
          case value: LiteralBool => value.copy(typeSpec = spec, typeAsc = ascription)
          case value: LiteralUnit => value.copy(typeSpec = spec, typeAsc = ascription)
          case value: LiteralFloat => value.copy(typeSpec = spec, typeAsc = ascription)

      case _ =>
        // Other terms don't have type ascriptions that need resolution
        term.pure[Resolution]

  /** Resolve type references in application callees */
  private def resolveAppFn(
    fn:      Callee,
    member:  Member,
    typeMap: TypeMap
  ): Resolution[Callee] =
    fn match
      case ref:    Ref => resolveRef(ref, member, typeMap).widen
      case app:    App => resolveApp(app, member, typeMap).widen
      case lambda: Lambda => resolveLambda(lambda, member, typeMap).widen

  private def resolveRef(ref: Ref, member: Member, typeMap: TypeMap): Resolution[Ref] =
    for
      qualifier <- ref.qualifier.traverse(resolveTerm(_, member, typeMap))
      typeAsc <- ref.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
    yield ref.copy(qualifier = qualifier, typeAsc = typeAsc)

  private def resolveApp(app: App, member: Member, typeMap: TypeMap): Resolution[App] =
    for
      fn <- resolveAppFn(app.fn, member, typeMap)
      arg <- resolveExpr(app.arg, member, typeMap)
      typeAsc <- app.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
      calleeAscriptions <- app.calleeAscriptions.traverse(
        resolveTypeSpecWithMap(_, member, typeMap)
      )
    yield app.copy(fn = fn, arg = arg, typeAsc = typeAsc, calleeAscriptions = calleeAscriptions)

  private def resolveLambda(
    lambda:  Lambda,
    member:  Member,
    typeMap: TypeMap
  ): Resolution[Lambda] =
    for
      params <- lambda.params.traverse(resolveParamWithMap(_, member, typeMap))
      body <- resolveExpr(lambda.body, member, typeMap)
      typeAsc <- lambda.typeAsc.traverse(resolveTypeSpecWithMap(_, member, typeMap))
    yield lambda.copy(params = params, body = body, typeAsc = typeAsc)

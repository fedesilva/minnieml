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
  private final class TypeOccurrence(val ref: Type):

    override def hashCode(): Int = System.identityHashCode(ref)

    override def equals(other: Any): Boolean = other match
      case occurrence: TypeOccurrence => ref eq occurrence.ref
      case _ => false

  /** Diagnostics, binder allocation, and shared annotation occurrences retained during resolution.
    */
  private case class ResolvedTypes(
    diagnostics:       DiagnosticStore,
    bindingIds:        BindingIdSupply,
    writtenReferences: Map[TypeOccurrence, Type]         = Map.empty,
    writtenVariables:  Map[TypeOccurrence, TypeVariable] = Map.empty
  )

  private type Resolution[A] = State[ResolvedTypes, A]
  private type TypeMap       = Map[String, List[ResolvableType]]

  /** Name lookup and lexical type binders available at one position in the syntax tree. */
  private case class TypeScope(
    types:     TypeMap,
    protocols: List[Protocol],
    owner:     BindingOwner,
    variables: Map[String, TypeVariable] = Map.empty
  ):

    def bind(parameters: List[TypeVariable]): TypeScope =
      copy(variables = variables ++ parameters.map(variable => variable.name -> variable))

  /** Resolve each type occurrence, preserving both independent failures and valid siblings. */
  def rewriteModule(state: CompilerState): CompilerState =
    val scope = TypeScope(
      buildTypeMap(state.module.members),
      state.module.members.collect { case protocol: Protocol => protocol },
      BindingOwner(state.module.name)
    )
    // Resolve declarations first so an instance can inherit a later signature's type scope.
    val resolution = for
      declarations <- state.module.members.traverse(resolveMember(_, scope))
      resolvedScope = scope.copy(protocols = declarations.collect { case p: Protocol => p })
      members <- declarations.traverse {
        case instance: ProtocolInstance =>
          resolveInstanceBodies(instance, resolvedScope).widen[Member]
        case member => member.pure[Resolution]
      }
    yield members
    val (resolved, resolvedMembers) =
      resolution.run(ResolvedTypes(state.diagnostics, state.bindingIds)).value
    val resolvedTypes = ResolvablesIndex().updatedAllTypes(
      resolvedMembers.collect { case declaration: ResolvableType => declaration }
    )
    val members = resolvedMembers.map {
      case alias: TypeAlias =>
        alias.copy(typeSpec = computeTypeSpecForAlias(alias.typeRef, resolvedTypes, Set.empty))
      case member => member
    }
    val module = ResolvablesIndexer.refresh(state.module.copy(members = members))
    state
      .copy(diagnostics = resolved.diagnostics, bindingIds = resolved.bindingIds)
      .withModule(module)

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

  private def resolveMember(
    member: Member,
    scope:  TypeScope
  ): Resolution[Member] =
    member match
      case bnd: Bnd => resolveBinding(bnd, scope).widen

      case protocol: Protocol =>
        val owned = scope.copy(owner = BindingOwner(protocol.id.getOrElse(protocol.name)))
        for
          parameter <- assignVariable(protocol.typeParameter, owned)
          parameterScope = owned.bind(List(parameter))
          constraints <- resolveConstraintClause(protocol.constraints, protocol, parameterScope)
          signatures <- protocol.signatures.traverse(
            resolveSignature(_, parameterScope)
          )
        yield protocol.copy(
          typeParameter = parameter,
          signatures    = signatures,
          constraints   = constraints
        )

      case instance: ProtocolInstance =>
        resolveType(instance.concreteType, instance, scope)
          .map(concrete => instance.copy(concreteType = concrete))

      case alias: TypeAlias =>
        resolveType(alias.typeRef, member, scope)
          .map(updatedTypeRef => alias.copy(typeRef = updatedTypeRef))

      case typeDef: TypeDef =>
        // Resolve type references within the TypeDef's typeSpec (e.g., for NativeStruct fields)
        typeDef.typeSpec
          .traverse(resolveType(_, member, scope))
          .map(updatedTypeSpec => typeDef.copy(typeSpec = updatedTypeSpec))

      case typeStruct: TypeStruct =>
        typeStruct.fields
          .traverse(field =>
            resolveType(field.typeSpec, member, scope)
              .map(resolvedType => field.copy(typeSpec = resolvedType))
          )
          .map(updatedFields => typeStruct.copy(fields = updatedFields))

      case _ =>
        member.pure[Resolution]

  /** Resolves each implementation in the type scope declared by its required signature. */
  private def resolveInstanceBodies(
    instance: ProtocolInstance,
    scope:    TypeScope
  ): Resolution[ProtocolInstance] =
    val protocol = ProtocolRegistry.resolveDeclaration(instance.protocol, scope.protocols)
    instance.members
      .traverse { member =>
        val signature = protocol.flatMap(ProtocolRegistry.matchingSignature(member, _))
        val locals    = signature.toList.flatMap(_.typeParams)
        resolveBinding(member.binding, scope, locals.some)
          .map(binding => member.copy(binding = binding))
      }
      .map(members => instance.copy(members = members))

  private def resolveBinding(
    binding:       Bnd,
    scope:         TypeScope,
    memberBinders: Option[List[TypeVariable]] = None
  ): Resolution[Bnd] =
    val owned = scope
      .copy(owner = BindingOwner(binding.id.getOrElse(binding.name)))
      .bind(memberBinders.toList.flatten)
    for
      value <- resolveExpr(binding.value, binding, owned, memberBinders)
      typeAsc <- binding.typeAsc.traverse(resolveType(_, binding, owned))
      typeSpec <- binding.typeSpec.traverse(resolveType(_, binding, owned))
    yield binding.copy(value = value, typeAsc = typeAsc, typeSpec = typeSpec)

  private def resolveSignature(
    signature: ProtocolSignature,
    scope:     TypeScope
  ): Resolution[ProtocolSignature] =
    val owned = scope.copy(owner = BindingOwner(signature.id.getOrElse(signature.name)))
    val types = signature.params.flatMap(_.typeAsc) :+ signature.returnType
    for
      locals <- declareVariables(types, signature.typeParams, owned)
      nested = owned.bind(locals)
      params <- signature.params.traverse(resolveParam(_, signature, nested))
      returnType <- resolveType(signature.returnType, signature, nested)
      constraints <- resolveConstraintClause(signature.constraints, signature, nested)
    yield signature.copy(
      params      = params,
      returnType  = returnType,
      typeParams  = locals,
      constraints = constraints
    )

  private def resolveParam(
    param:  FnParam,
    member: Member,
    scope:  TypeScope
  ): Resolution[FnParam] =
    param.typeAsc
      .traverse(resolveType(_, member, scope))
      .map(updatedTypeAsc => param.copy(typeAsc = updatedTypeAsc))

  private def resolveTypeReference(
    ref:    TypeRef,
    member: Member,
    scope:  TypeScope
  ): Resolution[Type] =
    def resolve: Resolution[Type] = scope.types.getOrElse(ref.name, Nil) match
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

  /** Resolve named types and lexical variables; a scheme introduces its own explicit binders. */
  private def resolveType(
    typeSpec: Type,
    member:   Member,
    scope:    TypeScope
  ): Resolution[Type] =
    typeSpec match
      case typeRef: TypeRef => resolveTypeReference(typeRef, member, scope)

      case variable: TypeVariable => resolveVariable(variable, member, scope).widen

      case scheme: TypeScheme =>
        for
          variables <- scheme.vars.traverse(assignVariable(_, scope))
          body <- resolveType(scheme.bodyType, member, scope.bind(variables))
        yield scheme.copy(vars = variables, bodyType = body)

      case refinement: TypeRefinement =>
        resolveExpr(refinement.expr, member, scope).map(expr => refinement.copy(expr = expr))

      case other =>
        TypeTraversal.traverseChildren(other)(resolveType(_, member, scope))

  /** Resolves types in the expression and its terms. An instance body inherits its signature
    * binders. Nested functions collect their own binders normally, including inside that
    * implementation.
    */
  private def resolveExpr(
    expr:          Expr,
    member:        Member,
    scope:         TypeScope,
    memberBinders: Option[List[TypeVariable]] = None
  ): Resolution[Expr] =
    for
      updatedTerms <- expr.terms.traverse { term =>
        (term, memberBinders) match
          case (lambda: Lambda, Some(variables)) =>
            resolveLambdaBody(lambda, member, scope, variables).widen[Term]
          case _ => resolveTerm(term, member, scope)
      }
      updatedTypeAsc <- expr.typeAsc.traverse(resolveType(_, member, scope))
      updatedTypeSpec <- expr.typeSpec.traverse(resolveType(_, member, scope))
    yield expr.copy(
      terms    = updatedTerms,
      typeAsc  = updatedTypeAsc,
      typeSpec = updatedTypeSpec
    )

  private def resolveTerm(
    term:   Term,
    member: Member,
    scope:  TypeScope
  ): Resolution[Term] =
    term match
      case ref: Ref => resolveRef(ref, member, scope).widen

      case group: TermGroup =>
        for
          updatedInner <- resolveExpr(group.inner, member, scope)
          updatedTypeAsc <- group.typeAsc.traverse(resolveType(_, member, scope))
        yield group.copy(inner = updatedInner, typeAsc = updatedTypeAsc)

      case e: Expr =>
        resolveExpr(e, member, scope).widen

      case t: Tuple =>
        for
          updatedElements <- t.elements.traverse(resolveExpr(_, member, scope))
          updatedTypeAsc <- t.typeAsc.traverse(resolveType(_, member, scope))
        yield t.copy(elements = updatedElements, typeAsc = updatedTypeAsc)

      case cond: Cond =>
        for
          updatedCond <- resolveExpr(cond.cond, member, scope)
          updatedIfTrue <- resolveExpr(cond.ifTrue, member, scope)
          updatedIfFalse <- resolveExpr(cond.ifFalse, member, scope)
          updatedTypeAsc <- cond.typeAsc.traverse(resolveType(_, member, scope))
          updatedTypeSpec <- cond.typeSpec.traverse(resolveType(_, member, scope))
        yield cond.copy(
          cond     = updatedCond,
          ifTrue   = updatedIfTrue,
          ifFalse  = updatedIfFalse,
          typeAsc  = updatedTypeAsc,
          typeSpec = updatedTypeSpec
        )

      case app: App => resolveApp(app, member, scope).widen

      case app: FnTypeApp =>
        for
          fn <- resolveTerm(app.fn, member, scope)
          args <- app.args.traverse(resolveType(_, member, scope))
          typeAsc <- app.typeAsc.traverse(resolveType(_, member, scope))
          typeSpec <- app.typeSpec.traverse(resolveType(_, member, scope))
        yield app.copy(fn = fn, args = args, typeAsc = typeAsc, typeSpec = typeSpec)

      case lambda: Lambda => resolveLambda(lambda, member, scope).widen

      case invalid: InvalidExpression =>
        for
          original <- resolveExpr(invalid.originalExpr, member, scope)
          typeAsc <- invalid.typeAsc.traverse(resolveType(_, member, scope))
          typeSpec <- invalid.typeSpec.traverse(resolveType(_, member, scope))
        yield invalid.copy(originalExpr = original, typeAsc = typeAsc, typeSpec = typeSpec)

      case placeholder: Placeholder =>
        placeholder.typeAsc
          .traverse(resolveType(_, member, scope))
          .map(updatedTypeAsc => placeholder.copy(typeAsc = updatedTypeAsc))

      case hole: Hole =>
        hole.typeAsc
          .traverse(resolveType(_, member, scope))
          .map(updatedTypeAsc => hole.copy(typeAsc = updatedTypeAsc))

      case native: NativeImpl =>
        native.typeAsc
          .traverse(resolveType(_, member, scope))
          .map(updatedTypeAsc => native.copy(typeAsc = updatedTypeAsc))

      case lit: LiteralValue =>
        for
          spec <- lit.typeSpec.traverse(resolveType(_, member, scope))
          ascription <- lit.typeAsc.traverse(resolveType(_, member, scope))
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
    fn:     Callee,
    member: Member,
    scope:  TypeScope
  ): Resolution[Callee] =
    fn match
      case ref:    Ref => resolveRef(ref, member, scope).widen
      case app:    App => resolveApp(app, member, scope).widen
      case lambda: Lambda => resolveLambda(lambda, member, scope).widen

  private def resolveRef(ref: Ref, member: Member, scope: TypeScope): Resolution[Ref] =
    for
      qualifier <- ref.qualifier.traverse(resolveTerm(_, member, scope))
      typeAsc <- ref.typeAsc.traverse(resolveType(_, member, scope))
      explicitTypeArgs <- ref.explicitTypeArgs.traverse(_.traverse(resolveType(_, member, scope)))
      typeArgs <- ref.typeArgs.traverse(resolveType(_, member, scope))
      protocolType <- ref.protocolType.traverse(resolveType(_, member, scope))
    yield ref.copy(
      qualifier        = qualifier,
      typeAsc          = typeAsc,
      explicitTypeArgs = explicitTypeArgs,
      typeArgs         = typeArgs,
      protocolType     = protocolType
    )

  /** Initializers resolve before binding scopes so cached local-function annotations reuse the
    * function's binders without making them visible in the continuation.
    */
  private def resolveApp(app: App, member: Member, scope: TypeScope): Resolution[App] =
    for
      arg <- resolveExpr(app.arg, member, scope)
      fn <- resolveAppFn(app.fn, member, scope)
      typeAsc <- app.typeAsc.traverse(resolveType(_, member, scope))
      calleeAscriptions <- app.calleeAscriptions.traverse(
        resolveType(_, member, scope)
      )
    yield app.copy(fn = fn, arg = arg, typeAsc = typeAsc, calleeAscriptions = calleeAscriptions)

  /** A function owns signature variables absent from its enclosing scope. Local binding and
    * statement wrappers only carry annotations; they never declare type parameters.
    */
  private def resolveLambda(
    lambda: Lambda,
    member: Member,
    scope:  TypeScope
  ): Resolution[Lambda] =
    val signatureTypes = lambda.params.flatMap(_.typeAsc) ++ lambda.typeAsc
    val declarations   = if lambda.meta.exists(_.isBindingScope) then Nil else signatureTypes
    declareVariables(declarations, lambda.typeParams, scope).flatMap { variables =>
      resolveLambdaBody(lambda, member, scope.bind(variables), variables)
    }

  /** Bind new names in signature order and reuse their identities on repeated resolution. */
  private def declareVariables(
    types:    List[Type],
    previous: List[TypeVariable],
    scope:    TypeScope
  ): Resolution[List[TypeVariable]] =
    val existing = previous.map(variable => variable.name -> variable).toMap
    types
      .flatMap(signatureVariables)
      .filterNot(variable => scope.variables.contains(variable.name))
      .distinctBy(_.name)
      .traverse(variable => assignVariable(existing.getOrElse(variable.name, variable), scope))

  private def resolveLambdaBody(
    lambda:    Lambda,
    member:    Member,
    nested:    TypeScope,
    variables: List[TypeVariable]
  ): Resolution[Lambda] =
    for
      params <- lambda.params.traverse(resolveParam(_, member, nested))
      body <- resolveExpr(lambda.body, member, nested)
      typeAsc <- lambda.typeAsc.traverse(resolveType(_, member, nested))
      constraints <- resolveConstraintClause(lambda.constraints, member, nested)
    yield lambda.copy(
      params      = params,
      body        = body,
      typeAsc     = typeAsc,
      typeParams  = variables,
      constraints = constraints
    )

  /** Quantified variables inside a scheme belong to that scheme, not the enclosing signature. */
  private def signatureVariables(tpe: Type): List[TypeVariable] = tpe match
    case variable: TypeVariable => List(variable)
    case scheme:   TypeScheme =>
      val bound = scheme.vars.map(_.name).toSet
      signatureVariables(scheme.bodyType).filterNot(variable => bound.contains(variable.name))
    case other => TypeTraversal.children(other).flatMap(signatureVariables)

  /** Reserve an existing binder or allocate a fresh identity from the compilation supply. */
  private def assignVariable(variable: TypeVariable, scope: TypeScope): Resolution[TypeVariable] =
    State { current =>
      variable.resolvedId match
        case Some(id) =>
          (current.copy(bindingIds = current.bindingIds.reserve(List(id))), variable)
        case None =>
          val (supply, id) = current.bindingIds.fresh(scope.owner, "type-variable", variable.name)
          (current.copy(bindingIds = supply), variable.copy(resolvedId = id.some))
    }

  /** Preserves existing variable identities and resolves unassigned occurrences in lexical scope. A
    * missing binder leaves the occurrence unresolved and adds a diagnostic. Parser signature caches
    * reuse the same occurrence's resolution without extending lexical scope.
    */
  private def resolveVariable(
    variable: TypeVariable,
    member:   Member,
    scope:    TypeScope
  ): Resolution[TypeVariable] =
    def resolve: Resolution[TypeVariable] = scope.variables.get(variable.name) match
      case Some(parameter) => variable.copy(resolvedId = parameter.resolvedId).pure[Resolution]
      case None =>
        report(SemanticError.UndefinedTypeVariable(variable, member, phaseName))
          .as(variable.copy(resolvedId = none))

    if variable.resolvedId.nonEmpty then variable.pure[Resolution]
    else if !variable.source.isFromSource then resolve
    else
      val occurrence = TypeOccurrence(variable)
      State
        .inspect[ResolvedTypes, Option[TypeVariable]](_.writtenVariables.get(occurrence))
        .flatMap {
          case Some(resolved) => resolved.pure[Resolution]
          case None =>
            resolve.flatTap { resolved =>
              State.modify(current =>
                current.copy(
                  writtenVariables = current.writtenVariables.updated(occurrence, resolved)
                )
              )
            }
        }

  /** Resolves a where clause with at most one entry per type-variable binder. Separate clauses have
    * independent entry sets, including when a nested function uses an enclosing binder.
    */
  private def resolveConstraintClause(
    entries: List[TypeVariableConstraints],
    member:  Member,
    scope:   TypeScope
  ): Resolution[List[TypeVariableConstraints]] =
    entries
      .foldLeftM((Map.empty[String, TypeVariable], List.empty[TypeVariableConstraints])) {
        case ((seen, resolved), entry) =>
          resolveConstraints(entry, member, scope).flatMap { current =>
            val variable = current.variable
            val duplicate = variable.resolvedId.flatMap(seen.get) match
              case Some(first) =>
                report(SemanticError.DuplicateConstraintVariable(variable, first, phaseName))
              case None => Vector.empty[DiagnosticId].pure[Resolution]

            duplicate.map { causes =>
              val next = variable.resolvedId.fold(seen) { id =>
                seen.updated(id, seen.getOrElse(id, variable))
              }
              val checked = current.copy(causes = (current.causes ++ causes).distinct)
              (next, checked :: resolved)
            }
          }
      }
      .map(_._2.reverse)

  /** Where entries refer to existing binders and protocols; each failed lookup is reported. */
  private def resolveConstraints(
    entry:  TypeVariableConstraints,
    member: Member,
    scope:  TypeScope
  ): Resolution[TypeVariableConstraints] =
    for
      start <- State.inspect[ResolvedTypes, Int](_.diagnostics.records.size)
      variable <- resolveVariable(entry.variable, member, scope)
      constraints <- entry.constraints.traverse { ref =>
        ProtocolRegistry.resolveDeclaration(ref, scope.protocols) match
          case Some(protocol) => ref.copy(resolvedId = protocol.id).pure[Resolution]
          case None =>
            report(SemanticError.ProtocolErrorFound(ProtocolError.UnknownProtocol(ref), phaseName))
              .as(ref.copy(resolvedId = none))
      }
      diagnostics <- State.inspect[ResolvedTypes, DiagnosticStore](_.diagnostics)
      causes = diagnostics
        .roots(entry.causes ++ diagnostics.records.drop(start).map(_.id))
        .getOrElse(entry.causes)
    yield entry.copy(variable = variable, constraints = constraints, causes = causes)

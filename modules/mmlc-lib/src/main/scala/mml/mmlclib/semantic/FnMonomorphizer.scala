package mml.mmlclib.semantic

import cats.Id
import cats.data.State
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Instantiates checked generic functions at concrete uses before callable and ownership lowering.
  * Templates supply types and bodies; only their concrete instances remain executable.
  */
object FnMonomorphizer:

  /** Lexical rewrite environment. Types map source type-variable IDs to concrete types; ids map
    * source definitions to generated definitions. Locals retains visible function templates. Caller
    * owns dependency edges discovered in this body. Fresh requests new parameter identities
    * throughout an instantiated body, including its nested lambdas.
    */
  private case class Scope(
    owner:  BindingOwner,
    types:  Map[String, Type]     = Map.empty,
    ids:    Map[String, String]   = Map.empty,
    locals: Map[String, Template] = Map.empty,
    caller: Option[FnInstanceKey] = None,
    fresh:  Boolean               = false
  ):

    def tpe(value: Type): Type = value match
      case scheme: TypeScheme =>
        val remaining = scheme.vars.filterNot(_.resolvedId.exists(types.contains))
        val body      = tpe(scheme.bodyType)
        if remaining.isEmpty then body else scheme.copy(vars = remaining, bodyType = body)
      case variable: TypeVariable => variable.resolvedId.flatMap(types.get).getOrElse(variable)
      case other => TypeTraversal.traverseChildren[Id](other)(tpe)

    def id(value: String): String = ids.getOrElse(value, value)

    def param(value: FnParam): FnParam = value.copy(
      id       = value.id.map(id),
      typeSpec = value.typeSpec.map(tpe),
      typeAsc  = value.typeAsc.map(tpe)
    )

  /** A checked generic function and its declaration environment. Global retains the source module
    * binding used to emit top-level instances; local templates instead emit lexical bindings.
    */
  private case class Template(
    id:     String,
    name:   Name,
    lambda: Lambda,
    scope:  Scope,
    global: Option[Bnd]
  )

  /** One specialization and its generated binding identity, carried by parameter. Use retains the
    * concrete selection for diagnostics. Body is absent while expansion is pending; reserving this
    * record first lets recursive uses reuse its identity.
    */
  private case class Instance(
    key:       FnInstanceKey,
    template:  Template,
    parameter: FnParam,
    use:       Ref,
    body:      Option[Lambda] = None
  )

  /** Immutable allocation, specialization, dependency, and diagnostic state for one module.
    * localTemplates retains checked local declarations for the module's existing symbol index.
    */
  private case class Work(
    supply:         BindingIdSupply,
    localTemplates: List[Bnd]                   = Nil,
    instances:      Vector[Instance]            = Vector.empty,
    dependencies:   FnInstantiationDependencies = FnInstantiationDependencies.empty,
    errors:         List[SemanticError]         = Nil
  )

  private type Expand[A] = State[Work, A]

  /** Adds concrete instances and redirects their uses, retaining checked source declarations.
    * Invalid instantiation leaves the checked module available with its diagnostics.
    */
  def rewriteModule(state: CompilerState): CompilerState =
    if state.hasErrors then state
    else
      val globals = state.module.members
        .collect { case binding: Bnd => binding }
        .flatMap { binding =>
          for
            id <- binding.id
            lambda <- binding.value.terms.collectFirst {
              case lambda: Lambda if generic(lambda) => lambda
            }
          yield id -> Template(
            id,
            binding.nameNode,
            lambda,
            Scope(BindingOwner.binding(state.module.name, binding.name)),
            binding.some
          )
        }
        .toMap
      val expansion = new Expansion(state, globals)
      val program = state.module.members.traverse {
        case binding: Bnd if binding.id.exists(globals.contains) => (binding: Member).pure[Expand]
        case binding: Bnd =>
          expansion
            .expr(binding.value, Scope(BindingOwner.binding(state.module.name, binding.name)))
            .map(value => binding.copy(value = value): Member)
        case other => other.pure[Expand]
      }
      val (work, members) = program.run(Work(state.bindingIds.include(state.module))).value
      if work.errors.nonEmpty then state.addErrors(work.errors).withCanEmitCode(false)
      else
        val generated = work.instances.toList.flatMap { instance =>
          for
            binding <- instance.template.global
            body <- instance.body
          yield binding.copy(
            nameNode = instance.parameter.nameNode,
            id       = instance.parameter.id,
            source   = binding.source.derived,
            value    = Expr(binding.value.source.derived, List(body), typeSpec = body.typeSpec),
            typeSpec = body.typeSpec,
            typeAsc  = None,
            meta     = binding.meta.map(_.copy(mangledName = instance.parameter.name))
          )
        }
        val concrete = state
          .copy(bindingIds = work.supply)
          .withModule(
            state.module.copy(
              members     = members ++ generated,
              resolvables = state.module.resolvables.updatedAll(work.localTemplates)
            )
          )
        ResolvablesIndexer.rewriteModule(concrete)

  private def generic(lambda: Lambda): Boolean = lambda.typeParams.nonEmpty

  /** All expansion operations share immutable compiler inputs and thread allocations and instances
    * through Work. Scope carries the lexical environment of the body being rewritten.
    */
  private class Expansion(input: CompilerState, globals: Map[String, Template]):

    private val index = input.module.resolvables

    private def allocate(parameter: FnParam, owner: BindingOwner): Expand[FnParam] = State { work =>
      val (supply, fresh) = BindingIds.fresh(parameter, owner, "generic").run(work.supply).value
      (work.copy(supply = supply), fresh)
    }

    private def report(ref: Ref, reason: ExpressionProblem): Expand[Ref] =
      State
        .modify[Work](work =>
          work.copy(errors =
            work.errors :+
              SemanticError
                .InvalidExpression(Expr(ref.source, List(ref)), reason, "fn-monomorphizer")
          )
        )
        .as(ref)

    private def template(ref: Ref, scope: Scope): Option[Template] =
      ref.resolvedId.flatMap(id => scope.locals.get(id).orElse(globals.get(id)))

    private def plainRef(ref: Ref, scope: Scope): Ref = ref.copy(
      sourceId         = ref.sourceId.orElse(Option.when(scope.fresh)(ref.resolvedId).flatten),
      resolvedId       = ref.resolvedId.map(scope.id),
      candidateIds     = ref.candidateIds.map(scope.id),
      typeSpec         = ref.typeSpec.map(scope.tpe),
      typeAsc          = ref.typeAsc.map(scope.tpe),
      typeArgs         = ref.typeArgs.map(scope.tpe),
      explicitTypeArgs = ref.explicitTypeArgs.map(_.map(scope.tpe)),
      protocolType     = ref.protocolType.map(scope.tpe)
    )

    private def reference(ref: Ref, scope: Scope): Expand[Ref] =
      ref.qualifier.traverse(term(_, scope)).flatMap { qualifier =>
        val concrete = plainRef(ref, scope).copy(qualifier = qualifier)
        template(ref, scope) match
          case Some(definition) => instantiate(concrete, definition, scope)
          case None if concrete.protocolType.nonEmpty =>
            val selected = for
              signature <- concrete.resolvedId.toRight(())
              tpe <- concrete.protocolType.toRight(())
              candidates <- input.protocols.signatureCandidates(signature, index).leftMap(_ => ())
              protocolId <- candidates.protocol.id.toRight(())
              instance <- input.protocols.usableInstance(protocolId, tpe, index).leftMap(_ => ())
              binding <- input.protocols.memberBinding(instance, signature, index).leftMap(_ => ())
            yield concrete.copy(
              name         = binding.name,
              resolvedId   = binding.id,
              candidateIds = binding.id.toList,
              protocolType = None
            )
            selected.fold(
              _ => report(concrete, ExpressionProblem.UnresolvedGenericInstantiation),
              member =>
                template(member, scope).fold(member.pure[Expand])(instantiate(member, _, scope))
            )
          case None => concrete.pure[Expand]
      }

    private def instantiate(ref: Ref, definition: Template, caller: Scope): Expand[Ref] =
      val binders = definition.lambda.typeParams.flatMap(_.resolvedId)
      val key = for
        args <- ref.typeArgs.traverse(ProtocolRegistry.typeKey(_, index)).toOption
        enclosing <- definition.scope.types.toList.traverse { case (id, tpe) =>
          ProtocolRegistry.typeKey(tpe, index).map(id -> _)
        }.toOption
        if args.size == binders.size && binders.size == definition.lambda.typeParams.size
      yield FnInstanceKey(definition.id, args, enclosing.toMap)

      key match
        case None => report(ref, ExpressionProblem.UnresolvedGenericInstantiation)
        case Some(key) =>
          for
            current <- State.get[Work]
            dependency = caller.caller.fold(current.dependencies.asRight[ChangingTypeRecursion]) {
              parent => current.dependencies.add(FnInstantiationDependency(parent, key, ref))
            }
            result <- dependency match
              case Left(recursion) =>
                val origin = current.instances.find(_.key == recursion.path.head.caller).map(_.use)
                report(
                  ref,
                  ExpressionProblem.ChangingGenericRecursion(
                    origin.toList ++ recursion.path.toList.map(_.site)
                  )
                )
              case Right(dependencies) =>
                State.modify[Work](_.copy(dependencies = dependencies)) *> (
                  current.instances.find(_.key == key) match
                    case Some(instance) => instanceRef(ref, instance).pure[Expand]
                    case None => createInstance(ref, definition, key, binders)
                )
          yield result

    private def instanceRef(ref: Ref, instance: Instance): Ref = ref.copy(
      name         = instance.parameter.name,
      sourceId     = ref.sourceId.orElse(ref.resolvedId),
      resolvedId   = instance.parameter.id,
      candidateIds = instance.parameter.id.toList,
      typeArgs     = Nil
    )

    private def createInstance(
      ref:        Ref,
      definition: Template,
      key:        FnInstanceKey,
      binders:    List[String]
    ): Expand[Ref] =
      for
        current <- State.get[Work]
        name = s"${definition.name.value}__instance_${current.supply.next}"
        parameter <- allocate(
          FnParam(
            definition.name.source.derived,
            Name(name, definition.name.source.derived),
            typeSpec = ref.typeSpec
          ),
          definition.scope.owner
        )
        reserved = Instance(key, definition, parameter, ref)
        _ <- State.modify[Work](work => work.copy(instances = work.instances :+ reserved))
        scope = definition.scope.copy(
          owner = BindingOwner(parameter.id.getOrElse(name)),
          types = definition.scope.types ++ binders.zip(ref.typeArgs),
          locals = definition.scope.locals ++ Option.when(definition.global.isEmpty)(
            definition.id -> definition
          ),
          caller = key.some,
          fresh  = true
        )
        expanded <- lambda(definition.lambda, scope)
        body = expanded.copy(meta =
          expanded.meta.getOrElse(LambdaMeta()).copy(templateId = definition.id.some).some
        )
        _ <- State.modify[Work](work =>
          work.copy(instances = work.instances.map { instance =>
            if instance.key == key then instance.copy(body = body.some) else instance
          })
        )
      yield instanceRef(ref, reserved)

    def expr(value: Expr, scope: Scope): Expand[Expr] =
      value.terms
        .traverse(term(_, scope))
        .map(terms =>
          value.copy(
            terms    = terms,
            typeSpec = value.typeSpec.map(scope.tpe),
            typeAsc  = value.typeAsc.map(scope.tpe)
          )
        )

    /** Specializes a lambda's types, parameter identities, and body in its lexical environment.
      * Assigned parameters share an allocation with a recursive local initializer. Abstract
      * template captures are dropped here; after concrete local instances are bound,
      * CaptureAnalyzer rebuilds their captures from the rewritten references.
      */
    private def lambda(
      value:    Lambda,
      scope:    Scope,
      assigned: Option[List[FnParam]] = None
    ): Expand[Lambda] =
      for
        params <- assigned.fold(value.params.traverse { param =>
          val concrete = scope.param(param)
          if scope.fresh then allocate(concrete, scope.owner) else concrete.pure[Expand]
        })(_.pure[Expand])
        nested = scope.copy(ids =
          scope.ids ++ value.params.zip(params).flatMap { case (old, fresh) =>
            old.id.zip(fresh.id)
          }
        )
        body <- expr(value.body, nested)
      yield value.copy(
        params = params,
        body   = body,
        captures = value.captures.filterNot(c => template(c.ref, scope).nonEmpty).map {
          case Capture.CapturedRef(ref) => Capture.CapturedRef(plainRef(ref, nested))
          case Capture.BorrowedRef(ref) => Capture.BorrowedRef(plainRef(ref, nested))
          case Capture.CapturedLiteral(ref, clone) =>
            Capture.CapturedLiteral(plainRef(ref, nested), clone)
          case Capture.CapturedConditional(ref, witness, clone) =>
            Capture.CapturedConditional(plainRef(ref, nested), plainRef(witness, nested), clone)
          case Capture.OwnedClosure(ref, target) =>
            Capture.OwnedClosure(plainRef(ref, nested), nested.id(target))
        },
        typeSpec   = value.typeSpec.map(scope.tpe),
        typeAsc    = value.typeAsc.map(scope.tpe),
        typeParams = value.typeParams.filterNot(_.resolvedId.exists(scope.types.contains)),
        constraints =
          value.constraints.filterNot(_.variable.resolvedId.exists(scope.types.contains)),
        meta = value.meta.map(meta =>
          meta.copy(
            transferredCaptures = meta.transferredCaptures.map(nested.id),
            borrowedCaptures    = meta.borrowedCaptures.map(nested.id),
            retainedCaptures    = meta.retainedCaptures.map(nested.id),
            environmentParam    = meta.environmentParam.map(nested.param)
          )
        )
      )

    /** Retains a checked local declaration in the symbol index and binds its concrete instances
      * before the continuation executes. Templates inside an instance already belong to the
      * retained source generic body; they need no additional indexed copy.
      */
    private def local(app: App, continuation: Lambda, value: Lambda, scope: Scope): Expand[Term] =
      val parameter = continuation.params.head
      parameter.id match
        case None =>
          report(
            Ref(parameter.source, parameter.name),
            ExpressionProblem.UnresolvedGenericInstantiation
          ).widen
        case Some(id) =>
          val definition = Template(id, parameter.nameNode, value, scope, None)
          val nested     = scope.copy(locals = scope.locals.updated(id, definition))
          for
            _ <- State.modify[Work] { work =>
              if scope.fresh then work
              else
                val binding = Bnd(
                  source     = parameter.source,
                  nameNode   = parameter.nameNode,
                  value      = Expr(value.source, List(value), typeSpec = value.typeSpec),
                  typeSpec   = parameter.typeSpec,
                  typeAsc    = parameter.typeAsc,
                  docComment = parameter.docComment,
                  id         = parameter.id
                )
                work.copy(localTemplates = binding :: work.localTemplates)
            }
            body <- expr(continuation.body, nested)
            work <- State.get[Work]
          yield
            val instances = work.instances.filter(instance =>
              instance.template.id == id && instance.template.scope.owner.path == scope.owner.path
            )
            val bound = instances.foldRight(body) { (instance, rest) =>
              instance.body.fold(rest) { lambda =>
                val initializer = Expr(lambda.source, List(lambda), typeSpec = lambda.typeSpec)
                val binding = LocalBindings.bind(
                  instance.parameter.copy(typeSpec = lambda.typeSpec),
                  initializer,
                  rest,
                  app.source
                )
                Expr(app.source, List(binding), typeSpec = rest.typeSpec)
              }
            }
            bound

    private def callee(value: Callee, scope: Scope): Expand[Callee] = value match
      case ref: Ref => reference(ref, scope).widen
      case fn:  Lambda => lambda(fn, scope).widen
      case app: App => application(app, scope).widen

    private def application(value: App, scope: Scope): Expand[App] =
      (callee(value.fn, scope), expr(value.arg, scope)).mapN { (fn, arg) =>
        value.copy(
          fn                = fn,
          arg               = arg,
          typeSpec          = value.typeSpec.map(scope.tpe),
          typeAsc           = value.typeAsc.map(scope.tpe),
          calleeAscriptions = value.calleeAscriptions.map(scope.tpe)
        )
      }

    /** Rewrites a unary lambda application, sharing one parameter identity with its argument and
      * body. Freshening inside an instance keeps recursive local initializer references consistent.
      */
    private def bindingApplication(value: App, continuation: Lambda, scope: Scope): Expand[App] =
      val original = continuation.params.head
      val concrete = scope.param(original)
      for
        parameter <- if scope.fresh then allocate(concrete, scope.owner) else concrete.pure[Expand]
        nested = scope.copy(ids = scope.ids ++ original.id.zip(parameter.id))
        fn <- lambda(continuation, scope, List(parameter).some)
        arg <- expr(value.arg, nested)
      yield value.copy(
        fn                = fn,
        arg               = arg,
        typeSpec          = value.typeSpec.map(scope.tpe),
        typeAsc           = value.typeAsc.map(scope.tpe),
        calleeAscriptions = value.calleeAscriptions.map(scope.tpe)
      )

    private def term(value: Term, scope: Scope): Expand[Term] = value match

      case app: App =>
        (app.fn, app.arg.terms) match
          case (continuation: Lambda, List(fn: Lambda))
              if continuation.params.size == 1 && generic(fn) =>
            local(app, continuation, fn, scope)
          case (continuation: Lambda, _) if continuation.params.size == 1 =>
            bindingApplication(app, continuation, scope).widen
          case _ => application(app, scope).widen

      case ref: Ref => reference(ref, scope).widen

      case fn: Lambda => lambda(fn, scope).widen

      case e: Expr => expr(e, scope).widen

      case c: Cond =>
        (expr(c.cond, scope), expr(c.ifTrue, scope), expr(c.ifFalse, scope)).mapN {
          (condition, yes, no) =>
            c.copy(
              cond     = condition,
              ifTrue   = yes,
              ifFalse  = no,
              typeSpec = c.typeSpec.map(scope.tpe),
              typeAsc  = c.typeAsc.map(scope.tpe)
            )
        }

      case g: TermGroup =>
        expr(g.inner, scope).map(inner => g.copy(inner = inner, typeAsc = g.typeAsc.map(scope.tpe)))

      case t: Tuple =>
        t.elements
          .traverse(expr(_, scope))
          .map(elements =>
            t.copy(
              elements = elements,
              typeSpec = t.typeSpec.map(scope.tpe),
              typeAsc  = t.typeAsc.map(scope.tpe)
            )
          )

      case i: InvalidExpression =>
        expr(i.originalExpr, scope).map(original =>
          i.copy(
            originalExpr = original,
            typeSpec     = i.typeSpec.map(scope.tpe),
            typeAsc      = i.typeAsc.map(scope.tpe)
          )
        )

      case i: LiteralInt =>
        i.copy(typeSpec = i.typeSpec.map(scope.tpe), typeAsc = i.typeAsc.map(scope.tpe))
          .pure[Expand]

      case s: LiteralString =>
        s.copy(typeSpec = s.typeSpec.map(scope.tpe), typeAsc = s.typeAsc.map(scope.tpe))
          .pure[Expand]

      case b: LiteralBool =>
        b.copy(typeSpec = b.typeSpec.map(scope.tpe), typeAsc = b.typeAsc.map(scope.tpe))
          .pure[Expand]

      case f: LiteralFloat =>
        f.copy(typeSpec = f.typeSpec.map(scope.tpe), typeAsc = f.typeAsc.map(scope.tpe))
          .pure[Expand]

      case u: LiteralUnit =>
        u.copy(typeSpec = u.typeSpec.map(scope.tpe), typeAsc = u.typeAsc.map(scope.tpe))
          .pure[Expand]

      case n: NativeImpl =>
        n.copy(typeSpec = n.typeSpec.map(scope.tpe), typeAsc = n.typeAsc.map(scope.tpe))
          .pure[Expand]

      case d: DataConstructor => d.copy(typeSpec = d.typeSpec.map(scope.tpe)).pure[Expand]

      case p: Placeholder =>
        p.copy(typeSpec = p.typeSpec.map(scope.tpe), typeAsc = p.typeAsc.map(scope.tpe))
          .pure[Expand]

      case h: Hole =>
        h.copy(typeSpec = h.typeSpec.map(scope.tpe), typeAsc = h.typeAsc.map(scope.tpe))
          .pure[Expand]

      case _: FnTypeApp | _: BindOwnership | _: YieldOwnership | _: Destruction |
          _: ParseTermError =>
        value.pure[Expand]

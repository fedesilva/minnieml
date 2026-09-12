package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import java.util.IdentityHashMap

import BindingIds.Allocation

/** Registers closure environment layouts and destructor helpers after type checking.
  *
  * Capturing lambdas receive environment metadata. Move environments receive specific destructors,
  * and each module receives a universal dispatcher. ClosureDestructorBodyGenerator fills field
  * cleanup entries after ownership analysis establishes capture ownership.
  */
object ClosureMemoryFnGenerator:
  private val syntheticSource = SourceOrigin.Synth

  private def unitTypeRef(source: SourceOrigin): TypeRef =
    TypeRef(source, "Unit", Some("stdlib::typedef::Unit"), Nil)

  private def rawPtrTypeRef(source: SourceOrigin): TypeRef =
    TypeRef(source, "RawPtr", Some("stdlib::typedef::RawPtr"), Nil)

  private def typeId(moduleName: String, name: String): Option[String] =
    BindingIds.declaration(moduleName, name, "typedef").some

  /** Collect all capturing lambdas from a module, paired with a stable name for each.
    *
    * The name is derived from the enclosing binding name + a counter for disambiguation.
    */
  private type Collected = (List[(Lambda, String)], Int)

  private def collectCapturingLambdas(
    module: Module
  ): List[(Lambda, String)] =

    def walkExpr(expr: Expr, counter: Int): Collected =
      expr.terms.foldLeft((List.empty[(Lambda, String)], counter)):
        case ((acc, cnt), term) =>
          val (found, next) = walkTerm(term, cnt)
          (acc ::: found, next)

    def walkTerm(term: Term, counter: Int): Collected =
      term match
        case lambda: Lambda if lambda.captures.nonEmpty =>
          val name          = s"__closure_env_$counter"
          val (inner, next) = walkExpr(lambda.body, counter + 1)
          ((lambda, name) :: inner, next)
        case lambda: Lambda =>
          walkExpr(lambda.body, counter)
        case App(_, fn, arg, _, _) =>
          val (fnFound, cnt1)  = walkTerm(fn, counter)
          val (argFound, cnt2) = walkExpr(arg, cnt1)
          (fnFound ::: argFound, cnt2)
        case Cond(_, cond, ifTrue, ifFalse, _, _) =>
          val (c, cnt1) = walkExpr(cond, counter)
          val (t, cnt2) = walkExpr(ifTrue, cnt1)
          val (f, cnt3) = walkExpr(ifFalse, cnt2)
          (c ::: t ::: f, cnt3)
        case TermGroup(_, inner, _) =>
          walkExpr(inner, counter)
        case Tuple(_, elements, _, _) =>
          elements.toList.foldLeft((List.empty[(Lambda, String)], counter)) {
            case ((acc, cnt), element) =>
              val (found, next) = walkExpr(element, cnt)
              (acc ::: found, next)
          }
        case ref: Ref =>
          ref.qualifier.fold((Nil, counter))(walkTerm(_, counter))
        case _ => (Nil, counter)

    module.members
      .foldLeft((List.empty[(Lambda, String)], 0)):
        case ((acc, cnt), bnd: Bnd) =>
          val (found, next) = walkExpr(bnd.value, cnt)
          (acc ::: found, next)
        case (state, _) => state
      ._1

  /** Synthesize a TypeStruct for a closure environment.
    *
    * Move lambdas: field 0 = `__dtor: RawPtr` (destructor pointer), fields 1..N = captures. Borrow
    * lambdas: fields 0..N-1 = captures only (no destructor, env is stack-allocated).
    */
  private def mkEnvStruct(
    lambda:      Lambda,
    envName:     String,
    moduleName:  String,
    resolvables: ResolvablesIndex
  ): TypeStruct =
    val dtorFields =
      if lambda.isMove then
        Vector(
          Field(
            source   = syntheticSource,
            nameNode = Name.synth("__dtor"),
            typeSpec = rawPtrTypeRef(syntheticSource),
            id       = Some(s"$moduleName::typedef::$envName::__dtor")
          )
        )
      else Vector.empty

    val captureFields = lambda.captures.map { cap =>
      val ref = cap.ref
      val fieldType = ref.typeSpec
        .orElse(ref.typeAsc)
        .orElse(
          ref.resolvedId
            .flatMap(resolvables.lookup)
            .collect { case r: Typeable => r }
            .flatMap(r => r.typeSpec.orElse(r.typeAsc))
        )
        .getOrElse(
          TypeRef(syntheticSource, "Unknown")
        )
      Field(
        source   = syntheticSource,
        nameNode = Name.synth(ref.name),
        typeSpec = fieldType,
        id       = Some(s"$moduleName::typedef::$envName::${ref.name}")
      )
    }.toVector

    TypeStruct(
      source     = syntheticSource,
      docComment = None,
      visibility = Visibility.Private,
      nameNode   = Name.synth(envName),
      fields     = dtorFields ++ captureFields,
      id         = typeId(moduleName, envName)
    )

  /** Register the real helper signature before ownership chooses capture cleanup targets. */
  private def mkFreeFunction(fnName: String, moduleName: String): Allocation[Bnd] =
    val unitTR = unitTypeRef(syntheticSource)
    val ptrTR  = rawPtrTypeRef(syntheticSource)
    LocalBindings
      .param(
        BindingOwner.binding(moduleName, fnName),
        "p",
        typeAsc  = ptrTR.some,
        typeSpec = ptrTR.some,
        purpose  = "destructor"
      )
      .map { ptrParam =>
        val fnType = TypeFn(syntheticSource, cats.data.NonEmptyList.one(ptrTR), unitTR)
        val body =
          Expr(
            syntheticSource,
            List(LiteralUnit(syntheticSource, unitTR.some)),
            typeSpec = unitTR.some
          )
        val lambda = Lambda(
          syntheticSource,
          List(ptrParam),
          body,
          Nil,
          typeSpec = fnType.some,
          typeAsc  = unitTR.some
        )
        Bnd(
          source   = syntheticSource,
          nameNode = Name.synth(fnName),
          value    = Expr(syntheticSource, List(lambda), typeSpec = fnType.some),
          typeSpec = fnType.some,
          typeAsc  = unitTR.some,
          meta = BindingMeta(
            BindingOrigin.Destructor,
            CallableArity.Unary,
            Precedence.Function,
            None,
            fnName,
            fnName
          ).some,
          id = BindingIds.declaration(moduleName, fnName).some
        )
      }

  private def initializeBody(binding: Bnd, layoutId: Option[String]): Bnd =
    binding.value.terms match
      case List(lambda: Lambda) =>
        val operand  = pointerOperand(lambda)
        val unitType = unitTypeRef(syntheticSource).some
        val body = layoutId.fold[Destruction](
          DispatchClosureDestructor(syntheticSource, operand, unitType)
        )(id => DestroyClosureEnvironment(syntheticSource, operand, id, Nil, unitType))
        ClosureDestructorAst.withBody(binding, lambda, body)
      case _ => binding

  private def pointerOperand(lambda: Lambda): Expr =
    val refs =
      lambda.params.map(p => Ref(syntheticSource, p.name, resolvedId = p.id, typeSpec = p.typeSpec))
    Expr(syntheticSource, refs, typeSpec = rawPtrTypeRef(syntheticSource).some)

  /** Tag exact lambda instances and allocate invocation parameters in traversal order. */
  private def tagLambdas(
    members:    List[Member],
    lambdaMap:  IdentityHashMap[Lambda, String],
    moduleName: String
  ): Allocation[List[Member]] =
    def rewriteExpr(expr: Expr): Allocation[Expr] =
      expr.terms.traverse(rewriteTerm).map(terms => expr.copy(terms = terms))

    def rewriteCallable(term: Ref | App | Lambda): Allocation[Ref | App | Lambda] = term match
      case ref: Ref =>
        ref.qualifier.traverse(rewriteTerm).map(q => ref.copy(qualifier = q))
      case lambda: Lambda =>
        rewriteExpr(lambda.body).flatMap { body =>
          Option(lambdaMap.get(lambda)) match
            case Some(envName) =>
              val meta = lambda.meta.getOrElse(LambdaMeta()).copy(envStructName = envName.some)
              prepareClosureInvocation(
                lambda.copy(body = body, meta = meta.some),
                BindingOwner.binding(moduleName, envName)
              ).widen
            case None => lambda.copy(body = body).pure[Allocation].widen
        }
      case app: App =>
        for
          fn <- rewriteCallable(app.fn)
          arg <- rewriteExpr(app.arg)
        yield app.copy(fn = fn, arg = arg)

    def rewriteTerm(term: Term): Allocation[Term] = term match
      case callable: (Ref | App | Lambda) => rewriteCallable(callable).widen
      case cond:     Cond =>
        for
          predicate <- rewriteExpr(cond.cond)
          yes <- rewriteExpr(cond.ifTrue)
          no <- rewriteExpr(cond.ifFalse)
        yield cond.copy(cond = predicate, ifTrue = yes, ifFalse = no)
      case group: TermGroup => rewriteExpr(group.inner).map(inner => group.copy(inner = inner))
      case expr:  Expr => rewriteExpr(expr).widen
      case tuple: Tuple =>
        tuple.elements.traverse(rewriteExpr).map(elements => tuple.copy(elements = elements))
      case other => other.pure[Allocation]

    members.traverse {
      case binding: Bnd =>
        rewriteExpr(binding.value).map(value => binding.copy(value = value): Member)
      case other => other.pure[Allocation]
    }

  def rewriteModule(state: CompilerState): CompilerState =
    val module     = state.module
    val moduleName = module.name

    val capturingLambdas = collectCapturingLambdas(module)
    // Lambda nodes have no stable IDs; this boundary tags their exact AST instances.
    val lambdaMap = capturingLambdas.foldLeft(new IdentityHashMap[Lambda, String]()) {
      case (acc, (lambda, envName)) =>
        acc.put(lambda, envName)
        acc
    }
    val envStructs = capturingLambdas.map((lambda, name) =>
      mkEnvStruct(lambda, name, moduleName, module.resolvables)
    )
    val targets = capturingLambdas.zip(envStructs).collect {
      case ((lambda, _), struct) if lambda.isMove => (s"__free_${struct.name}", struct.id)
    } :+ ("__free_closure", none[String])
    val allocation = for
      _ <- envStructs.traverse_(BindingIds.reserveDeclaration)
      registrations <- targets.traverse { (name, layout) =>
        mkFreeFunction(name, moduleName)
          .flatTap(BindingIds.reserveDeclaration)
          .map(binding => (binding, layout))
      }
      tagged <- tagLambdas(module.members, lambdaMap, moduleName)
    yield tagged ++ envStructs ++ registrations.map(initializeBody)

    val (supply, members) = allocation.run(state.bindingIds.include(module)).value
    state
      .copy(bindingIds = supply)
      .withModule(ResolvablesIndexer.refresh(module.copy(members = members)))

package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import java.util.IdentityHashMap

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

  private def genId(moduleName: String, name: String): Option[String] =
    Some(s"$moduleName::bnd::$name")

  private def typeId(moduleName: String, name: String): Option[String] =
    Some(s"$moduleName::typedef::$name")

  private def paramId(
    moduleName: String,
    fnName:     String,
    paramName:  String
  ): Option[String] =
    Some(s"$moduleName::bnd::$fnName::$paramName")

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
  private def mkFreeFunction(fnName: String, moduleName: String): Bnd =
    val unitTR = unitTypeRef(syntheticSource)
    val ptrTR  = rawPtrTypeRef(syntheticSource)
    val ptrParam = FnParam(
      syntheticSource,
      Name.synth("p"),
      typeAsc  = ptrTR.some,
      typeSpec = ptrTR.some,
      id       = paramId(moduleName, fnName, "p")
    )
    val fnType = TypeFn(syntheticSource, cats.data.NonEmptyList.one(ptrTR), unitTR)
    val body =
      Expr(syntheticSource, List(LiteralUnit(syntheticSource, unitTR.some)), typeSpec = unitTR.some)
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
      id = genId(moduleName, fnName)
    )

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

  /** Rewrite lambdas in the AST to tag them with envStructName. */
  private def tagLambdas(
    members:    List[Member],
    lambdaMap:  IdentityHashMap[Lambda, String],
    moduleName: String
  ): List[Member] =
    if lambdaMap.isEmpty then members
    else
      def rewriteExpr(expr: Expr): Expr =
        val newTerms = expr.terms.map(rewriteTerm)
        if newTerms == expr.terms then expr
        else expr.copy(terms = newTerms)

      def rewriteCallable(term: Ref | App | Lambda): Ref | App | Lambda = term match
        case ref: Ref =>
          val newQualifier = ref.qualifier.map(rewriteTerm)
          if newQualifier == ref.qualifier then ref
          else ref.copy(qualifier = newQualifier)
        case lambda: Lambda =>
          val newBody = rewriteExpr(lambda.body)
          Option(lambdaMap.get(lambda)) match
            case Some(envName) =>
              val newMeta = lambda.meta
                .getOrElse(LambdaMeta())
                .copy(envStructName = Some(envName))
              prepareClosureInvocation(
                lambda.copy(body = newBody, meta = Some(newMeta)),
                SyntheticOwner.binding(moduleName, envName)
              )
            case None =>
              if newBody == lambda.body then lambda
              else lambda.copy(body = newBody)
        case App(src, fn, arg, ts, ta) =>
          val newFn  = rewriteCallable(fn)
          val newArg = rewriteExpr(arg)
          if (newFn == fn) && (newArg == arg) then term
          else App(src, newFn, newArg, ts, ta)

      def rewriteTerm(term: Term): Term = term match
        case callable: (Ref | App | Lambda) =>
          rewriteCallable(callable)
        case Cond(src, cond, ifTrue, ifFalse, ts, ta) =>
          val newCond    = rewriteExpr(cond)
          val newIfTrue  = rewriteExpr(ifTrue)
          val newIfFalse = rewriteExpr(ifFalse)
          if (newCond == cond) && (newIfTrue == ifTrue) && (newIfFalse == ifFalse) then term
          else Cond(src, newCond, newIfTrue, newIfFalse, ts, ta)
        case TermGroup(src, inner, ts) =>
          val newInner = rewriteExpr(inner)
          if newInner == inner then term
          else TermGroup(src, newInner, ts)
        case Tuple(src, elements, ts, ta) =>
          val newElements = elements.map(rewriteExpr)
          if newElements.toList == elements.toList then term
          else Tuple(src, newElements, ts, ta)
        case other =>
          other

      members.map:
        case bnd: Bnd =>
          val newValue = rewriteExpr(bnd.value)
          if newValue == bnd.value then bnd
          else bnd.copy(value = newValue)
        case other => other

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
    val registrations = capturingLambdas.zip(envStructs).collect {
      case ((lambda, _), struct) if lambda.isMove =>
        (mkFreeFunction(s"__free_${struct.name}", moduleName), struct.id)
    } :+ (mkFreeFunction("__free_closure", moduleName), none[String])
    val index = module.resolvables
      .updatedAllTypes(envStructs)
      .updatedAll(registrations.map(_._1))
    val freeFunctions = registrations.map(initializeBody)
    val members = tagLambdas(module.members, lambdaMap, moduleName) ++ envStructs ++ freeFunctions
    state.withModule(module.copy(members = members, resolvables = index.updatedAll(freeFunctions)))

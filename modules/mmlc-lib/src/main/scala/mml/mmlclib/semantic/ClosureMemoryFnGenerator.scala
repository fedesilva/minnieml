package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import java.util.IdentityHashMap

/** Generates closure environment types and memory functions for capturing lambdas.
  *
  * Runs after CaptureAnalyzer (which populates Lambda.captures) and before TypeChecker. For each
  * capturing lambda, this phase:
  *   1. Synthesizes a TypeStruct for the environment (`__closure_env_N`)
  *   2. Generates a free function (`__free_closure_env_N`) that frees heap fields + the env pointer
  *   3. Tags the Lambda with the env struct name via LambdaMeta.envStructName
  *
  * Follows the same patterns as ConstructorGenerator and MemoryFunctionGenerator.
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
        case _ => (Nil, counter)

    module.members
      .foldLeft((List.empty[(Lambda, String)], 0)):
        case ((acc, cnt), bnd: Bnd) =>
          val (found, next) = walkExpr(bnd.value, cnt)
          (acc ::: found, next)
        case (state, _) => state
      ._1

  /** Build a map from param/binding IDs to their types by walking the module AST. */
  private def buildIdTypeMap(module: Module): Map[String, Type] =
    val result = Map.newBuilder[String, Type]

    def walkExpr(expr: Expr): Unit = expr.terms.foreach(walkTerm)

    def walkTerm(term: Term): Unit = term match
      case lambda: Lambda =>
        lambda.params.foreach { p =>
          p.id.foreach { id =>
            p.typeAsc.orElse(p.typeSpec).foreach(t => result += (id -> t))
          }
        }
        walkExpr(lambda.body)
      case App(_, fn, arg, _, _) =>
        walkTerm(fn)
        walkExpr(arg)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        walkExpr(cond); walkExpr(ifTrue); walkExpr(ifFalse)
      case TermGroup(_, inner, _) => walkExpr(inner)
      case _ => ()

    module.members.foreach:
      case bnd: Bnd => walkExpr(bnd.value)
      case _ => ()

    result.result()

  /** Resolve the type of a captured Ref. */
  private def resolveCaptureType(ref: Ref, idTypeMap: Map[String, Type]): Option[Type] =
    ref.typeSpec
      .orElse(ref.typeAsc)
      .orElse(ref.resolvedId.flatMap(idTypeMap.get))

  /** Synthesize a TypeStruct for a closure environment.
    *
    * Field 0 is always `__dtor: RawPtr` — a pointer to the env's destructor function. This allows
    * the universal `__free_closure` to call the correct destructor without knowing the env layout.
    */
  private def mkEnvStruct(
    lambda:     Lambda,
    envName:    String,
    moduleName: String,
    idTypeMap:  Map[String, Type]
  ): TypeStruct =
    val dtorField = Field(
      source   = syntheticSource,
      nameNode = Name.synth("__dtor"),
      typeSpec = rawPtrTypeRef(syntheticSource),
      id       = Some(s"$moduleName::typedef::$envName::__dtor")
    )

    val captureFields = lambda.captures.map { ref =>
      val fieldType = resolveCaptureType(ref, idTypeMap).getOrElse(
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
      fields     = dtorField +: captureFields,
      id         = typeId(moduleName, envName)
    )

  /** Generate a __free_closure_env_N function.
    *
    * For 3.4 (value-type captures), the body is just: mml_free_raw(ptr)
    *
    * For 3.5 (heap-type captures), the body will be extended to free each heap field before freeing
    * the pointer.
    */
  private def mkFreeFunction(
    envStruct:  TypeStruct,
    moduleName: String
  ): Bnd =
    val envName = envStruct.name
    val fnName  = s"__free_$envName"
    val unitTR  = unitTypeRef(syntheticSource)

    // The free function takes a RawPtr parameter (the env pointer)
    val ptrParam = FnParam(
      syntheticSource,
      Name.synth("p"),
      typeAsc = Some(rawPtrTypeRef(syntheticSource)),
      id      = paramId(moduleName, fnName, "p")
    )

    // Body: mml_free_raw(p)
    val freeRawRef = Ref(
      syntheticSource,
      "mml_free_raw",
      resolvedId = Some("stdlib::bnd::mml_free_raw"),
      typeSpec   = Some(TypeFn(syntheticSource, List(rawPtrTypeRef(syntheticSource)), unitTR))
    )
    val ptrRef = Ref(
      syntheticSource,
      "p",
      typeSpec   = Some(rawPtrTypeRef(syntheticSource)),
      resolvedId = ptrParam.id
    )
    val argExpr =
      Expr(syntheticSource, List(ptrRef), typeSpec = Some(rawPtrTypeRef(syntheticSource)))
    val freeCall = App(syntheticSource, freeRawRef, argExpr, typeSpec = Some(unitTR))
    val body     = Expr(syntheticSource, List(freeCall), typeSpec = Some(unitTR))

    // Function type: RawPtr -> Unit
    val fnType = TypeFn(syntheticSource, List(rawPtrTypeRef(syntheticSource)), unitTR)

    val lambda = Lambda(
      syntheticSource,
      List(ptrParam),
      body,
      Nil,
      typeSpec = Some(fnType),
      typeAsc  = Some(unitTR)
    )

    val meta = BindingMeta(
      origin        = BindingOrigin.Destructor,
      arity         = CallableArity.Unary,
      precedence    = Precedence.Function,
      associativity = None,
      originalName  = fnName,
      mangledName   = fnName
    )

    Bnd(
      source     = syntheticSource,
      nameNode   = Name.synth(fnName),
      value      = Expr(syntheticSource, List(lambda)),
      typeSpec   = Some(fnType),
      typeAsc    = Some(unitTR),
      docComment = None,
      meta       = Some(meta),
      id         = genId(moduleName, fnName)
    )

  /** Universal closure free function — frees the env pointer of any closure.
    *
    * Used when a closure is returned from a function and the caller doesn't know which specific env
    * struct is inside. For 3.4 (value-type captures), this is sufficient since all env structs only
    * need their malloc'd pointer freed. For 3.5 (heap captures), this will need to be replaced with
    * per-layout free functions.
    *
    * The codegen extracts the env ptr from the fat pointer before calling this function.
    */
  private def mkUniversalClosureFree(moduleName: String): Bnd =
    val fnName = "__free_closure"
    val unitTR = unitTypeRef(syntheticSource)

    val ptrParam = FnParam(
      syntheticSource,
      Name.synth("p"),
      typeAsc = Some(rawPtrTypeRef(syntheticSource)),
      id      = paramId(moduleName, fnName, "p")
    )

    val freeRawRef = Ref(
      syntheticSource,
      "mml_free_raw",
      resolvedId = Some("stdlib::bnd::mml_free_raw"),
      typeSpec   = Some(TypeFn(syntheticSource, List(rawPtrTypeRef(syntheticSource)), unitTR))
    )
    val ptrRef = Ref(
      syntheticSource,
      "p",
      typeSpec   = Some(rawPtrTypeRef(syntheticSource)),
      resolvedId = ptrParam.id
    )
    val argExpr =
      Expr(syntheticSource, List(ptrRef), typeSpec = Some(rawPtrTypeRef(syntheticSource)))
    val freeCall = App(syntheticSource, freeRawRef, argExpr, typeSpec = Some(unitTR))
    val body     = Expr(syntheticSource, List(freeCall), typeSpec = Some(unitTR))

    val fnType = TypeFn(syntheticSource, List(rawPtrTypeRef(syntheticSource)), unitTR)

    val lambda = Lambda(
      syntheticSource,
      List(ptrParam),
      body,
      Nil,
      typeSpec = Some(fnType),
      typeAsc  = Some(unitTR)
    )

    val meta = BindingMeta(
      origin        = BindingOrigin.Destructor,
      arity         = CallableArity.Unary,
      precedence    = Precedence.Function,
      associativity = None,
      originalName  = fnName,
      mangledName   = fnName
    )

    Bnd(
      source     = syntheticSource,
      nameNode   = Name.synth(fnName),
      value      = Expr(syntheticSource, List(lambda)),
      typeSpec   = Some(fnType),
      typeAsc    = Some(unitTR),
      docComment = None,
      meta       = Some(meta),
      id         = genId(moduleName, fnName)
    )

  /** Rewrite lambdas in the AST to tag them with envStructName. */
  private def tagLambdas(
    members:   List[Member],
    lambdaMap: IdentityHashMap[Lambda, String]
  ): List[Member] =
    if lambdaMap.isEmpty then return members

    def rewriteExpr(expr: Expr): Expr =
      val newTerms = expr.terms.map(rewriteTerm)
      if newTerms eq expr.terms then expr
      else expr.copy(terms = newTerms)

    def rewriteTerm(term: Term): Term = term match
      case lambda: Lambda =>
        val newBody = rewriteExpr(lambda.body)
        Option(lambdaMap.get(lambda)) match
          case Some(envName) =>
            val newMeta = lambda.meta
              .getOrElse(LambdaMeta())
              .copy(envStructName = Some(envName))
            lambda.copy(body = newBody, meta = Some(newMeta))
          case None =>
            if newBody eq lambda.body then lambda
            else lambda.copy(body = newBody)
      case App(src, fn, arg, ts, ta) =>
        val newFn  = rewriteTerm(fn).asInstanceOf[Ref | App | Lambda]
        val newArg = rewriteExpr(arg)
        if (newFn eq fn) && (newArg eq arg) then term
        else App(src, newFn, newArg, ts, ta)
      case Cond(src, cond, ifTrue, ifFalse, ts, ta) =>
        val newCond    = rewriteExpr(cond)
        val newIfTrue  = rewriteExpr(ifTrue)
        val newIfFalse = rewriteExpr(ifFalse)
        if (newCond eq cond) && (newIfTrue eq ifTrue) && (newIfFalse eq ifFalse) then term
        else Cond(src, newCond, newIfTrue, newIfFalse, ts, ta)
      case TermGroup(src, inner, ts) =>
        val newInner = rewriteExpr(inner)
        if newInner eq inner then term
        else TermGroup(src, newInner, ts)
      case other => other

    members.map:
      case bnd: Bnd =>
        val newValue = rewriteExpr(bnd.value)
        if newValue eq bnd.value then bnd
        else bnd.copy(value = newValue)
      case other => other

  def rewriteModule(state: CompilerState): CompilerState =
    val module     = state.module
    val moduleName = module.name

    val capturingLambdas = collectCapturingLambdas(module)
    if capturingLambdas.isEmpty then return state

    // Build a map from Lambda identity (reference equality) to env struct name
    val lambdaMap = new IdentityHashMap[Lambda, String]()
    capturingLambdas.foreach((l, n) => lambdaMap.put(l, n))

    // Build ID → type map for resolving capture types
    val idTypeMap = buildIdTypeMap(module)

    // Generate env structs and free functions
    val envStructs =
      capturingLambdas.map((lambda, name) => mkEnvStruct(lambda, name, moduleName, idTypeMap))
    val freeFunctions = envStructs.map(mkFreeFunction(_, moduleName))

    // Generate universal __free_closure function (for closures returned from functions
    // where we don't know the specific env struct at the call site)
    val universalFree = mkUniversalClosureFree(moduleName)

    // Tag lambdas with envStructName
    val taggedMembers = tagLambdas(module.members, lambdaMap)

    // Add env structs, free functions, and universal free to members
    val finalMembers = taggedMembers ++ envStructs ++ freeFunctions :+ universalFree

    // Update resolvables
    val resolvablesWithStructs = envStructs.foldLeft(module.resolvables) { (idx, struct) =>
      idx.updatedType(struct)
    }
    val resolvablesWithFrees = freeFunctions.foldLeft(resolvablesWithStructs) { (idx, fn) =>
      idx.updated(fn)
    }
    val finalResolvables = resolvablesWithFrees.updated(universalFree)

    state.withModule(module.copy(members = finalMembers, resolvables = finalResolvables))

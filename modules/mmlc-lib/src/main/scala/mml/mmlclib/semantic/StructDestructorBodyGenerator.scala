package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Completes struct cleanup after function-field types and closure dispatch are available. */
object StructDestructorBodyGenerator:
  private val source   = SourceOrigin.Synth
  private val unitType = TypeRef(source, "Unit", "stdlib::typedef::Unit".some)

  private def parameterStruct(param: FnParam, index: ResolvablesIndex): Option[TypeStruct] =
    param.typeSpec.flatMap(TypeUtils.canonical(_, index)).flatMap {
      case ref: TypeRef =>
        ref.resolvedId.flatMap(index.lookupType).collect { case struct: TypeStruct =>
          struct
        }
      case _ => none
    }

  private def destroyField(
    param: FnParam,
    field: Field,
    index: ResolvablesIndex
  ): Either[SemanticError, Term] =
    val ref = Ref(
      source,
      field.name,
      qualifier  = SyntheticLocals.ref(param).some,
      resolvedId = field.id,
      typeSpec   = field.typeSpec.some
    )
    val operand    = Expr(source, List(ref), typeSpec = field.typeSpec.some)
    val isFunction = TypeUtils.canonical(field.typeSpec, index).exists(_.isInstanceOf[TypeFn])
    val target =
      if isFunction then DestructionTargets.named("__free_closure", index)
      else DestructionTargets.forType(field.typeSpec, index)
    target
      .flatMap(id => index.lookup(id).collect { case binding: Bnd => (id, binding) })
      .toRight(
        SemanticError.InvalidExpression(
          operand,
          "Missing registered destructor for struct field",
          "struct-destructor-bodies"
        )
      )
      .map { (id, binding) =>
        if isFunction then DestroyClosure(source, operand, id, unitType.some)
        else
          val callee =
            Ref(source, binding.name, resolvedId = binding.id, typeSpec = binding.typeSpec)
          App(source, callee, operand, typeSpec = unitType.some)
      }

  private def complete(binding: Bnd, module: Module): (Bnd, List[SemanticError]) =
    val eligible = for
      lambda <- binding.value.terms.collectFirst { case lambda: Lambda => lambda }
      param <- lambda.params.headOption
      struct <- parameterStruct(param, module.resolvables)
      if TypeUtils.containsFunction(struct, module.resolvables)
    yield (lambda, param, struct)
    eligible.fold((binding, List.empty[SemanticError])) { (lambda, param, struct) =>
      val cleanup = struct.fields.toList
        .filter(field => TypeUtils.requiresDestruction(field.typeSpec, module.resolvables))
        .map(destroyField(param, _, module.resolvables))
      val errors = cleanup.collect { case Left(error) => error }
      val calls  = cleanup.collect { case Right(call) => call }
      val body =
        SyntheticLocals.cleanup(calls, SyntheticOwner.binding(module.name, binding.name), unitType)
      (binding.copy(value = binding.value.copy(terms = List(lambda.copy(body = body)))), errors)
    }

  def rewriteModule(state: CompilerState): CompilerState =
    val results = state.module.members.map {
      case binding: Bnd if binding.meta.exists(_.origin == BindingOrigin.Destructor) =>
        complete(binding, state.module)
      case member => (member, Nil)
    }
    state
      .withModule(state.module.copy(members = results.map(_._1)))
      .addErrors(results.flatMap(_._2))

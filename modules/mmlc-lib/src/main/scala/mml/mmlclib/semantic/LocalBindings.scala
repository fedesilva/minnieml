package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*

import BindingIds.Allocation

object LocalBindings:

  /** Sequence cleanup expressions in declaration order and return Unit. */
  def cleanup(calls: List[Term], owner: BindingOwner, unitType: Type): Allocation[Expr] =
    val source = SourceOrigin.Synth
    val result = Expr(source, List(LiteralUnit(source, Some(unitType))), typeSpec = Some(unitType))
    calls.foldRight(result.pure[Allocation]) { (call, rest) =>
      rest.flatMap(sequence(call, _, owner, unitType))
    }

  final class Local private[LocalBindings] (val param: FnParam):

    def ref: Ref = parameterRef(param)

    def expression: Expr =
      val reference = ref
      Expr(reference.source, List(reference), typeSpec = reference.typeSpec)

  /** Apply a local scope once, retaining the parameter identity and its consuming contract. */
  def bind(
    param:  FnParam,
    value:  Expr,
    body:   Expr,
    source: SourceOrigin = SourceOrigin.Synth
  ): App =
    val signature = for
      input <- param.typeSpec.orElse(param.typeAsc)
      output <- body.typeSpec
    yield TypeFn(source, NonEmptyList.one(input), output)

    val scope = Lambda(source, List(param), body, Nil, typeSpec = signature)
    App(source, scope, value, typeSpec = body.typeSpec)

  /** Run one Unit-valued effect before the body. */
  def sequence(call: Term, body: Expr, owner: BindingOwner, unitType: Type): Allocation[Expr] =
    val source = SourceOrigin.Synth
    param(owner, "_", typeSpec = unitType.some, purpose = "cleanup").map { discard =>
      val argument = Expr(source, List(call), typeSpec = unitType.some)
      Expr(source, List(bind(discard, argument, body)), typeSpec = body.typeSpec)
    }

  /** Create a synthetic parameter with a fresh identity. */
  def param(
    owner:     BindingOwner,
    name:      String,
    typeSpec:  Option[Type] = None,
    typeAsc:   Option[Type] = None,
    consuming: Boolean      = false,
    purpose:   String       = "local"
  ): Allocation[FnParam] =
    BindingIds.fresh(
      FnParam(
        SourceOrigin.Synth,
        Name.synth(name),
        typeSpec  = typeSpec,
        typeAsc   = typeAsc,
        consuming = consuming
      ),
      owner,
      purpose
    )

  /** A resolved reference requires an assigned definition identity. */
  def reference(param: FnParam): Either[SemanticError, Ref] =
    param.id
      .toRight(
        SemanticError.InvalidExpression(
          Expr(param.source, List(parameterRef(param))),
          s"Missing binding identity for '${param.name}'",
          "local-construction"
        )
      )
      .map(_ => parameterRef(param))

  private def parameterRef(param: FnParam): Ref =
    val resolvedId = param.id
    Ref(
      SourceOrigin.Synth,
      param.name,
      typeAsc      = param.typeAsc,
      typeSpec     = param.typeSpec.orElse(param.typeAsc),
      resolvedId   = resolvedId,
      candidateIds = resolvedId.toList
    )

  /** Freshen a parameter template and construct references to the new definition. */
  def fresh(template: FnParam, owner: BindingOwner, purpose: String): Allocation[Local] =
    BindingIds.fresh(template, owner, purpose).map(new Local(_))

  def local(
    owner:     BindingOwner,
    name:      String,
    typeSpec:  Option[Type] = None,
    typeAsc:   Option[Type] = None,
    consuming: Boolean      = false,
    purpose:   String       = "local"
  ): Allocation[Local] =
    LocalBindings
      .param(
        owner,
        name,
        typeSpec  = typeSpec,
        typeAsc   = typeAsc,
        consuming = consuming,
        purpose   = purpose
      )
      .map(new Local(_))

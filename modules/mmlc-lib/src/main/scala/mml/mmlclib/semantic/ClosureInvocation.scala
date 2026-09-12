package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*

import BindingIds.Allocation

/** Transfer-bearing entries expose their environment operand and disarm payload destruction. */
def prepareClosureInvocation(lambda: Lambda, owner: BindingOwner): Allocation[Lambda] =
  lambda.meta match
    case Some(meta) if meta.transferredCaptures.nonEmpty =>
      val source      = SourceOrigin.Synth
      val pointerType = TypeRef(source, "RawPtr", "stdlib::typedef::RawPtr".some)
      val unitType    = TypeRef(source, "Unit", "stdlib::typedef::Unit".some)
      for
        environment <- LocalBindings.local(
          owner,
          "$environment",
          typeSpec = pointerType.some,
          purpose  = "environment"
        )
        discard <- LocalBindings.param(owner, "_", typeSpec = unitType.some, purpose = "disarm")
      yield
        val operand =
          Expr(source, List(environment.ref), typeSpec = pointerType.some)
        val disarm =
          DisarmClosureEnvironment(source, operand, "stdlib::bnd::mml_free_raw", unitType.some)
        val scopeType = lambda.body.typeSpec.map(TypeFn(source, NonEmptyList.one(unitType), _))
        val scope     = Lambda(source, List(discard), lambda.body, Nil, typeSpec = scopeType)
        val call = App(
          source,
          scope,
          Expr(source, List(disarm), typeSpec = unitType.some),
          typeSpec = lambda.body.typeSpec
        )
        lambda.copy(
          body = lambda.body.copy(terms = List(call)),
          meta = meta.copy(environmentParam = environment.param.some).some
        )
    case _ => lambda.pure[Allocation]

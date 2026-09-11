package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Transfer-bearing entries expose their environment operand and disarm payload destruction. */
def prepareClosureInvocation(lambda: Lambda, owner: SyntheticOwner): Lambda =
  lambda.meta match
    case Some(meta) if meta.transferredCaptures.nonEmpty =>
      val source      = SourceOrigin.Synth
      val pointerType = TypeRef(source, "RawPtr", "stdlib::typedef::RawPtr".some)
      val unitType    = TypeRef(source, "Unit", "stdlib::typedef::Unit".some)
      val environment = SyntheticLocals.param(owner, "$environment", typeSpec = pointerType.some)
      val operand =
        Expr(source, List(SyntheticLocals.ref(environment)), typeSpec = pointerType.some)
      val disarm =
        DisarmClosureEnvironment(source, operand, "stdlib::bnd::mml_free_raw", unitType.some)
      val discard   = SyntheticLocals.param(owner, "_", typeSpec = unitType.some)
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
        meta = meta.copy(environmentParam = environment.some).some
      )
    case _ => lambda

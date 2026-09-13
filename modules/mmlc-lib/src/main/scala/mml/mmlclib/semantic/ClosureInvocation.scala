package mml.mmlclib.semantic

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
        disarm =
          DisarmClosureEnvironment(
            source,
            environment.expression,
            "stdlib::bnd::mml_free_raw",
            unitType.some
          )
        body <- LocalBindings.sequence(
          disarm,
          lambda.body,
          owner,
          unitType,
          purpose = "disarm"
        )
      yield lambda.copy(
        body = body,
        meta = meta.copy(environmentParam = environment.param.some).some
      )
    case _ => lambda.pure[Allocation]

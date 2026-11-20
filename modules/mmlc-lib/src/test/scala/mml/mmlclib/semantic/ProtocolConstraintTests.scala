package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class ProtocolConstraintTests extends BaseEffFunSuite:

  private val source    = SourceOrigin.Synth
  private val parameter = TypeVariable(source, "'T", "protocol-variable".some)
  private val signature = ProtocolSignature(
    source,
    Name.synth("identity"),
    List(FnParam(source, Name.synth("value"), typeAsc = parameter.some)),
    parameter,
    BindingMeta(BindingKind.Function, CallableArity.Unary, "identity", "identity"),
    id = "identity-signature".some
  )
  private val protocol = Protocol(
    source,
    Name.synth("Identity"),
    parameter,
    List(signature),
    id = "identity-protocol".some
  )
  private val variable = TypeVariable(source, "'A", "function-variable".some)
  private val requirement = TypeVariableConstraints(
    source,
    variable,
    List(ProtocolRef(source, protocol.nameNode, protocol.id))
  )
  private val index = ResolvablesIndex().updated(protocol).updated(signature)
  private val registry = ProtocolRegistry.empty
    .registerProtocol(protocol.id.get, index)
    .fold(error => fail(s"Invalid fixture protocol: $error"), identity)
  private val candidates = ProtocolSelection.CandidateSet(
    Nil,
    List(ProtocolSignatureCandidates(protocol, signature, Nil))
  )

  private def evidence = ProtocolSelection.matchSignatures(
    candidates,
    List(ProtocolSelection.ApplicationEvidence(variable.some, Nil)),
    variable.some,
    index
  )

  test(
    "declared abstract requirements retain signature identity and instantiate its callable type"
  ):
    val selected =
      ProtocolSelection.constrainedCandidates(evidence, List(requirement), registry, index)

    assertEquals(selected.map(_.signature.id), List(signature.id))
    assertEquals(selected.map(_.protocolType.resolvedId), List(variable.resolvedId))
    assertEquals(
      selected.map(_.callableType),
      List(TypeFn(source, NonEmptyList.one(variable), variable))
    )
    assertEquals(evidence.flatMap(_.implementationIds), Nil)

  test("a requirement cannot be borrowed from a different variable or protocol with the same name"):
    val shadowed = requirement.copy(variable = variable.copy(resolvedId = "shadow".some))
    val unrelated = requirement.copy(constraints =
      requirement.constraints.map(
        _.copy(resolvedId = "other-protocol".some)
      )
    )

    List(Nil, List(shadowed), List(unrelated)).foreach { requirements =>
      assertEquals(
        ProtocolSelection.constrainedCandidates(evidence, requirements, registry, index),
        Nil
      )
    }

  test("generic forwarding requires accepted lexical evidence for the same binder"):
    assert(
      ProtocolSelection.satisfiesRequirement(
        protocol.id.get,
        variable,
        List(requirement),
        registry,
        index
      )
    )
    assert(
      !ProtocolSelection.satisfiesRequirement(
        protocol.id.get,
        variable.copy(resolvedId = "unconstrained-variable".some),
        List(requirement),
        registry,
        index
      )
    )
    assert(
      !ProtocolSelection.satisfiesRequirement(
        protocol.id.get,
        TypeUnit(source),
        List(requirement),
        registry,
        index
      )
    )

  test("unregistered and rejected protocols cannot justify abstract operations or forwarding"):
    val rejected = registry
      .rejectProtocol(protocol.id.get, Vector.empty, index)
      .fold(error => fail(s"Invalid fixture rejection: $error"), identity)

    List(ProtocolRegistry.empty, rejected).foreach { unavailable =>
      assertEquals(
        ProtocolSelection.constrainedCandidates(evidence, List(requirement), unavailable, index),
        Nil
      )
      assert(
        !ProtocolSelection.satisfiesRequirement(
          protocol.id.get,
          variable,
          List(requirement),
          unavailable,
          index
        )
      )
    }

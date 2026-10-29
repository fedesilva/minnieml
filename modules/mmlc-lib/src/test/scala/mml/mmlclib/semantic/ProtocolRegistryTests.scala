package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.DiagnosticId
import mml.mmlclib.test.BaseEffFunSuite

class ProtocolRegistryTests extends BaseEffFunSuite:

  // Shared AST declarations for Num, its + signature, and the Num Int implementation.
  // Each test registers the declarations it needs and shows its expected result directly.
  import Numbers.*

  test("register Num, then look it up by its ID") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))

    val result = registry.protocol(numId, numberIndex)

    assertEquals(result, Right(num))
  }

  test("look up the + signature belonging to Num") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))

    val result = registry.signature(numId, plusId, numberIndex)

    assertEquals(result, Right(plus))
  }

  test("signature lookup reads the updated signature from the supplied index") {
    val registry     = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val updatedPlus  = plus.copy(returnType = intType)
    val updatedIndex = numberIndex.updated(updatedPlus)

    val result = registry.signature(numId, plusId, updatedIndex)

    assertEquals(result, Right(updatedPlus))
    assertEquals(registry.signature(numId, plusId, numberIndex), Right(plus))
  }

  test("register Num Int, then find its instance ID") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val registry  = success(protocols.registerInstance(numInt, numberIndex))

    val result = registry.instance(numId, intType, numberIndex)

    assertEquals(result, Right(numIntId))
  }

  test("associate Num Int's + signature with the integer addition binding") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val instances = success(protocols.registerInstance(numInt, numberIndex))
    val registry =
      success(instances.registerMemberBinding(numIntId, plusId, addIntsId, numberIndex))

    val result = registry.memberBinding(numIntId, plusId, numberIndex)

    assertEquals(result, Right(addInts))
  }

  test("binding lookup sees a replacement body without rebuilding the registry") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val instances = success(protocols.registerInstance(numInt, numberIndex))
    val registry =
      success(instances.registerMemberBinding(numIntId, plusId, addIntsId, numberIndex))
    val replacement = addInts.copy(value = Expr(source, List(LiteralInt(source, 42, intType.some))))
    val updatedIndex = numberIndex.updated(replacement)

    val result = registry.memberBinding(numIntId, plusId, updatedIndex)

    assertEquals(result, Right(replacement))
    assertEquals(registry.memberBinding(numIntId, plusId, numberIndex), Right(addInts))
  }

  test("an unregistered protocol is a missing protocol") {
    val result = ProtocolRegistry.empty.protocol(numId, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingProtocol(numId)))
  }

  test("a known signature without instances has an empty implementation list") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))

    val result = registry.signatureCandidates(plusId, numberIndex)

    assertEquals(result, Right(ProtocolSignatureCandidates(num, plus, Nil)))
  }

  test("signature candidates read the current implementation from the index") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val instances = success(protocols.registerInstance(numInt, numberIndex))
    val registry =
      success(instances.registerMemberBinding(numIntId, plusId, addIntsId, numberIndex))
    val replacement = addInts.copy(value = Expr(source, List(LiteralInt(source, 42, intType.some))))
    val updatedIndex = numberIndex.updated(replacement)

    val result = registry.signatureCandidates(plusId, updatedIndex)

    assertEquals(
      result,
      Right(
        ProtocolSignatureCandidates(
          num,
          plus,
          List(ProtocolImplementation(numIntId, ProtocolTypeKey.Nominal(intId), replacement))
        )
      )
    )
  }

  test("signature candidates report an instance whose implementation is missing") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val registry  = success(protocols.registerInstance(numInt, numberIndex))

    val result = registry.signatureCandidates(plusId, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingMemberBinding(numIntId, plusId)))
  }

  test("a rejected instance reserves its key without appearing among implementations") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val instances = success(protocols.registerInstance(numInt, numberIndex))
    val causes    = Vector(DiagnosticId(7))
    val registry  = success(instances.rejectInstance(numIntId, causes))

    val result = registry.signatureCandidates(plusId, numberIndex)

    assertEquals(result, Right(ProtocolSignatureCandidates(num, plus, Nil)))
    assertEquals(registry.instance(numId, intType, numberIndex), Right(numIntId))
    assertEquals(registry.instanceFailureCauses(numId, intType, numberIndex), causes)
    assertEquals(
      registry.memberBinding(numIntId, plusId, numberIndex),
      Left(ProtocolLookupError.RejectedDeclaration(causes))
    )
    assertEquals(
      registry.registerInstance(numInt.copy(id = "another-instance".some), numberIndex),
      Left(
        ProtocolLookupError.DuplicateInstance(
          ProtocolInstanceKey(
            numId,
            ProtocolTypeKey.Nominal(intId)
          )
        )
      )
    )
  }

  test("a rejected signature name reports its known declaration roots") {
    val causes   = Vector(DiagnosticId(8))
    val registry = success(ProtocolRegistry.empty.rejectProtocol(numId, causes, numberIndex))
    val scope    = ProtocolLookupScope(List(List(plusId)))

    val result = registry.visibleCandidates("+", scope, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.RejectedDeclaration(causes)))
    assertEquals(
      registry.signatureCandidates(plusId, numberIndex),
      Left(ProtocolLookupError.RejectedDeclaration(causes))
    )
  }

  test("an unregistered signature without rejection evidence remains a registry error") {
    val scope = ProtocolLookupScope(List(List(plusId)))

    val result = ProtocolRegistry.empty.visibleCandidates("+", scope, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.WrongNode(plusId, "registered signature")))
  }

  test("signature candidates keep concrete implementations distinct") {
    val floatDeclaration = TypeDef(
      source   = source,
      nameNode = Name.synth("Float"),
      typeSpec = NativePrimitive(source, "double").some,
      id       = "float-type".some
    )
    val floatType = TypeRef(source, "Float", floatDeclaration.id)
    val addFloats = Bnd(
      source   = source,
      nameNode = Name.synth("addFloats"),
      value    = Expr(source, List(LiteralFloat(source, 0.0f, floatType.some))),
      id       = "float-addition".some
    )
    val numFloat = ProtocolInstance(
      source,
      ProtocolRef(source, num.nameNode, num.id),
      floatType,
      List(ProtocolInstanceMember(addFloats, BindingOrigin.Operator)),
      id = "num-float-instance".some
    )
    val index       = numberIndex.updatedType(floatDeclaration).updated(addFloats)
    val protocols   = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val intInstance = success(protocols.registerInstance(numInt, index))
    val instances   = success(intInstance.registerInstance(numFloat, index))
    val intMember   = success(instances.registerMemberBinding(numIntId, plusId, addIntsId, index))
    val registry = success(
      intMember.registerMemberBinding(
        "num-float-instance",
        plusId,
        "float-addition",
        index
      )
    )

    val result = registry.signatureCandidates(plusId, index)

    assertEquals(
      result,
      Right(
        ProtocolSignatureCandidates(
          num,
          plus,
          List(
            ProtocolImplementation(
              "num-float-instance",
              ProtocolTypeKey.Nominal("float-type"),
              addFloats
            ),
            ProtocolImplementation(numIntId, ProtocolTypeKey.Nominal(intId), addInts)
          )
        )
      )
    )
  }

  test("a signature not belonging to Num is a missing signature") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))

    val result = registry.signature(numId, "unknown-signature", numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingSignature(numId, "unknown-signature")))
  }

  test("Num can exist without a Num Int instance") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))

    val result = registry.instance(numId, intType, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingInstance(numIntKey)))
  }

  test("binding lookup reports an unregistered instance") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))

    val result = registry.memberBinding(numIntId, plusId, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingInstanceId(numIntId)))
  }

  test("Num Int can be registered before its + binding is associated") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val registry  = success(protocols.registerInstance(numInt, numberIndex))

    val result = registry.memberBinding(numIntId, plusId, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingMemberBinding(numIntId, plusId)))
  }

  test("a registered signature missing from the current index is an error") {
    val registry        = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val incompleteIndex = numberIndex.copy(resolvables = numberIndex.resolvables - plusId)

    val result = registry.signature(numId, plusId, incompleteIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingNode(plusId)))
  }

  test("a binding cannot masquerade as a signature in the index") {
    val registry   = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val wrongIndex = numberIndex.updated(addInts.copy(id = plusId.some))

    val result = registry.signature(numId, plusId, wrongIndex)

    assertEquals(result, Left(ProtocolLookupError.WrongNode(plusId, "signature")))
  }

  test("an indexed node must carry the ID used to retrieve it") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val wrongIndex =
      numberIndex.copy(resolvables = numberIndex.resolvables.updated(plusId, addInts))

    val result = registry.signature(numId, plusId, wrongIndex)

    assertEquals(result, Left(ProtocolLookupError.WrongNode(plusId, "matching stable identity")))
  }

  test("registering Num twice is rejected") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))

    val result = registry.registerProtocol(numId, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.DuplicateProtocol(numId)))
  }

  test("Num cannot list the same signature ID twice") {
    val repeatedPlus = num.copy(signatures = List(plus, plus))
    val index        = numberIndex.updated(repeatedPlus)

    val result = ProtocolRegistry.empty.registerProtocol(numId, index)

    assertEquals(result, Left(ProtocolLookupError.DuplicateSignature(plusId)))
  }

  test("two protocols cannot own the same signature ID") {
    val registry      = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val otherProtocol = num.copy(nameNode = Name.synth("Other"), id = "other-protocol".some)
    val index         = numberIndex.updated(otherProtocol)

    val result = registry.registerProtocol("other-protocol", index)

    assertEquals(result, Left(ProtocolLookupError.DuplicateSignature(plusId)))
  }

  test("an instance declaration ID cannot be registered twice") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val registry  = success(protocols.registerInstance(numInt, numberIndex))

    val result = registry.registerInstance(numInt, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.DuplicateInstanceId(numIntId)))
  }

  test("a second Num Int declaration is a duplicate even with a different instance ID") {
    val protocols    = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val registry     = success(protocols.registerInstance(numInt, numberIndex))
    val secondNumInt = numInt.copy(id = "second-num-int".some)

    val result = registry.registerInstance(secondNumInt, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.DuplicateInstance(numIntKey)))
  }

  test("an instance's signature cannot be assigned a binding twice") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val instances = success(protocols.registerInstance(numInt, numberIndex))
    val registry =
      success(instances.registerMemberBinding(numIntId, plusId, addIntsId, numberIndex))

    val result = registry.registerMemberBinding(numIntId, plusId, addIntsId, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.DuplicateMemberBinding(numIntId, plusId)))
  }

  test("an instance needs its own assigned ID") {
    val registry   = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val unassigned = numInt.copy(id = None)

    val result = registry.registerInstance(unassigned, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingId(unassigned)))
  }

  test("an instance needs a resolved protocol reference") {
    val registry   = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val unresolved = numInt.copy(protocol = numInt.protocol.copy(resolvedId = None))

    val result = registry.registerInstance(unresolved, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingId(unresolved.protocol)))
  }

  test("Num must be registered before Num Int") {
    val result = ProtocolRegistry.empty.registerInstance(numInt, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingProtocol(numId)))
  }

  test("a protocol signature needs an assigned ID") {
    val unassignedPlus = plus.copy(id = None)
    val index          = numberIndex.updated(num.copy(signatures = List(unassignedPlus)))

    val result = ProtocolRegistry.empty.registerProtocol(numId, index)

    assertEquals(result, Left(ProtocolLookupError.MissingId(unassignedPlus)))
  }

  test("a binding association must name a signature belonging to the instance's protocol") {
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val registry  = success(protocols.registerInstance(numInt, numberIndex))

    val result =
      registry.registerMemberBinding(numIntId, "unknown-signature", addIntsId, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingSignature(numId, "unknown-signature")))
  }

  test("Count is an alias of Int, so Num Count finds the Num Int instance") {
    val count = TypeAlias(
      source   = source,
      nameNode = Name.synth("Count"),
      typeRef  = intType,
      id       = "count-type".some
    )
    val countType = TypeRef(source, "Count", "count-type".some)
    val index     = numberIndex.updatedType(count)
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val registry  = success(protocols.registerInstance(numInt, index))

    val result = registry.instance(numId, countType, index)

    assertEquals(result, Right(numIntId))
  }

  test("Num Count duplicates Num Int when Count aliases Int") {
    val count = TypeAlias(
      source   = source,
      nameNode = Name.synth("Count"),
      typeRef  = intType,
      id       = "count-type".some
    )
    val countType = TypeRef(source, "Count", "count-type".some)
    val index     = numberIndex.updatedType(count)
    val protocols = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val registry  = success(protocols.registerInstance(numInt, index))
    val numCount  = numInt.copy(id = "num-count".some, concreteType = countType)

    val result = registry.registerInstance(numCount, index)

    assertEquals(result, Left(ProtocolLookupError.DuplicateInstance(numIntKey)))
  }

  test("Metres and Seconds remain distinct even though both use i64") {
    val metres = TypeDef(
      source   = source,
      nameNode = Name.synth("Metres"),
      typeSpec = NativePrimitive(source, "i64").some,
      id       = "metres-type".some
    )
    val seconds = TypeDef(
      source   = source,
      nameNode = Name.synth("Seconds"),
      typeSpec = NativePrimitive(source, "i64").some,
      id       = "seconds-type".some
    )
    val metresType    = TypeRef(source, "Metres", "metres-type".some)
    val secondsType   = TypeRef(source, "Seconds", "seconds-type".some)
    val index         = numberIndex.updatedAllTypes(List(metres, seconds))
    val numMetres     = numInt.copy(id = "num-metres".some, concreteType = metresType)
    val numSeconds    = numInt.copy(id = "num-seconds".some, concreteType = secondsType)
    val protocols     = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val firstInstance = success(protocols.registerInstance(numMetres, index))
    val registry      = success(firstInstance.registerInstance(numSeconds, index))

    assertEquals(registry.instance(numId, metresType, index), Right("num-metres"))
    assertEquals(registry.instance(numId, secondsType, index), Right("num-seconds"))
  }

  test("an unresolved type name cannot identify an instance type") {
    val unresolvedInt = TypeRef(source, "Int")

    val result = ProtocolRegistry.typeKey(unresolvedInt, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.InvalidConcreteType(unresolvedInt)))
  }

  test("an alias cycle is rejected instead of looping") {
    val cycleType = TypeRef(source, "Cycle", "cycle-type".some)
    val cycle = TypeAlias(
      source   = source,
      nameNode = Name.synth("Cycle"),
      typeRef  = cycleType,
      id       = "cycle-type".some
    )
    val index = numberIndex.updatedType(cycle)

    val result = ProtocolRegistry.typeKey(cycleType, index)

    assertEquals(result, Left(ProtocolLookupError.InvalidConcreteType(cycleType)))
  }

  test("a type variable is not a concrete instance type") {
    val variable = TypeVariable(source, "'T")

    val result = ProtocolRegistry.typeKey(variable, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.InvalidConcreteType(variable)))
  }

  test("a polymorphic type scheme is not a concrete instance type") {
    val polymorphic = TypeScheme(source, List("'T"), intType)

    val result = ProtocolRegistry.typeKey(polymorphic, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.InvalidConcreteType(polymorphic)))
  }

  test("a raw LLVM representation is not a nominal type identity") {
    val native = NativePrimitive(source, "i64")

    val result = ProtocolRegistry.typeKey(native, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.InvalidConcreteType(native)))
  }

  test("a struct and a reference to it have the same nominal identity") {
    val point = TypeStruct(
      source,
      None,
      Visibility.Protected,
      Name.synth("Point"),
      Vector.empty,
      "point-type".some
    )
    val pointType = TypeRef(source, "Point", "point-type".some)
    val index     = numberIndex.updatedType(point)
    val expected  = ProtocolTypeKey.Nominal("point-type")

    assertEquals(ProtocolRegistry.typeKey(point, index), Right(expected))
    assertEquals(ProtocolRegistry.typeKey(pointType, index), Right(expected))
  }

  test("parentheses do not change the identity of Int -> (Int, Unit)") {
    val resultType    = TypeTuple(source, List(intType, TypeUnit(source)))
    val functionType  = TypeFn(source, NonEmptyList.one(intType), resultType)
    val parenthesized = TypeGroup(source, List(functionType))
    val expected = ProtocolTypeKey.Function(
      List(ProtocolTypeKey.Nominal(intId)),
      ProtocolTypeKey.Tuple(List(ProtocolTypeKey.Nominal(intId), ProtocolTypeKey.Unit))
    )

    assertEquals(ProtocolRegistry.typeKey(functionType, numberIndex), Right(expected))
    assertEquals(ProtocolRegistry.typeKey(parenthesized, numberIndex), Right(expected))
  }

  test("two visible protocols keep both + signatures even with no instances") {
    val vectorPlus = plus.copy(id = "vector-plus".some)
    val vector = num.copy(
      nameNode   = Name.synth("Vector"),
      id         = "vector-protocol".some,
      signatures = List(vectorPlus)
    )
    val index    = numberIndex.updated(vector).updated(vectorPlus)
    val numbers  = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val registry = success(numbers.registerProtocol("vector-protocol", index))
    val scope    = ProtocolLookupScope(List(List(plusId, "vector-plus")))

    val result = registry.candidates("+", CallableArity.Binary.some, scope, index)

    assertEquals(
      result,
      Right(
        List(
          ProtocolCandidate.Signature(numId, plusId),
          ProtocolCandidate.Signature("vector-protocol", "vector-plus")
        )
      )
    )
  }

  test("reversing visibility order still keeps both protocol candidates") {
    val vectorPlus = plus.copy(id = "vector-plus".some)
    val vector = num.copy(
      nameNode   = Name.synth("Vector"),
      id         = "vector-protocol".some,
      signatures = List(vectorPlus)
    )
    val index    = numberIndex.updated(vector).updated(vectorPlus)
    val numbers  = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val registry = success(numbers.registerProtocol("vector-protocol", index))
    val scope    = ProtocolLookupScope(List(List("vector-plus", plusId)))

    val result = registry.candidates("+", CallableArity.Binary.some, scope, index)

    assertEquals(
      result,
      Right(
        List(
          ProtocolCandidate.Signature("vector-protocol", "vector-plus"),
          ProtocolCandidate.Signature(numId, plusId)
        )
      )
    )
  }

  test("a local parameter named combine hides an outer protocol signature named combine") {
    val combine = plus.copy(
      nameNode = Name.synth("combine"),
      meta     = plus.meta.copy(origin = BindingOrigin.Function, originalName = "combine")
    )
    val local    = FnParam(source, Name.synth("combine"), id = "local-combine".some)
    val index    = numberIndex.updated(combine).updated(local)
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val scope    = ProtocolLookupScope(List(List("local-combine"), List(plusId)))

    val result = registry.candidates("combine", None, scope, index)

    assertEquals(result, Right(List(ProtocolCandidate.Declaration("local-combine"))))
  }

  test("an absent source name has no candidates") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val scope    = ProtocolLookupScope(List(List(plusId)))

    val result = registry.candidates("absent", None, scope, numberIndex)

    assertEquals(result, Right(Nil))
  }

  test("binary lookup keeps both free and protocol operators without choosing precedence") {
    val freePlus = addInts.copy(meta = plus.meta.copy(precedence = 90).some)
    val index    = numberIndex.updated(freePlus)
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val scope    = ProtocolLookupScope(List(List(plusId, addIntsId)))

    val result = registry.candidates("+", CallableArity.Binary.some, scope, index)

    assertEquals(
      result,
      Right(
        List(
          ProtocolCandidate.Signature(numId, plusId),
          ProtocolCandidate.Declaration(addIntsId)
        )
      )
    )
  }

  test("unary lookup excludes a binary operator with the same spelling") {
    val unaryPlus =
      addInts.copy(id = "unary-plus".some, meta = plus.meta.copy(arity = CallableArity.Unary).some)
    val index    = numberIndex.updated(unaryPlus)
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val scope    = ProtocolLookupScope(List(List(plusId, "unary-plus")))

    val result = registry.candidates("+", CallableArity.Unary.some, scope, index)

    assertEquals(result, Right(List(ProtocolCandidate.Declaration("unary-plus"))))
  }

  test("ordinary value lookup does not return an operator") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val scope    = ProtocolLookupScope(List(List(plusId)))

    val result = registry.candidates("+", None, scope, numberIndex)

    assertEquals(result, Right(Nil))
  }

  test("visible lookup retains unary and binary candidates before grouping") {
    val unaryPlus =
      addInts.copy(id = "unary-plus".some, meta = plus.meta.copy(arity = CallableArity.Unary).some)
    val index    = numberIndex.updated(unaryPlus)
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val scope    = ProtocolLookupScope(List(List(plusId, "unary-plus")))

    val result = registry.visibleCandidates("+", scope, index)

    assertEquals(
      result,
      Right(
        List(
          ProtocolCandidate.Signature(numId, plusId),
          ProtocolCandidate.Declaration("unary-plus")
        )
      )
    )
    assertEquals(result.map(_.map(_.id)), Right(List(plusId, "unary-plus")))
  }

  test("operator arity filtering cannot uncover a declaration hidden by a local name") {
    val local    = FnParam(source, Name.synth("+"), id = "local-plus".some)
    val index    = numberIndex.updated(local)
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, index))
    val outer    = ProtocolLookupScope(List(List(plusId)))
    val scope    = outer.within(List(local))

    assertEquals(
      registry.visibleCandidates("+", scope, index),
      Right(List(ProtocolCandidate.Declaration("local-plus")))
    )
    assertEquals(registry.candidates("+", CallableArity.Binary.some, scope, index), Right(Nil))
  }

  test("listing the same visible signature twice does not duplicate its candidate") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val scope    = ProtocolLookupScope(List(List(plusId, plusId)))

    val result = registry.candidates("+", CallableArity.Binary.some, scope, numberIndex)

    assertEquals(result, Right(List(ProtocolCandidate.Signature(numId, plusId))))
  }

  test("a visible ID missing from the index is reported") {
    val registry = success(ProtocolRegistry.empty.registerProtocol(numId, numberIndex))
    val scope    = ProtocolLookupScope(List(List("missing-node")))

    val result = registry.candidates("+", CallableArity.Binary.some, scope, numberIndex)

    assertEquals(result, Left(ProtocolLookupError.MissingNode("missing-node")))
  }

  test("Unit syntax and the built-in Unit declaration have the same identity") {
    val unitId = BindingIds.declaration("stdlib", "Unit", "typedef")
    val unit = TypeDef(
      source   = source,
      nameNode = Name.synth("Unit"),
      typeSpec = NativePrimitive(source, "void").some,
      id       = unitId.some
    )
    val unitType = TypeRef(source, "Unit", unitId.some)
    val index    = numberIndex.updatedType(unit)

    assertEquals(ProtocolRegistry.typeKey(TypeUnit(source), index), Right(ProtocolTypeKey.Unit))
    assertEquals(ProtocolRegistry.typeKey(unitType, index), Right(ProtocolTypeKey.Unit))
  }

  test("a user type named Unit does not become the built-in Unit type") {
    val userUnit = TypeDef(
      source   = source,
      nameNode = Name.synth("Unit"),
      typeSpec = NativePrimitive(source, "void").some,
      id       = "user-unit".some
    )
    val userUnitType = TypeRef(source, "Unit", "user-unit".some)
    val index        = numberIndex.updatedType(userUnit)

    val result = ProtocolRegistry.typeKey(userUnitType, index)

    assertEquals(result, Right(ProtocolTypeKey.Nominal("user-unit")))
  }

  private def success[A](result: Either[ProtocolLookupError, A]): A =
    result.fold(error => fail(s"Unexpected setup failure: $error"), identity)

  private object Numbers:

    val source:    SourceOrigin = SourceOrigin.Synth
    val numId:     String       = "num-protocol"
    val plusId:    String       = "num-plus-signature"
    val intId:     String       = "int-type"
    val numIntId:  String       = "num-int-instance"
    val addIntsId: String       = "integer-addition"

    val intDeclaration: TypeDef = TypeDef(
      source   = source,
      nameNode = Name.synth("Int"),
      typeSpec = NativePrimitive(source, "i64").some,
      id       = intId.some
    )
    val intType:       TypeRef      = TypeRef(source, "Int", intId.some)
    val typeParameter: TypeVariable = TypeVariable(source, "'T")

    val plus: ProtocolSignature = ProtocolSignature(
      source   = source,
      nameNode = Name.synth("+"),
      params = List(
        FnParam(source, Name.synth("left"), typeAsc  = typeParameter.some),
        FnParam(source, Name.synth("right"), typeAsc = typeParameter.some)
      ),
      returnType = typeParameter,
      meta = BindingMeta(
        BindingOrigin.Operator,
        CallableArity.Binary,
        10,
        Associativity.Left.some,
        "+",
        "plus"
      ),
      id = plusId.some
    )
    val num: Protocol =
      Protocol(source, Name.synth("Num"), typeParameter, List(plus), id = numId.some)

    // The registry stores this binding's ID; it does not type-check or execute its body.
    val addInts: Bnd = Bnd(
      source   = source,
      nameNode = Name.synth("addInts"),
      value = Expr(
        source,
        List(
          Lambda(
            source,
            List(
              FnParam(source, Name.synth("left"), typeAsc  = intType.some),
              FnParam(source, Name.synth("right"), typeAsc = intType.some)
            ),
            Expr(source, List(LiteralInt(source, 0, intType.some))),
            Nil
          )
        )
      ),
      id = addIntsId.some
    )
    val numInt: ProtocolInstance = ProtocolInstance(
      source,
      ProtocolRef(source, num.nameNode, num.id),
      intType,
      List(ProtocolInstanceMember(addInts, BindingOrigin.Operator)),
      id = numIntId.some
    )
    val numIntKey: ProtocolInstanceKey = ProtocolInstanceKey(numId, ProtocolTypeKey.Nominal(intId))
    val numberIndex: ResolvablesIndex = ResolvablesIndex()
      .updatedType(intDeclaration)
      .updated(num)
      .updated(plus)
      .updated(addInts)

package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class CallStagesTests extends BaseEffFunSuite:

  private val source    = SourceOrigin.Synth
  private val integer   = TypeRef(source, "Int")
  private val unit      = TypeRef(source, "Unit")
  private val number    = expression(LiteralInt(source, 1, integer.some))
  private val unitValue = expression(LiteralUnit(source, unit.some))

  private def expression(term: Term): Expr = Expr(source, List(term), typeSpec = term.typeSpec)

  private def parameter(id: String, tpe: Type, consuming: Boolean = false): FnParam =
    FnParam(source, Name.synth(id), typeSpec = tpe.some, id = id.some, consuming = consuming)

  private def reference(parameter: FnParam): Ref =
    Ref(source, parameter.name, typeSpec = parameter.typeSpec, resolvedId = parameter.id)

  private def reference(binding: Bnd): Ref =
    Ref(source, binding.name, typeSpec = binding.typeSpec, resolvedId = binding.id)

  private def lambda(parameters: List[FnParam], body: Expr): Lambda =
    val inputs = NonEmptyList
      .fromList(parameters.flatMap(_.typeSpec))
      .getOrElse(NonEmptyList.one(unit))
    val output = body.typeSpec.getOrElse(fail("Expected fixture body type"))
    Lambda(source, parameters, body, Nil, TypeFn(source, inputs, output).some)

  private def binding(id: String, value: Term): Bnd =
    Bnd(
      source   = source,
      nameNode = Name.synth(id),
      value    = expression(value),
      typeSpec = value.typeSpec,
      id       = id.some
    )

  private def application(callee: Ref | App | Lambda, argument: Expr, result: Type): App =
    App(source, callee, argument, typeSpec = result.some)

  private def flow(bindings: List[Bnd], types: List[ResolvableType] = Nil): CallableValues =
    val parameters = bindings.flatMap { binding =>
      TermTraversal.collect(binding.value) { case lambda: Lambda => lambda.params }.flatten
    }
    val index =
      ResolvablesIndex().updatedAll(bindings).updatedAll(parameters).updatedAllTypes(types)
    CallableValues.fromModule(
      Module(source, "Stages", Visibility.Public, bindings, resolvables = index)
    )

  test("nullary and explicit Unit calls complete with distinct source parameters") {
    val explicit    = parameter("explicit-unit", unit)
    val nullary     = binding("nullary", lambda(Nil, number))
    val unary       = binding("unary", lambda(List(explicit), number))
    val values      = flow(List(nullary, unary))
    val nullaryCall = application(reference(nullary), unitValue, integer)
    val unaryCall   = application(reference(unary), unitValue, integer)

    assert(values.stages(nullaryCall).forall(_.complete))
    assert(values.stages(unaryCall).forall(_.complete))
    assertEquals(values.parameters(reference(nullary)), Nil)
    assertEquals(values.parameters(reference(unary)).flatMap(_.id), explicit.id.toList)
    assertEquals(values.invokedLambdas(nullaryCall).map(_.params), List(Nil))
    assertEquals(values.provenInvokedLambdas(nullaryCall).map(_.map(_.params)), List(Nil).some)
  }

  test("multi-parameter stage retains a partial prefix and completes at its own boundary") {
    val left     = parameter("left", integer)
    val right    = parameter("right", integer, consuming = true)
    val target   = binding("binary", lambda(List(left, right), number))
    val residual = TypeFn(source, NonEmptyList.one(integer), integer)
    val partial  = application(reference(target), number, residual)
    val complete = application(partial, number, integer)
    val values   = flow(List(target))

    assertEquals(values.stages(partial).map(_.complete), List(false))
    assertEquals(values.stages(partial).map(_.isPartial), List(true))
    assertEquals(values.parameters(partial).flatMap(_.id), right.id.toList)
    assertEquals(values.invokedLambdas(partial), Nil)
    assertEquals(values.stages(complete).map(_.applications), List(List(partial, complete)))
    assertEquals(values.stages(complete).map(_.isPartial), List(false))
    assertEquals(values.invokedLambdas(complete).map(_.params), List(List(left, right)))
  }

  test("nullary and explicit Unit alternatives agree without inventing source parameters") {
    val input     = parameter("unit-input", unit)
    val nullary   = binding("nullary-alternative", lambda(Nil, number))
    val explicit  = binding("unit-alternative", lambda(List(input), number))
    val predicate = expression(LiteralBool(source, true, TypeRef(source, "Bool").some))
    val alternatives = Cond(
      source,
      predicate,
      expression(reference(nullary)),
      expression(reference(explicit)),
      nullary.typeSpec
    )
    val selected = binding("unit-selection", alternatives)
    val call     = application(reference(selected), unitValue, integer)
    val values   = flow(List(nullary, explicit, selected, binding("unit-result", call)))

    assert(values.hasConsistentParameterOwnership(reference(selected)))
    assertEquals(values.invokedLambdas(call).map(_.params).toSet, Set(Nil, List(input)))
    assertEquals(input.id.toList.flatMap(values.bindings.get).flatten, List(unitValue))
  }

  test("returned callables form separate stages and select final invocation targets") {
    val target = binding("target", lambda(List(parameter("value", integer)), number))
    val middle = binding("middle", lambda(Nil, expression(reference(target))))
    val outer  = binding("outer", lambda(Nil, expression(reference(middle))))
    val first = application(
      reference(outer),
      unitValue,
      middle.typeSpec.getOrElse(fail("Expected middle signature"))
    )
    val second =
      application(first, unitValue, target.typeSpec.getOrElse(fail("Expected target signature")))
    val third  = application(second, number, integer)
    val values = flow(List(target, middle, outer))

    assertEquals(values.stages(third).map(_.result), List(first, second, third))
    assertEquals(values.stages(third).map(_.callee), List(reference(outer), first, second))
    assert(values.stages(third).forall(_.complete))
    assertEquals(values.invokedLambdas(third), values.lambdas(reference(target)))
    assertEquals(values.provenInvokedLambdas(third), values.provenLambdas(reference(target)))
  }

  test("later-stage callable arguments propagate into actual parameters before saturation") {
    val supplied     = binding("supplied", lambda(List(parameter("input", integer)), number))
    val callableType = supplied.typeSpec.getOrElse(fail("Expected callable type"))
    val callable     = parameter("later-callable", callableType, consuming = true)
    val remaining    = parameter("later-number", integer)
    val target       = binding("later-target", lambda(List(callable, remaining), number))
    val choose       = binding("choose", lambda(Nil, expression(reference(target))))
    val returned = application(
      reference(choose),
      unitValue,
      target.typeSpec.getOrElse(fail("Expected returned signature"))
    )
    val residual = TypeFn(source, NonEmptyList.one(integer), integer)
    val partial  = application(returned, expression(reference(supplied)), residual)
    val use      = binding("use", partial)
    val values   = flow(List(supplied, target, choose, use))

    assertEquals(values.stages(partial).map(_.complete), List(true, false))
    assertEquals(values.lambdas(reference(callable)), values.lambdas(reference(supplied)))
    assertEquals(values.parameters(partial).flatMap(_.id), remaining.id.toList)
    assertEquals(values.provenLambdas(reference(callable)), None)
    assertEquals(
      values.provenInvokedLambdas(application(reference(callable), number, integer)),
      None
    )
  }

  test("incompatible later-stage alternatives cannot supply a consuming parameter contract") {
    val consumed  = parameter("consumed", integer, consuming = true)
    val borrowed  = parameter("borrowed", integer)
    val consume   = binding("consume", lambda(List(consumed), number))
    val borrow    = binding("borrow", lambda(List(borrowed), number))
    val predicate = expression(LiteralBool(source, true, TypeRef(source, "Bool").some))
    val alternatives = Cond(
      source,
      predicate,
      expression(reference(consume)),
      expression(reference(borrow)),
      consume.typeSpec
    )
    val choose = binding("choose-alternative", lambda(Nil, expression(alternatives)))
    val returned = application(
      reference(choose),
      unitValue,
      consume.typeSpec.getOrElse(fail("Expected alternative signature"))
    )
    val values = flow(List(consume, borrow, choose))

    assert(!values.hasConsistentParameterOwnership(returned))
    assertEquals(values.parameters(returned), Nil)
    assertEquals(values.invokedLambdas(application(returned, number, integer)).size, 2)
  }

  test("unavailable operands retain their applications without establishing invocation evidence") {
    val target  = binding("unavailable-target", lambda(List(parameter("value", integer)), number))
    val invalid = expression(InvalidExpression(source, number, integer.some))
    val call    = application(reference(target), invalid, integer)
    val values  = flow(List(target))

    assertEquals(values.stages(call).map(_.applications), List(List(call)))
    assertEquals(values.stages(call).map(_.arguments), List(List(invalid)))
    assertEquals(values.stages(call).map(_.complete), List(false))
    assertEquals(values.stages(call).map(_.isPartial), List(false))
    assertEquals(values.invokedLambdas(call), Nil)
    assertEquals(values.provenInvokedLambdas(call), None)

    val immediate =
      application(lambda(List(parameter("immediate", integer)), number), invalid, integer)
    assertEquals(values.stages(immediate).map(_.complete), List(false))
    assertEquals(values.invokedLambdas(immediate), Nil)
    assertEquals(values.provenInvokedLambdas(immediate), None)
  }

  test("result references preserve caller arguments and local aliases without invoked callee IDs") {
    val target = binding("reference-target", lambda(List(parameter("value", integer)), number))
    val callableType = target.typeSpec.getOrElse(fail("Expected target signature"))
    val argument     = parameter("argument", callableType)
    val local        = parameter("local-alias", callableType)
    val aliasBody    = lambda(List(local), expression(reference(local)))
    val aliasCall    = application(aliasBody, expression(reference(argument)), callableType)
    val identity     = binding("identity", lambda(List(argument), expression(aliasCall)))
    val call         = application(reference(identity), expression(reference(target)), callableType)
    val values       = flow(List(target, identity))

    assertEquals(
      values.resultReferences(call).flatMap(_.resolvedId).toSet,
      Set(local.id, argument.id, target.id).flatten
    )
    assertEquals(values.provenLambdas(call), values.provenLambdas(reference(target)))
  }

  test("scoped generated bindings preserve lexical proofs without completing unknown formals") {
    val targetLambda = lambda(List(parameter("value", integer)), number)
    val target       = binding("scoped-target", targetLambda)
    val callableType = target.typeSpec.getOrElse(fail("Expected target signature"))
    val formal       = parameter("observed-formal", callableType)
    val receiver     = binding("receiver", lambda(List(formal), number))
    val call = binding(
      "observed-call",
      application(reference(receiver), expression(reference(target)), integer)
    )
    val observed = flow(List(target, receiver, call))

    assertEquals(observed.lambdas(reference(formal)), List(targetLambda))
    assertEquals(observed.provenLambdas(reference(formal)), None)

    val generated      = parameter("generated", callableType)
    val generatedAlias = parameter("generated-formal-alias", callableType)
    val values = observed.withBindings(
      List(
        generated -> expression(reference(target)),
        generatedAlias -> expression(reference(formal))
      )
    )

    assertEquals(values.provenLambdas(reference(generated)), List(targetLambda).some)

    List(formal, generatedAlias).foreach { unresolved =>
      assertEquals(values.lambdas(reference(unresolved)), List(targetLambda))
      assertEquals(values.provenLambdas(reference(unresolved)), None)
    }

    List(generated, generatedAlias).foreach { local =>
      assertEquals(values.index.lookup(local.id.getOrElse(fail("Expected identity"))), local.some)
    }
  }

  test("canonical callable aliases retain their multi-parameter invocation boundary") {
    val target = binding(
      "aliased-target",
      lambda(List(parameter("first", integer), parameter("second", integer)), number)
    )
    val signature = target.typeSpec.getOrElse(fail("Expected callable signature"))
    val alias = TypeAlias(
      source   = source,
      nameNode = Name.synth("Callable"),
      typeRef  = signature,
      id       = "callable-type".some
    )
    val aliasType = TypeRef(source, alias.name, alias.id)
    val aliased   = target.copy(typeSpec = aliasType.some)
    val values    = flow(List(aliased), List(alias))
    val partial =
      application(reference(aliased), number, TypeFn(source, NonEmptyList.one(integer), integer))
    val complete = application(partial, number, integer)

    assertEquals(values.stages(complete).map(_.applications), List(List(partial, complete)))
    assertEquals(values.stages(complete).flatMap(_.signature), List(signature))
    assertEquals(values.invokedLambdas(complete), values.lambdas(reference(aliased)))
  }

  test("an unknown callable alternative remains unknown when another alternative is observed") {
    val target = binding("known-target", lambda(List(parameter("input", integer)), number))
    val unknown =
      parameter("unknown-formal", target.typeSpec.getOrElse(fail("Expected callable type")))
    val predicate = expression(LiteralBool(source, true, TypeRef(source, "Bool").some))
    val alternatives = Cond(
      source,
      predicate,
      expression(reference(target)),
      expression(reference(unknown)),
      target.typeSpec
    )
    val selected = binding("selected", alternatives)
    val values   = flow(List(target, selected))
    val call     = application(reference(selected), number, integer)

    assertEquals(values.lambdas(reference(selected)), values.lambdas(reference(target)))
    assertEquals(values.provenLambdas(reference(selected)), None)
    assertEquals(values.invokedLambdas(call), values.lambdas(reference(target)))
    assertEquals(values.provenInvokedLambdas(call), None)
  }

  List(false, true).foreach { consuming =>
    test(s"an unresolved alternative prevents a known consuming=$consuming parameter contract") {
      val input  = parameter("known-input", integer, consuming)
      val target = binding("known-contract", lambda(List(input), number))
      val unknown =
        parameter("unresolved-contract", target.typeSpec.getOrElse(fail("Expected callable type")))
      val predicate = expression(LiteralBool(source, true, TypeRef(source, "Bool").some))
      val alternatives = Cond(
        source,
        predicate,
        expression(reference(target)),
        expression(reference(unknown)),
        target.typeSpec
      )
      val choose = binding("choose-contract", lambda(List(unknown), expression(alternatives)))
      val returned = application(
        reference(choose),
        expression(reference(unknown)),
        target.typeSpec.getOrElse(fail("Expected returned signature"))
      )
      val values = flow(List(target, choose))

      assertEquals(values.lambdas(returned), values.lambdas(reference(target)))
      assert(!values.hasConsistentParameterOwnership(returned))
      assertEquals(values.parameters(returned), Nil)
      assertEquals(values.provenLambdas(returned), None)
      assert(values.hasConsistentParameterOwnership(reference(unknown)))
      assertEquals(values.parameters(reference(unknown)), Nil)
    }
  }

  test("constructor fields preserve callable origins without returning their aggregate owner") {
    val target       = binding("field-target", lambda(List(parameter("input", integer)), number))
    val callableType = target.typeSpec.getOrElse(fail("Expected callable type"))
    val field        = Field(source, Name.synth("callback"), callableType, "callback-field".some)
    val struct = TypeStruct(
      source,
      None,
      Visibility.Public,
      Name.synth("Holder"),
      Vector(field),
      "holder-type".some
    )
    val payload = parameter("payload", callableType)
    val constructor = binding(
      "constructor",
      lambda(List(payload), expression(DataConstructor(source, struct.some)))
    )
    val creation = application(reference(constructor), expression(reference(target)), struct)
    val holder   = binding("holder", creation)
    val projected = Ref(
      source,
      field.name,
      typeSpec   = callableType.some,
      resolvedId = field.id,
      qualifier  = reference(holder).some
    )
    val values = flow(List(target, constructor, holder), List(struct))

    assertEquals(values.provenLambdas(projected), values.provenLambdas(reference(target)))
    assertEquals(
      values.resultReferences(projected).flatMap(_.resolvedId).toSet,
      Set(field.id, target.id).flatten
    )
  }

  test("nested calls to the same helper retain each argument's caller cycle context") {
    val target       = binding("nested-target", lambda(List(parameter("input", integer)), number))
    val callableType = target.typeSpec.getOrElse(fail("Expected callable type"))
    val input        = parameter("forwarded", callableType)
    val identity     = binding("nested-identity", lambda(List(input), expression(reference(input))))
    val inner        = application(reference(identity), expression(reference(target)), callableType)
    val outer        = application(reference(identity), expression(inner), callableType)
    val values       = flow(List(target, identity))

    assertEquals(values.provenLambdas(outer), values.provenLambdas(reference(target)))
    assert(values.hasConsistentParameterOwnership(outer))
    assertEquals(
      values.parameters(outer).flatMap(_.id),
      values.parameters(reference(target)).flatMap(_.id)
    )
    assertEquals(
      values.resultReferences(outer).flatMap(_.resolvedId).toSet,
      Set(input.id, target.id).flatten
    )
  }

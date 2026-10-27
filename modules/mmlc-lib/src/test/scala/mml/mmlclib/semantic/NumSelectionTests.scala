package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.{
  callRootReference,
  termApplication,
  termLambda,
  termReference,
  unwrappedTerm as unwrapped
}

class NumSelectionTests extends BaseEffFunSuite:

  test("all eleven Int operations select the registered Num Int members"):
    val source = """
      let sum = 8 + 2;
      let difference = 8 - 2;
      let product = 8 * 2;
      let quotient = 8 / 2;
      let equal = 8 == 2;
      let unequal = 8 != 2;
      let less = 8 < 2;
      let greater = 8 > 2;
      let at_most = 8 <= 2;
      let at_least = 8 >= 2;
      let negative = -8;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(result.errors, Vector.empty)
      assertEquals(selectedId(result, "sum"), numericMember(initial, "Int", "+").id)
      assertEquals(selectedId(result, "difference"), numericMember(initial, "Int", "-").id)
      assertEquals(selectedId(result, "product"), numericMember(initial, "Int", "*").id)
      assertEquals(selectedId(result, "quotient"), numericMember(initial, "Int", "/").id)
      assertEquals(selectedId(result, "equal"), numericMember(initial, "Int", "==").id)
      assertEquals(selectedId(result, "unequal"), numericMember(initial, "Int", "!=").id)
      assertEquals(selectedId(result, "less"), numericMember(initial, "Int", "<").id)
      assertEquals(selectedId(result, "greater"), numericMember(initial, "Int", ">").id)
      assertEquals(selectedId(result, "at_most"), numericMember(initial, "Int", "<=").id)
      assertEquals(selectedId(result, "at_least"), numericMember(initial, "Int", ">=").id)
      assertEquals(
        selectedId(result, "negative"),
        numericMember(initial, "Int", "-", CallableArity.Unary).id
      )
      assertEquals(computedType(binding(result, "sum"), result), namedType("Int", result))
      assertEquals(computedType(binding(result, "equal"), result), namedType("Bool", result))
    }

  test("all eleven Float operations select the registered Num Float members"):
    val source = """
      let sum = 8.0 + 2.0;
      let difference = 8.0 - 2.0;
      let product = 8.0 * 2.0;
      let quotient = 8.0 / 2.0;
      let equal = 8.0 == 2.0;
      let unequal = 8.0 != 2.0;
      let less = 8.0 < 2.0;
      let greater = 8.0 > 2.0;
      let at_most = 8.0 <= 2.0;
      let at_least = 8.0 >= 2.0;
      let negative = -8.0;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(result.errors, Vector.empty)
      assertEquals(selectedId(result, "sum"), numericMember(initial, "Float", "+").id)
      assertEquals(selectedId(result, "difference"), numericMember(initial, "Float", "-").id)
      assertEquals(selectedId(result, "product"), numericMember(initial, "Float", "*").id)
      assertEquals(selectedId(result, "quotient"), numericMember(initial, "Float", "/").id)
      assertEquals(selectedId(result, "equal"), numericMember(initial, "Float", "==").id)
      assertEquals(selectedId(result, "unequal"), numericMember(initial, "Float", "!=").id)
      assertEquals(selectedId(result, "less"), numericMember(initial, "Float", "<").id)
      assertEquals(selectedId(result, "greater"), numericMember(initial, "Float", ">").id)
      assertEquals(selectedId(result, "at_most"), numericMember(initial, "Float", "<=").id)
      assertEquals(selectedId(result, "at_least"), numericMember(initial, "Float", ">=").id)
      assertEquals(
        selectedId(result, "negative"),
        numericMember(initial, "Float", "-", CallableArity.Unary).id
      )
      assertEquals(computedType(binding(result, "sum"), result), namedType("Float", result))
      assertEquals(computedType(binding(result, "equal"), result), namedType("Bool", result))
    }

  test("Num Int inherits multiplicative precedence and left-associative division"):
    val source = "let mixed = 1 + 2 * 3; let divided = 8 / 4 / 2;"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result   = TypeChecker.rewriteModule(initial)
      val addition = binaryCall(binding(result, "mixed").value)
      val product  = binaryCall(addition.right)
      val outer    = binaryCall(binding(result, "divided").value)
      val inner    = binaryCall(outer.left)

      assertEquals(result.errors, Vector.empty)
      assertEquals(addition.operator.resolvedId, numericMember(initial, "Int", "+").id)
      assertEquals(product.operator.resolvedId, numericMember(initial, "Int", "*").id)
      assertEquals(intValue(addition.left), 1L)
      assertEquals(intValue(product.left), 2L)
      assertEquals(intValue(product.right), 3L)
      assertEquals(outer.operator.resolvedId, numericMember(initial, "Int", "/").id)
      assertEquals(inner.operator.resolvedId, numericMember(initial, "Int", "/").id)
      assertEquals(intValue(inner.left), 8L)
      assertEquals(intValue(inner.right), 4L)
      assertEquals(intValue(outer.right), 2L)
    }

  test("Num Float inherits unary precedence and binary subtraction associativity"):
    val source = "let product = -2.0 * 3.0; let difference = 8.0 - 4.0 - 2.0;"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result   = TypeChecker.rewriteModule(initial)
      val product  = binaryCall(binding(result, "product").value)
      val negative = application(product.left)
      val outer    = binaryCall(binding(result, "difference").value)
      val inner    = binaryCall(outer.left)

      assertEquals(result.errors, Vector.empty)
      assertEquals(product.operator.resolvedId, numericMember(initial, "Float", "*").id)
      assertEquals(
        reference(negative.fn).resolvedId,
        numericMember(initial, "Float", "-", CallableArity.Unary).id
      )
      assertEquals(floatValue(negative.arg), 2.0f)
      assertEquals(floatValue(product.right), 3.0f)
      assertEquals(outer.operator.resolvedId, numericMember(initial, "Float", "-").id)
      assertEquals(inner.operator.resolvedId, numericMember(initial, "Float", "-").id)
      assertEquals(floatValue(inner.left), 8.0f)
      assertEquals(floatValue(inner.right), 4.0f)
      assertEquals(floatValue(outer.right), 2.0f)
    }

  test("expected callable types select numeric type and unary or binary arity"):
    val source = """
      let add_int: Int -> Int -> Int = (+);
      let add_float: Float -> Float -> Float = (+);
      let negate_float: Float -> Float = (-);
      let subtract_float: Float -> Float -> Float = (-);
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result   = TypeChecker.rewriteModule(initial)
      val addInt   = reference(binding(result, "add_int").value)
      val addFloat = reference(binding(result, "add_float").value)
      val negative = reference(binding(result, "negate_float").value)
      val subtract = reference(binding(result, "subtract_float").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(addInt.resolvedId, numericMember(initial, "Int", "+").id)
      assertEquals(addFloat.resolvedId, numericMember(initial, "Float", "+").id)
      assertEquals(
        negative.resolvedId,
        numericMember(initial, "Float", "-", CallableArity.Unary).id
      )
      assertEquals(subtract.resolvedId, numericMember(initial, "Float", "-").id)
      assertNotEquals(addInt.resolvedId, addFloat.resolvedId)
      assertNotEquals(negative.resolvedId, subtract.resolvedId)
    }

  test("partial applications select Int and Float members before their later calls"):
    val source = """
      let add_int: Int -> Int = (+) 1;
      let add_float: Float -> Float = (+) 1.0;
      let int_result = add_int 2;
      let float_result = add_float 2.0;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result = TypeChecker.rewriteModule(initial)
      val int    = namedType("Int", result)
      val float  = namedType("Float", result)

      assertEquals(result.errors, Vector.empty)
      assertEquals(selectedId(result, "add_int"), numericMember(initial, "Int", "+").id)
      assertEquals(selectedId(result, "add_float"), numericMember(initial, "Float", "+").id)
      assertEquals(
        computedType(binding(result, "add_int"), result),
        ProtocolTypeKey.Function(List(int), int)
      )
      assertEquals(
        computedType(binding(result, "add_float"), result),
        ProtocolTypeKey.Function(List(float), float)
      )
      assertEquals(computedType(binding(result, "int_result"), result), int)
      assertEquals(computedType(binding(result, "float_result"), result), float)
    }

  test("an alias of Float uses the same Num instance"):
    val source = "type Amount = Float; let amount: Amount = 1.0; let total = amount + 2.0;"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(result.errors, Vector.empty)
      assertEquals(selectedId(result, "total"), numericMember(initial, "Float", "+").id)
      assertEquals(computedType(binding(result, "total"), result), namedType("Amount", result))
    }

  test("a Float operand supplies the missing lambda parameter type through Num"):
    val source = "let add = { value -> value + 1.0; };"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = lambda(binding(initial, "add").value).params.head
      val expected = numericMember(initial, "Float", "+")

      val result = TypeChecker.rewriteModule(initial)
      val body   = lambda(binding(result, "add").value)
      val float  = namedType("Float", result)

      assertEquals(result.errors, Vector.empty)
      assertEquals(body.params.head.id, original.id)
      assertEquals(computedType(body.params.head, result), float)
      assertEquals(rootReference(body.body).resolvedId, expected.id)
      assertEquals(computedType(body, result), ProtocolTypeKey.Function(List(float), float))
    }

  test("Float operand evidence reaches a lambda parameter through a local binding alias"):
    val source = "let add = { value -> let alias = value; alias + 1.0; };"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = lambda(binding(initial, "add").value).params.head
      val expected = numericMember(initial, "Float", "+")

      val result = TypeChecker.rewriteModule(initial)
      val body   = lambda(binding(result, "add").value)
      val float  = namedType("Float", result)
      val additions = TermTraversal.collect(body.body) {
        case ref: Ref if ref.name == "+" => ref
      }

      assertEquals(result.errors, Vector.empty)
      assertEquals(body.params.head.id, original.id)
      assertEquals(computedType(body.params.head, result), float)
      assertEquals(additions.map(_.resolvedId), List(expected.id))
      assertEquals(computedType(body, result), ProtocolTypeKey.Function(List(float), float))
    }

  test("a captured Float parameter supplies evidence to an otherwise untyped inner lambda"):
    val source = "fn factory(seed: Float) = ~{ value -> value + seed; };;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = lambda(binding(initial, "factory").value)
      val value    = lambda(original.body).params.head
      val expected = numericMember(initial, "Float", "+")

      val result  = TypeChecker.rewriteModule(initial)
      val factory = lambda(binding(result, "factory").value)
      val body    = lambda(factory.body)
      val float   = namedType("Float", result)

      assertEquals(result.errors, Vector.empty)
      assertEquals(body.params.head.id, value.id)
      assertEquals(computedType(body.params.head, result), float)
      assertEquals(body.captures.map(_.ref.resolvedId), List(original.params.head.id))
      assertEquals(rootReference(body.body).resolvedId, expected.id)
      assertEquals(computedType(body, result), ProtocolTypeKey.Function(List(float), float))
    }

  test("numeric usage without a concrete operand leaves both lambda parameters uninferred"):
    val source = "let add = { left, right -> left + right; };"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = lambda(binding(initial, "add").value)

      val result = TypeChecker.rewriteModule(initial)
      val uninferrable = result.visibleErrors.collect {
        case SemanticError.TypeCheckingError(TypeError.UninferrableLambdaParam(param, _)) => param
      }

      assert(result.hasErrors)
      assertEquals(uninferrable.map(_.id).toList, original.params.map(_.id))
      assertEquals(uninferrable.map(_.typeSpec), Vector(None, None))
    }

  test("an equally applicable source operator and injected Num member remain ambiguous"):
    val source = """
      op +(left: Int, right: Int): Int 60 left = left;;
      let result = 1 + 2;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial, "result").value)
      val signature = protocolSignature(
        initial.module,
        "Num",
        "+",
        Some(CallableArity.Binary)
      )
      val custom = initial.module.members.collectFirst {
        case member: Bnd if member.source.isFromSource && member.meta.exists { meta =>
              meta.origin == BindingOrigin.Operator && meta.originalName == "+"
            } =>
          member
      }.get

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(original.candidateIds.toSet, Set(signature.id.get, custom.id.get))
      result.visibleErrors match
        case Vector(SemanticError.ProtocolErrorFound(ProtocolError.AmbiguousSelection(ref), _)) =>
          assertEquals(ref.source, original.source)
          assertEquals(ref.resolvedId, None)
        case other => fail(s"Expected ambiguity between source and injected operators, got $other")
    }

  test("Int plus Float is rejected without implicit numeric conversion"):
    val source = "let result = 1 + 2.0;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(ref), _)
            ) =>
          assertEquals(ref.source, original.source)
          assertEquals(ref.resolvedId, None)
        case other => fail(s"Expected incompatible numeric arguments, got $other")
    }

  test("Float plus Int is rejected without implicit numeric conversion"):
    val source = "let result = 1.0 + 2;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(ref), _)
            ) =>
          assertEquals(ref.source, original.source)
          assertEquals(ref.resolvedId, None)
        case other => fail(s"Expected incompatible numeric arguments, got $other")
    }

  test("mixed numeric comparison operands remain incompatible despite an expected Bool"):
    val source = "let result: Bool = 1 < 2.0;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(ref), _)
            ) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected incompatible comparison arguments, got $other")
    }

  test("a concrete nonnumeric operand type reports its missing Num instance"):
    val source = "let result = true + false;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val protocol = numericProtocol(initial)
      val original = rootReference(binding(initial, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(ProtocolError.MissingInstance(ref, found, tpe), _)
            ) =>
          assertEquals(ref.source, original.source)
          assertEquals(found.id, protocol.id)
          assertEquals(typeKey(tpe, result), namedType("Bool", result))
        case other => fail(s"Expected the missing Num Bool instance, got $other")
    }

  test("all eleven removed dotted Float operations are undefined without source declarations"):
    val source = """
      let sum = 8.0 +. 2.0;
      let difference = 8.0 -. 2.0;
      let product = 8.0 *. 2.0;
      let quotient = 8.0 /. 2.0;
      let equal = 8.0 ==. 2.0;
      let unequal = 8.0 !=. 2.0;
      let less = 8.0 <. 2.0;
      let greater = 8.0 >. 2.0;
      let at_most = 8.0 <=. 2.0;
      let at_least = 8.0 >=. 2.0;
      let negative = -.8.0;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result = TypeChecker.rewriteModule(initial)
      val undefined = result.visibleErrors.collect { case SemanticError.UndefinedRef(ref, _, _) =>
        ref.name
      }

      assertEquals(
        undefined,
        Vector("+.", "-.", "*.", "/.", "==.", "!=.", "<.", ">.", "<=.", ">=.", "-.")
      )
      assertEquals(result.visibleErrors.size, 11)
    }

  test("an explicitly declared dotted operator remains a valid ordinary source operator"):
    val source = """
      op +.(left: Float, right: Float): Float 60 left = left + right;;
      let result = 1.0 +. 2.0;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      val custom = initial.module.members.collectFirst {
        case member: Bnd if member.source.isFromSource && member.meta.exists { meta =>
              meta.origin == BindingOrigin.Operator && meta.originalName == "+."
            } =>
          member
      }.get

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(result.errors, Vector.empty)
      assertEquals(selectedId(result, "result"), custom.id)
      assertEquals(computedType(binding(result, "result"), result), namedType("Float", result))
    }

  test("Num Int unary minus retains minimum-Int literal folding and its source span"):
    val source = "let minimum = -2147483648;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = application(binding(initial, "minimum").value)
      val expected = numericMember(initial, "Int", "-", CallableArity.Unary)

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result, "minimum")

      assertEquals(result.errors, Vector.empty)
      assertEquals(expected.id, Some(standardIntNegationId))
      assertEquals(intValue(value.value), Int.MinValue.toLong)
      assertEquals(unwrapped(value.value).source, original.source)
      assertEquals(computedType(value, result), namedType("Int", result))
    }

  test("Num injection does not admit a positive out-of-range Int literal"):
    val source = "let invalid = 2147483648;"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(SemanticError.InvalidExpression(literal, message, _)) =>
          val span = literal.spanOpt.get
          assertEquals(source.substring(span.start.index, span.end.index), "2147483648")
          assertEquals(message, "Integer literal is outside the Int32 range")
        case other => fail(s"Expected the positive literal range diagnostic, got $other")
    }

  private case class BinaryCall(operator: Ref, left: Expr, right: Expr)

  private def numericProtocol(state: CompilerState): Protocol =
    state.module.members
      .collectFirst { case protocol: Protocol if protocol.name == "Num" => protocol }
      .getOrElse(fail("Expected injected Num protocol"))

  private def numericMember(
    state:    CompilerState,
    typeName: String,
    symbol:   String,
    arity:    CallableArity = CallableArity.Binary
  ): Bnd =
    protocolImplementation(state, "Num", typeName, symbol, Some(arity))

  private def binding(state: CompilerState, name: String): Bnd =
    state.module.members
      .collectFirst { case value: Bnd if value.name == name => value }
      .getOrElse(fail(s"Expected fixture binding $name"))

  private def selectedId(state: CompilerState, name: String): Option[String] =
    rootReference(binding(state, name).value).resolvedId

  private def typeKey(tpe: Type, state: CompilerState): ProtocolTypeKey =
    ProtocolRegistry
      .typeKey(tpe, state.module.resolvables)
      .fold(error => fail(s"Expected concrete type: $error"), identity)

  private def namedType(name: String, state: CompilerState): ProtocolTypeKey =
    typeKey(typeReference(name, state.module.resolvables), state)

  private def computedType(node: Typeable, state: CompilerState): ProtocolTypeKey =
    typeKey(node.typeSpec.getOrElse(fail(s"Expected computed type on $node")), state)

  private def reference(term: Term): Ref =
    termReference(term).getOrElse(fail(s"Expected selected reference, got ${unwrapped(term)}"))

  private def application(term: Term): App =
    termApplication(term).getOrElse(fail(s"Expected ordinary application, got ${unwrapped(term)}"))

  private def lambda(term: Term): Lambda =
    termLambda(term).getOrElse(fail(s"Expected source lambda, got ${unwrapped(term)}"))

  private def rootReference(term: Term): Ref =
    callRootReference(term).getOrElse(fail(s"Expected reference callee, got ${unwrapped(term)}"))

  private def binaryCall(term: Term): BinaryCall =
    val outer = application(term)
    val inner = application(outer.fn)
    BinaryCall(reference(inner.fn), inner.arg, outer.arg)

  private def intValue(term: Term): Long = unwrapped(term) match
    case literal: LiteralInt => literal.value
    case other => fail(s"Expected Int literal, got $other")

  private def floatValue(term: Term): Float = unwrapped(term) match
    case literal: LiteralFloat => literal.value
    case other => fail(s"Expected Float literal, got $other")

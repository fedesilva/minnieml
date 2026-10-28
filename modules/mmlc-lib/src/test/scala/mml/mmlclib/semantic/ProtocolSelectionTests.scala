package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.{
  callRootReference,
  termApplication,
  termLambda,
  termReference,
  unwrappedTerm as unwrapped
}

class ProtocolSelectionTests extends BaseEffFunSuite:

  test("the maximum Int literal remains valid after concrete type checking"):
    val source = "let result = 2147483647;"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result   = TypeChecker.rewriteModule(initial)
      val value    = binding(result.module, "result")
      val literals = TermTraversal.collect(value.value) { case literal: LiteralInt => literal }

      assertEquals(result.errors, Vector.empty)
      assertEquals(literals.map(_.value), List(2147483647L))
      assertEquals(computedType(value, result.module), namedType("Int", result.module))
    }

  test("the minimum Int literal folds only after the built-in unary minus is checked"):
    val source = "let result = -2147483648;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = application(binding(initial.module, "result").value)
      val magnitude = unwrapped(original.arg) match
        case literal: LiteralInt => literal
        case other => fail(s"Expected the parsed positive magnitude, got $other")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val folded = unwrapped(value.value) match
        case literal: LiteralInt => literal
        case other => fail(s"Expected the checked minimum literal, got $other")

      assertEquals(initial.errors, Vector.empty)
      assertEquals(magnitude.value, 2147483648L)
      assertEquals(result.errors, Vector.empty)
      assertEquals(folded.value, -2147483648L)
      assertEquals(folded.source, original.source)
      assertEquals(computedType(folded, result.module), namedType("Int", result.module))
      assertEquals(TermTraversal.collect(value.value) { case app: App => app }, Nil)
    }

  test("the positive minimum-Int magnitude reports its literal span after selection"):
    val source = "let invalid = 2147483648; let after = 42;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = unwrapped(binding(initial.module, "invalid").value)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(initial.errors, Vector.empty)
      result.visibleErrors match
        case Vector(SemanticError.InvalidExpression(expr, message, _)) =>
          val span = expr.spanOpt.get
          assertEquals(expr.source, original.source)
          assertEquals(source.substring(span.start.index, span.end.index), "2147483648")
          assertEquals(message, "Integer literal is outside the Int32 range")
        case other => fail(s"Expected one literal-range diagnostic, got $other")
      assertEquals(
        computedType(binding(result.module, "after"), result.module),
        namedType("Int", result.module)
      )
    }

  test("unary plus cannot admit the minimum-Int magnitude and reports only the literal span"):
    val source = "let invalid = +2147483648;"
    parseNotFailed(source).map { parsed =>
      val initial   = prepareForTypeChecking(source, parsed)
      val original  = application(binding(initial.module, "invalid").value)
      val magnitude = unwrapped(original.arg)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(initial.errors, Vector.empty)
      result.visibleErrors match
        case Vector(SemanticError.InvalidExpression(expr, message, _)) =>
          val span = expr.spanOpt.get
          assertEquals(expr.source, magnitude.source)
          assertEquals(source.substring(span.start.index, span.end.index), "2147483648")
          assertEquals(message, "Integer literal is outside the Int32 range")
        case other => fail(s"Expected one literal-range diagnostic, got $other")
    }

  test("a custom unary minus with the same Int signature cannot fold the minimum-Int magnitude"):
    val source = """
      op -(value: Int): Int 95 right = value;;
      let invalid = -2147483648;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed, withStandardOperators = false)
      val custom = initial.module.members.collectFirst {
        case member: Bnd if member.source.isFromSource && member.meta.exists { meta =>
              meta.origin == BindingOrigin.Operator && meta.originalName == "-" &&
              meta.arity == CallableArity.Unary
            } =>
          member
      }.get
      val original  = application(binding(initial.module, "invalid").value)
      val magnitude = unwrapped(original.arg)

      val result     = TypeChecker.rewriteModule(initial)
      val value      = binding(result.module, "invalid")
      val literals   = TermTraversal.collect(value.value) { case literal: LiteralInt => literal }
      val references = TermTraversal.collect(value.value) { case ref: Ref => ref }

      assertEquals(initial.errors, Vector.empty)
      assertEquals(reference(original.fn).resolvedId, custom.id)
      assertEquals(literals.map(_.value), List(2147483648L))
      assertEquals(references.map(_.resolvedId), List(custom.id))
      result.visibleErrors match
        case Vector(SemanticError.InvalidExpression(expr, message, _)) =>
          val span = expr.spanOpt.get
          assertEquals(expr.source, magnitude.source)
          assertEquals(source.substring(span.start.index, span.end.index), "2147483648")
          assertEquals(message, "Integer literal is outside the Int32 range")
        case other => fail(s"Expected the custom operator's literal-range diagnostic, got $other")
    }

  test("a named full call selects the member for its argument's concrete type"):
    val source = identityInstances + "let result = identity true;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Identity", "Bool", "identity")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val ref    = rootReference(value.value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.resolvedId, expected.id)
      assertEquals(ref.candidateIds, expected.id.toList)
      assertEquals(computedType(value, result.module), namedType("Bool", result.module))
      assertEquals(computedType(ref, result.module), computedType(expected, initial.module))
    }

  test("a user-defined operator selects the concrete member after grouping"):
    val source = """
      protocol Combine 'T = op <+>(left: 'T, right: 'T): 'T 60 left; ;
      instance Combine Int = op <+>(left, right) = left + right;; ;
      instance Combine Bool = op <+>(left, right) = left or right;; ;
      let result = 1 <+> 2;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Combine", "Int", "<+>")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")

      assertEquals(result.errors, Vector.empty)
      assertEquals(rootReference(value.value).resolvedId, expected.id)
      assertEquals(computedType(value, result.module), namedType("Int", result.module))
    }

  test("an expected result type selects a nullary member"):
    val source = emptyInstances + "let result: Bool = empty ();"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Empty", "Bool", "empty")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")

      assertEquals(result.errors, Vector.empty)
      assertEquals(rootReference(value.value).resolvedId, expected.id)
      assertEquals(computedType(value, result.module), namedType("Bool", result.module))
    }

  test("an expected callable type selects a reference before partial-application lowering"):
    val source = identityInstances + "let selected: Bool -> Bool = identity;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Identity", "Bool", "identity")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "selected")
      val ref    = reference(value.value)
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.resolvedId, expected.id)
      assertEquals(computedType(ref, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(TermTraversal.collect(value.value) { case body: Lambda => body }, Nil)
    }

  test("a grouped call's type ascription selects its nullary member"):
    val source = emptyInstances + "let result = (empty ()): Bool;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Empty", "Bool", "empty")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")

      assertEquals(result.errors, Vector.empty)
      assertEquals(rootReference(value.value).resolvedId, expected.id)
      assertEquals(computedType(value, result.module), namedType("Bool", result.module))
    }

  test("a grouped callable value annotation selects an overloaded reference"):
    val source = identityInstances + "let selected = (identity): Bool -> Bool;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Identity", "Bool", "identity")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "selected")
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(value.value).resolvedId, expected.id)
      assertEquals(computedType(value, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(TermTraversal.collect(value.value) { case body: Lambda => body }, Nil)
    }

  test("a grouped callee annotation selects a nullary member before its Unit argument"):
    val source = emptyInstances + "let result = ((empty): Unit -> Bool) ();"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Empty", "Bool", "empty")
      val original = application(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)
      val call   = application(binding(result.module, "result").value)
      val bool   = namedType("Bool", result.module)
      val unit   = namedType("Unit", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(call.fn).resolvedId, expected.id)
      assertEquals(call.calleeAscriptions, original.calleeAscriptions)
      assertEquals(
        call.calleeAscriptions.map(typeKey(_, result.module)),
        List(ProtocolTypeKey.Function(List(unit), bool))
      )
      assertEquals(computedType(call, result.module), bool)
    }

  test("a grouped partial-application annotation selects its remaining callable type"):
    val source = chooseInstances + "let selected = (choose 0): Bool -> Bool;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Choose", "Bool", "choose")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "selected")
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(application(value.value).fn).resolvedId, expected.id)
      assertEquals(computedType(value, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(TermTraversal.collect(value.value) { case body: Lambda => body }, Nil)
    }

  test("a grouped partial's callable annotation applies at the later argument boundary"):
    val source = chooseInstances + "let result = ((choose 0): Bool -> Bool) true;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Choose", "Bool", "choose")
      val original = application(binding(initial.module, "result").value)

      val result    = TypeChecker.rewriteModule(initial)
      val outer     = application(binding(result.module, "result").value)
      val inner     = application(outer.fn)
      val bool      = namedType("Bool", result.module)
      val remaining = ProtocolTypeKey.Function(List(bool), bool)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(inner.fn).resolvedId, expected.id)
      assertEquals(inner.calleeAscriptions, Nil)
      assertEquals(outer.calleeAscriptions, original.calleeAscriptions)
      assertEquals(outer.calleeAscriptions.map(typeKey(_, result.module)), List(remaining))
      assertEquals(computedType(inner, result.module), remaining)
      assertEquals(computedType(outer, result.module), bool)
    }

  test("nested compatible callable annotations preserve both written source constraints"):
    val source = emptyInstances + "let result = (((empty): Unit -> Bool): Unit -> Bool) ();"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Empty", "Bool", "empty")
      val original = application(binding(initial.module, "result").value)

      val result    = TypeChecker.rewriteModule(initial)
      val call      = application(binding(result.module, "result").value)
      val bool      = namedType("Bool", result.module)
      val unit      = namedType("Unit", result.module)
      val signature = ProtocolTypeKey.Function(List(unit), bool)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(call.fn).resolvedId, expected.id)
      assertEquals(call.calleeAscriptions, original.calleeAscriptions)
      assertEquals(
        call.calleeAscriptions.map(typeKey(_, result.module)),
        List(signature, signature)
      )
      assertEquals(call.calleeAscriptions.map(_.source).distinct.size, 2)
      assertEquals(computedType(call, result.module), bool)
    }

  test("an outer compatible callable annotation cannot hide a conflicting inner annotation"):
    val source = """
      fn truth(): Bool = true;;
      let result = (((truth): Unit -> Int): Unit -> Bool) ();
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = application(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)
      val mismatches = result.visibleErrors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }
      val unit    = namedType("Unit", result.module)
      val integer = namedType("Int", result.module)
      val bool    = namedType("Bool", result.module)

      assertEquals(original.calleeAscriptions.size, 2)
      assertEquals(mismatches.size, 1)
      assertEquals(result.visibleErrors.size, 1)
      assertEquals(
        typeKey(mismatches.head.expected, result.module),
        ProtocolTypeKey.Function(List(unit), integer)
      )
      assertEquals(
        typeKey(mismatches.head.actual, result.module),
        ProtocolTypeKey.Function(List(unit), bool)
      )
    }

  test("a grouped lambda's callable annotation remains distinct from its return annotation"):
    val source = "let result = (({ value: Int -> value; }: Int): Int -> Int) 1;"
    parseNotFailed(source).map { parsed =>
      val initial        = prepareForTypeChecking(source, parsed)
      val original       = application(binding(initial.module, "result").value)
      val originalLambda = lambda(original.fn)

      val result  = TypeChecker.rewriteModule(initial)
      val call    = application(binding(result.module, "result").value)
      val body    = lambda(call.fn)
      val integer = namedType("Int", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(body.typeAsc, originalLambda.typeAsc)
      assertEquals(body.typeAsc.map(typeKey(_, result.module)), Some(integer))
      assertEquals(
        original.calleeAscriptions.map(typeKey(_, result.module)),
        List(ProtocolTypeKey.Function(List(integer), integer))
      )
      assertEquals(body.params.map(_.id), originalLambda.params.map(_.id))
      assertEquals(computedType(call, result.module), integer)
      assertEquals(
        computedType(body, result.module),
        ProtocolTypeKey.Function(List(integer), integer)
      )
    }

  test("a conflicting callable annotation is rejected before direct-lambda lowering"):
    val source = "let result = (({ value: Int -> value; }: Int): Int -> Bool) 1;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = application(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)
      val mismatches = result.visibleErrors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }
      val integer = namedType("Int", result.module)
      val bool    = namedType("Bool", result.module)

      assertEquals(original.calleeAscriptions.size, 1)
      assertEquals(mismatches.size, 1)
      assertEquals(result.visibleErrors.size, 1)
      assertEquals(
        typeKey(mismatches.head.expected, result.module),
        ProtocolTypeKey.Function(List(integer), bool)
      )
      assertEquals(
        typeKey(mismatches.head.actual, result.module),
        ProtocolTypeKey.Function(List(integer), integer)
      )
    }

  test("an annotated full lambda application preserves supplied parameter identities and types"):
    val source = """
      let result = (({ ~left: Int, right: Int -> left + right; }): Int -> Int -> Int) 1 2;
    """
    parseNotFailed(source).map { parsed =>
      val initial        = prepareForTypeChecking(source, parsed)
      val original       = application(application(binding(initial.module, "result").value).fn)
      val originalLambda = lambda(original.fn)
      val originalLeft   = originalLambda.params.head
      val originalRight  = originalLambda.params(1)

      val result    = TypeChecker.rewriteModule(initial)
      val value     = binding(result.module, "result")
      val leftCall  = applicationForParameter(value.value, originalLeft)
      val rightCall = applicationForParameter(value.value, originalRight)
      val left      = lambda(leftCall.fn).params.head
      val right     = lambda(rightCall.fn).params.head
      val integer   = namedType("Int", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(left.id, originalLeft.id)
      assertEquals(right.id, originalRight.id)
      assertEquals(left.consuming, true)
      assertEquals(right.consuming, false)
      assertEquals(computedType(left, result.module), integer)
      assertEquals(computedType(right, result.module), integer)
      assertEquals(
        TermTraversal.collect(leftCall.arg) { case literal: LiteralInt => literal.value },
        List(1L)
      )
      assertEquals(
        TermTraversal.collect(rightCall.arg) { case literal: LiteralInt => literal.value },
        List(2L)
      )
      assertEquals(
        original.calleeAscriptions.map(typeKey(_, result.module)),
        List(ProtocolTypeKey.Function(List(integer, integer), integer))
      )
      assertEquals(computedType(value, result.module), integer)
    }

  test("an annotated partial lambda application preserves supplied and remaining parameters"):
    val source = """
      let selected = (({ left: Int, ~right: Int -> left + right; }): Int -> Int -> Int) 1;
    """
    parseNotFailed(source).map { parsed =>
      val initial        = prepareForTypeChecking(source, parsed)
      val original       = application(binding(initial.module, "selected").value)
      val originalLambda = lambda(original.fn)
      val originalLeft   = originalLambda.params.head
      val originalRight  = originalLambda.params(1)

      val result       = TypeChecker.rewriteModule(initial)
      val value        = binding(result.module, "selected")
      val suppliedCall = applicationForParameter(value.value, originalLeft)
      val supplied     = lambda(suppliedCall.fn).params.head
      val residuals = TermTraversal.collect(value.value) {
        case body: Lambda if body.params.map(_.id) == List(originalRight.id) => body
      }
      val residual  = residuals.head
      val remaining = residual.params.head
      val integer   = namedType("Int", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(residuals.size, 1)
      assertEquals(supplied.id, originalLeft.id)
      assertEquals(remaining.id, originalRight.id)
      assertEquals(supplied.consuming, false)
      assertEquals(remaining.consuming, true)
      assertEquals(computedType(supplied, result.module), integer)
      assertEquals(computedType(remaining, result.module), integer)
      assertEquals(
        TermTraversal.collect(suppliedCall.arg) { case literal: LiteralInt => literal.value },
        List(1L)
      )
      assertEquals(
        computedType(value, result.module),
        ProtocolTypeKey.Function(List(integer), integer)
      )
      assertEquals(computedType(residual, result.module), computedType(value, result.module))
      assertEquals(
        TermTraversal
          .collect(residual.body) {
            case ref: Ref if ref.resolvedId == supplied.id || ref.resolvedId == remaining.id =>
              ref.resolvedId
          }
          .toSet,
        Set(originalLeft.id, originalRight.id)
      )
    }

  test("a later argument selects an overloaded partial through a source group"):
    val source = chooseInstances + "let result = (choose 0) true;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Choose", "Bool", "choose")

      val result = TypeChecker.rewriteModule(initial)
      val outer  = application(binding(result.module, "result").value)
      val inner  = application(outer.fn)
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(inner.fn).resolvedId, expected.id)
      assertEquals(computedType(inner, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(computedType(outer, result.module), bool)
    }

  test("an expected function result reaches a nullary call through a local-let continuation"):
    val source = emptyInstances + """
      fn result(): Bool =
        let ignored = 0;
        empty ();
      ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Empty", "Bool", "empty")

      val result = TypeChecker.rewriteModule(initial)
      val body   = lambda(binding(result.module, "result").value).body
      val refs   = TermTraversal.collect(body) { case ref: Ref if ref.name == "empty" => ref }

      assertEquals(result.errors, Vector.empty)
      assertEquals(refs.map(_.resolvedId), List(expected.id))
      assertEquals(computedType(body, result.module), namedType("Bool", result.module))
    }

  test("an expected application result reaches an immediately applied lambda's body"):
    val source = emptyInstances + "let result: Bool = { ignored: Int -> empty (); } 0;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Empty", "Bool", "empty")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val body   = lambda(application(value.value).fn).body

      assertEquals(result.errors, Vector.empty)
      assertEquals(rootReference(body).resolvedId, expected.id)
      assertEquals(computedType(body, result.module), namedType("Bool", result.module))
      assertEquals(computedType(value, result.module), namedType("Bool", result.module))
    }

  test("the internal expected-tuple-type boundary selects each nullary member independently"):
    val source = emptyInstances + "let result = (empty (), empty ());"
    parseNotFailed(source).map { parsed =>
      val initial      = prepareForTypeChecking(source, parsed)
      val expectedBool = protocolImplementation(initial, "Empty", "Bool", "empty")
      val expectedInt  = protocolImplementation(initial, "Empty", "Int", "empty")
      val original     = binding(initial.module, "result")
      val expectedTuple = TypeTuple(
        SourceOrigin.Synth,
        List(
          typeReference("Bool", initial.module.resolvables),
          typeReference("Int", initial.module.resolvables)
        )
      )
      // Tuple expressions parse, but tuple type syntax is not available in source yet.
      val typedMembers = initial.module.members.map {
        case member: Bnd if member.id == original.id =>
          member.copy(typeAsc = Some(expectedTuple))
        case other => other
      }
      val typedInput = ResolvablesIndexer.rewriteModule(
        initial.withModule(initial.module.copy(members = typedMembers))
      )

      val result = TypeChecker.rewriteModule(typedInput)
      val value  = binding(result.module, "result")
      val tuple = unwrapped(value.value) match
        case tuple: Tuple => tuple
        case other => fail(s"Expected tuple result, got $other")

      assertEquals(result.errors, Vector.empty)
      assertEquals(tuple.elements.size, 2)
      assertEquals(rootReference(tuple.elements.head).resolvedId, expectedBool.id)
      assertEquals(rootReference(tuple.elements.tail.head).resolvedId, expectedInt.id)
      assertEquals(computedType(value, result.module), typeKey(expectedTuple, result.module))
    }

  test("a later argument selects an instance after a shared first parameter"):
    val source = chooseInstances + "let result = choose 0 true;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Choose", "Bool", "choose")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val outer  = application(value.value)
      val inner  = application(outer.fn)
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(rootReference(value.value).resolvedId, expected.id)
      assertEquals(computedType(inner, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(computedType(value, result.module), bool)
    }

  test("an expected remaining callable type selects a partial application"):
    val source = chooseInstances + "let selected: Bool -> Bool = choose 0;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Choose", "Bool", "choose")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "selected")
      val call   = application(value.value)
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(call.fn).resolvedId, expected.id)
      assertEquals(computedType(call, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(TermTraversal.collect(value.value) { case body: Lambda => body }, Nil)
    }

  test("an outer parameter type reaches a nested overloaded nullary call"):
    val source = emptyInstances + """
      fn takeBool(value: Bool): Bool = value;;
      let result = takeBool (empty ());
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Empty", "Bool", "empty")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val outer  = application(value.value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(rootReference(outer.arg).resolvedId, expected.id)
      assertEquals(computedType(outer.arg, result.module), namedType("Bool", result.module))
    }

  test("an expected lambda parameter type reaches an overloaded call in its body"):
    val source = identityInstances + """
      fn useBool(f: Bool -> Bool): Bool = f true;;
      let result = useBool { value -> identity value; };
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Identity", "Bool", "identity")

      val result = TypeChecker.rewriteModule(initial)
      val call   = application(binding(result.module, "result").value)
      val body   = lambda(call.arg)

      assertEquals(result.errors, Vector.empty)
      assertEquals(computedType(body.params.head, result.module), namedType("Bool", result.module))
      assertEquals(rootReference(body.body).resolvedId, expected.id)
      assertEquals(computedType(body.body, result.module), namedType("Bool", result.module))
    }

  test("an expected function argument selects a protocol member reference"):
    val source = identityInstances + """
      fn useBool(f: Bool -> Bool): Bool = f true;;
      let result = useBool identity;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Identity", "Bool", "identity")

      val result = TypeChecker.rewriteModule(initial)
      val call   = application(binding(result.module, "result").value)
      val ref    = reference(call.arg)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.resolvedId, expected.id)
      assertEquals(computedType(ref, result.module), computedType(expected, initial.module))
      assertEquals(TermTraversal.collect(call.arg) { case body: Lambda => body }, Nil)
    }

  test("selection respects the invocation boundary when a member returns a callable"):
    val source = """
      protocol Make 'T = fn make(seed: Int): ('T -> 'T); ;
      instance Make Int = fn make(seed) = { value: Int -> value; };; ;
      instance Make Bool = fn make(seed) = { value: Bool -> value; };; ;
      let result = make 0 true;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Make", "Bool", "make")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val outer  = application(value.value)
      val inner  = application(outer.fn)
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(inner.fn).resolvedId, expected.id)
      assertEquals(computedType(inner, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(computedType(outer, result.module), bool)
      assertEquals(lambda(expected.value).params.size, 1)
    }

  test("a known callable argument selects a function-typed instance before its returned call"):
    val source = """
      type Predicate = Int -> Bool;
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Predicate = fn identity(value) = value;; ;
      fn positive(value: Int): Bool = value > 0;;
      let result = identity positive 1;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Identity", "Predicate", "identity")
      val supplied = binding(initial.module, "positive")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val outer  = application(value.value)
      val inner  = application(outer.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(inner.fn).resolvedId, expected.id)
      assertEquals(reference(inner.arg).resolvedId, supplied.id)
      assertEquals(computedType(inner, result.module), namedType("Predicate", result.module))
      assertEquals(computedType(outer, result.module), namedType("Bool", result.module))
    }

  test("one returned-call argument and an expected scalar result determine a function instance"):
    val source = """
      type Predicate = Int -> Bool;
      protocol Factory 'T = fn create(seed: Int): 'T; ;
      instance Factory Predicate = fn create(seed) = { value: Int -> true; };; ;
      let result: Bool = create 0 1;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Factory", "Predicate", "create")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val outer  = application(value.value)
      val inner  = application(outer.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(inner.fn).resolvedId, expected.id)
      assertEquals(computedType(inner, result.module), namedType("Predicate", result.module))
      assertEquals(computedType(outer, result.module), namedType("Bool", result.module))
      assertEquals(lambda(expected.value).params.size, 1)
    }

  test("unknown returned-call boundaries remain insufficient despite matching argument types"):
    val source = """
      type Flat = Int -> Bool -> Int;
      type Staged = Int -> (Bool -> Int);
      protocol Factory 'T = fn create(seed: Int): 'T; ;
      instance Factory Flat = fn create(seed) = { value: Int, flag: Bool -> 1; };; ;
      instance Factory Staged =
        fn create(seed) = { value: Int -> { flag: Bool -> 1; }; };;
      ;
      let result: Int = create 0 1 true;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)
      val flat     = protocolImplementation(initial, "Factory", "Flat", "create")
      val staged   = protocolImplementation(initial, "Factory", "Staged", "create")

      val result = TypeChecker.rewriteModule(initial)

      assertNotEquals(flat.id, staged.id)
      assertNotEquals(namedType("Flat", result.module), namedType("Staged", result.module))
      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.InsufficientTypeInformation(ref),
                _
              )
            ) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected insufficient callable-boundary evidence, got $other")
    }

  test("a recursive concrete member selects its own implementation by the argument type"):
    val source = """
      protocol CountDown 'T = fn countDown(value: 'T): 'T; ;
      instance CountDown Int =
        fn countDown(value) =
          if value == 0 then 0;
          else countDown (value - 1);
          ;
        ;
      ;
      instance CountDown Bool = fn countDown(value) = value;; ;
      let result = countDown 3;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "CountDown", "Int", "countDown")

      val result = TypeChecker.rewriteModule(initial)
      val member =
        result.module.resolvables.lookup(expected.id.get).collect { case b: Bnd => b }.get
      val recursive = TermTraversal.collect(member.value) {
        case ref: Ref if ref.name == "countDown" => ref
      }

      assertEquals(result.errors, Vector.empty)
      assertEquals(recursive.map(_.resolvedId), List(expected.id))
      assertEquals(rootReference(binding(result.module, "result").value).resolvedId, expected.id)
      assertEquals(computedType(member, result.module), computedType(expected, initial.module))
    }

  test("an unused instance body is checked against its supplied concrete result type"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = true;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      val result = TypeChecker.rewriteModule(initial)
      val mismatches = result.semanticErrors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }

      assertEquals(mismatches.size, 1)
      assertEquals(result.errors.size, 1)
      assertEquals(
        typeKey(mismatches.head.expected, result.module),
        namedType("Int", result.module)
      )
      assertEquals(typeKey(mismatches.head.actual, result.module), namedType("Bool", result.module))
    }

  test("an alias selects the same concrete implementation as its target type"):
    val source = identityInstances + """
      type Count = Int;
      fn use(value: Count): Count = identity value;;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Identity", "Int", "identity")

      val result = TypeChecker.rewriteModule(initial)
      val body   = lambda(binding(result.module, "use").value).body

      assertEquals(result.errors, Vector.empty)
      assertEquals(rootReference(body).resolvedId, expected.id)
      assertEquals(computedType(body, result.module), namedType("Int", result.module))
      assertEquals(namedType("Count", result.module), namedType("Int", result.module))
    }

  test("nominal structs with the same fields select distinct implementations"):
    val source = """
      struct First { value: Int };
      struct Second { value: Int };
      protocol Read 'T = fn readValue(value: 'T): Int; ;
      instance Read First = fn readValue(value) = value.value;; ;
      instance Read Second = fn readValue(value) = value.value;; ;
      let result = readValue (Second 2);
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Read", "Second", "readValue")
      val other    = protocolImplementation(initial, "Read", "First", "readValue")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")

      assertEquals(result.errors, Vector.empty)
      assertNotEquals(expected.id, other.id)
      assertNotEquals(namedType("First", result.module), namedType("Second", result.module))
      assertEquals(rootReference(value.value).resolvedId, expected.id)
      assertEquals(computedType(value, result.module), namedType("Int", result.module))
    }

  test("a known concrete type without an instance reports the missing protocol instance"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      let result = identity true;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)
      val protocol = initial.module.members
        .collectFirst {
          case protocol: Protocol if protocol.name == "Identity" => protocol
        }
        .getOrElse(fail("Expected fixture Identity protocol"))

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.MissingInstance(ref, expected, concreteType),
                _
              )
            ) =>
          assertEquals(ref.source, original.source)
          assertEquals(expected.id, protocol.id)
          assertEquals(typeKey(concreteType, result.module), namedType("Bool", result.module))
        case other => fail(s"Expected one missing-instance diagnostic, got $other")
    }

  test("incompatible supplied arguments receive one selection diagnostic"):
    val source = """
      protocol Same 'T = fn same(left: 'T, right: 'T): 'T; ;
      instance Same Int = fn same(left, right) = left;; ;
      instance Same Bool = fn same(left, right) = left;; ;
      let result = same 1 true;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.IncompatibleArguments(ref),
                _
              )
            ) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected one incompatible-arguments diagnostic, got $other")
    }

  test("a bare overloaded value with no expected type is rejected at its binding"):
    val source = identityInstances + "let undecided = identity;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = reference(binding(initial.module, "undecided").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.InsufficientTypeInformation(ref),
                _
              )
            ) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected one insufficient-information diagnostic, got $other")
    }

  test("a partial application cannot defer instance selection into a later binding"):
    val source = chooseInstances + "let undecided = choose 0; let result = undecided true;"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "undecided").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.InsufficientTypeInformation(ref),
                _
              )
            ) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected the unresolved binding to remain the error, got $other")
    }

  test("a nullary call with no expected result cannot select between concrete instances"):
    val source = emptyInstances + "let undecided = empty ();"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "undecided").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.InsufficientTypeInformation(ref),
                _
              )
            ) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected one insufficient-information diagnostic, got $other")
    }

  test("a failed expected type roots the dependent protocol-selection diagnostic"):
    val source = """
      protocol Empty 'T = fn empty(): 'T; ;
      instance Empty Int = fn empty() = 0;; ;
      let result: Missing = empty ();
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = initial.diagnostics.visibleRecords.head

      val result = TypeChecker.rewriteModule(initial)
      val selection = result.diagnostics.records.filter {
        _.error.isInstanceOf[SemanticError.ProtocolErrorFound]
      }

      assertEquals(initial.visibleErrors.size, 1)
      assert(original.error.isInstanceOf[SemanticError.UndefinedTypeRef])
      assertEquals(original.relation, DiagnosticRelation.Primary)
      assertEquals(result.visibleErrors, initial.visibleErrors)
      assertEquals(selection.size, 1)
      assertEquals(selection.head.relation, DiagnosticRelation.Consequence(Vector(original.id)))
      assertEquals(result.diagnostics.lookup(original.id), Some(original))
      assert(
        result.diagnostics.records.flatMap(_.causes).forall(result.diagnostics.lookup(_).nonEmpty)
      )
    }

  test("an independent wrong argument stays primary when the expected result type has failed"):
    val source = """
      protocol Choose 'T = fn choose(seed: Int, value: 'T): 'T; ;
      instance Choose Int = fn choose(seed, value) = value;; ;
      let result: Missing = choose true 0;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)
      val selection = result.diagnostics.visibleRecords.filter {
        _.error.isInstanceOf[SemanticError.ProtocolErrorFound]
      }

      assertEquals(initial.visibleErrors.size, 1)
      assert(initial.visibleErrors.head.isInstanceOf[SemanticError.UndefinedTypeRef])
      assertEquals(result.visibleErrors.size, 2)
      assert(result.visibleErrors.contains(initial.visibleErrors.head))
      assertEquals(selection.size, 1)
      assertEquals(selection.head.relation, DiagnosticRelation.Primary)
      selection.head.error match
        case SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(ref), _) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected independent incompatible-arguments diagnostic, got $other")
    }

  test("equally applicable members from different protocols remain ambiguous"):
    val source = """
      protocol First 'T = fn identity(value: 'T): 'T; ;
      protocol Second 'T = fn identity(value: 'T): 'T; ;
      instance First Int = fn identity(value) = value;; ;
      instance Second Int = fn identity(value) = value;; ;
      let result = identity 1;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      result.visibleErrors match
        case Vector(SemanticError.ProtocolErrorFound(ProtocolError.AmbiguousSelection(ref), _)) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected one ambiguous-selection diagnostic, got $other")
    }

  test("one applicable signature cannot win while another signature lacks result evidence"):
    val source = """
      protocol First 'T = fn choose(seed: 'T): Int; ;
      instance First Int = fn choose(seed) = seed;; ;
      protocol Second 'T = fn choose(seed: Int): 'T; ;
      instance Second Bool = fn choose(seed) = true;; ;
      let result = choose 1;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(original.candidateIds.size, 2)
      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.InsufficientTypeInformation(ref),
                _
              )
            ) =>
          assertEquals(ref.source, original.source)
          assertEquals(ref.resolvedId, None)
        case other => fail(s"Expected unresolved result evidence to block selection, got $other")
    }

  test("an outer protocol call supplies result evidence to an unresolved inner alternative"):
    val source = """
      protocol First 'T = fn choose(seed: 'T): Int; ;
      instance First Int = fn choose(seed) = seed;; ;
      protocol Second 'T = fn choose(seed: Int): 'T; ;
      instance Second Bool = fn choose(seed) = true;; ;
      protocol Sink 'T = fn sink(value: 'T): 'T; ;
      instance Sink Bool = fn sink(value) = value;; ;
      let result: Bool = sink (choose 1);
    """
    parseNotFailed(source).map { parsed =>
      val initial       = prepareForTypeChecking(source, parsed)
      val expectedInner = protocolImplementation(initial, "Second", "Bool", "choose")
      val expectedOuter = protocolImplementation(initial, "Sink", "Bool", "sink")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val outer  = application(value.value)
      val inner  = application(outer.arg)
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(inner.fn).resolvedId, expectedInner.id)
      assertEquals(reference(outer.fn).resolvedId, expectedOuter.id)
      assertEquals(computedType(inner, result.module), bool)
      assertEquals(computedType(outer, result.module), bool)
      assertEquals(computedType(value, result.module), bool)
    }

  test("result evidence that completes a second applicable signature produces ambiguity"):
    val source = """
      protocol First 'T = fn choose(seed: 'T): Int; ;
      instance First Int = fn choose(seed) = seed;; ;
      protocol Second 'T = fn choose(seed: Int): 'T; ;
      instance Second Int = fn choose(seed) = seed;; ;
      let result: Int = choose 1;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(original.candidateIds.size, 2)
      result.visibleErrors match
        case Vector(SemanticError.ProtocolErrorFound(ProtocolError.AmbiguousSelection(ref), _)) =>
          assertEquals(ref.source, original.source)
          assertEquals(ref.resolvedId, None)
        case other => fail(s"Expected both completed signatures to remain applicable, got $other")
    }

  test("an outer protocol call cannot reuse an inner choice before all alternatives are known"):
    val source = """
      protocol First 'T = fn choose(seed: 'T): Int; ;
      instance First Int = fn choose(seed) = seed;; ;
      protocol Second 'T = fn choose(seed: Int): 'T; ;
      instance Second Int = fn choose(seed) = seed;; ;
      protocol Sink 'T = fn sink(value: 'T): 'T; ;
      instance Sink Int = fn sink(value) = value;; ;
      let result: Int = sink (choose 1);
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val outer    = application(binding(initial.module, "result").value)
      val original = reference(outer.fn)

      val result = TypeChecker.rewriteModule(initial)
      val failed = unwrapped(binding(result.module, "result").value) match
        case invalid: InvalidExpression => application(invalid.originalExpr)
        case other => fail(s"Expected the rejected outer call to retain its syntax, got $other")

      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(ref), _)
            ) =>
          assertEquals(ref.source, original.source)
          assertEquals(ref.resolvedId, None)
        case other =>
          fail(s"Expected the ambiguous argument to prevent outer selection, got $other")
      assertEquals(reference(failed.fn).resolvedId, None)
      assertEquals(rootReference(failed.arg).resolvedId, None)
    }

  test("a fixed nested chain preserves every selected member identity and result type"):
    val source = """
      protocol Step 'T = fn step(value: 'T): 'T; ;
      instance Step Int = fn step(value) = value;; ;
      let result =
        step (step (step (step (step (step (
          step (step (step (step (step (step 1)))))))))));
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Step", "Int", "step")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val refs   = TermTraversal.collect(value.value) { case ref: Ref => ref }
      val calls  = TermTraversal.collect(value.value) { case app: App => app }
      val int    = namedType("Int", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(refs.size, 12)
      assertEquals(refs.map(_.resolvedId).toSet, Set(expected.id))
      assertEquals(calls.size, 12)
      assertEquals(calls.map(computedType(_, result.module)).toSet, Set(int))
      assertEquals(computedType(value, result.module), int)
    }

  test("a failed left operand stays one error without repeated candidate checking"):
    val source = """
      let invalid =
        1.0 + 2 + 3.0 + 4.0 + 5.0 + 6.0 + 7.0 + 8.0 +
        9.0 + 10.0 + 11.0 + 12.0 + 13.0 + 14.0 + 15.0 + 16.0 +
        17.0 + 18.0 + 19.0 + 20.0 + 21.0 + 22.0 + 23.0 + 24.0;
      let after = 40.0 + 2.0;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      // Parsing and preparation are outside the bound; only type checking is measured.
      val started        = System.nanoTime()
      val result         = TypeChecker.rewriteModule(initial)
      val elapsedSeconds = (System.nanoTime() - started).toDouble / 1_000_000_000

      assertEquals(initial.errors, Vector.empty)
      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(ref), _)
            ) =>
          assertEquals(ref.spanOpt.get.start.index, source.indexOf("+"))
        case other => fail(s"Expected only the mixed first pair to fail, got $other")
      assertEquals(result.diagnostics.visibleRecords.head.relation, DiagnosticRelation.Primary)
      assert(
        result.diagnostics.records.flatMap(_.causes).forall(result.diagnostics.lookup(_).nonEmpty)
      )
      assertEquals(
        computedType(binding(result.module, "after"), result.module),
        namedType("Float", result.module)
      )
      assert(elapsedSeconds < 5.0, s"Checking 24 terms took $elapsedSeconds seconds")
    }

  test("a failed right operand stays one error without repeated candidate checking"):
    val source = """
      protocol Combine 'T = op <+>(left: 'T, right: 'T): 'T 60 right; ;
      instance Combine Float = op <+>(left, right) = left + right;; ;
      let invalid =
        1.0 <+> 2.0 <+> 3.0 <+> 4.0 <+> 5.0 <+> 6.0 <+> 7.0 <+> 8.0 <+>
        9.0 <+> 10.0 <+> 11.0 <+> 12.0 <+> 13.0 <+> 14.0 <+> 15.0 <+> 16.0 <+>
        17.0 <+> 18.0 <+> 19.0 <+> 20.0 <+> 21.0 <+> 22.0 <+> 23.0 <+> 24;
      let after = 40.0 <+> 2.0;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)

      // Right associativity supplies the nesting without nested-parser work in the measurement.
      val started        = System.nanoTime()
      val result         = TypeChecker.rewriteModule(initial)
      val elapsedSeconds = (System.nanoTime() - started).toDouble / 1_000_000_000

      assertEquals(initial.errors, Vector.empty)
      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(ref), _)
            ) =>
          assertEquals(ref.spanOpt.get.start.index, source.indexOf("<+> 24"))
        case other => fail(s"Expected only the mixed last pair to fail, got $other")
      assertEquals(result.diagnostics.visibleRecords.head.relation, DiagnosticRelation.Primary)
      assert(
        result.diagnostics.records.flatMap(_.causes).forall(result.diagnostics.lookup(_).nonEmpty)
      )
      assertEquals(
        computedType(binding(result.module, "after"), result.module),
        namedType("Float", result.module)
      )
      assert(elapsedSeconds < 5.0, s"Checking 24 terms took $elapsedSeconds seconds")
    }

  test("an enclosing protocol still supplies context to a nested lambda result"):
    val source = """
      protocol Empty 'T = fn empty(): 'T; ;
      instance Empty Int = fn empty() = 0;; ;
      instance Empty Bool = fn empty() = false;; ;
      protocol Apply 'T = fn apply(f: (Int -> 'T), value: 'T): 'T; ;
      instance Apply Bool = fn apply(f, value) = f 0;; ;
      let result = apply { ignored: Int -> empty (); } true;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Empty", "Bool", "empty")

      val result = TypeChecker.rewriteModule(initial)
      val value  = binding(result.module, "result")
      val calls = TermTraversal.collect(value.value) { case ref: Ref if ref.name == "empty" => ref }

      assertEquals(result.errors, Vector.empty)
      assertEquals(calls.map(_.resolvedId), List(expected.id))
      assertEquals(computedType(value, result.module), namedType("Bool", result.module))
    }

  test("an invalid instance body cannot make an equally applicable member win selection"):
    val source = """
      protocol First 'T = fn identity(value: 'T): 'T; ;
      protocol Second 'T = fn identity(value: 'T): 'T; ;
      instance First Int = fn identity(value) = true;; ;
      instance Second Int = fn identity(value) = value;; ;
      let result = identity 1;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)
      val selections = result.semanticErrors.collect {
        case SemanticError.ProtocolErrorFound(error, _) => error
      }
      val mismatches = result.semanticErrors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }

      selections match
        case Vector(ProtocolError.AmbiguousSelection(ref)) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected ambiguous selection despite the body error, got $other")
      assertEquals(mismatches.size, 1)
      assertEquals(result.visibleErrors.size, 2)
      assertEquals(
        typeKey(mismatches.head.expected, result.module),
        namedType("Int", result.module)
      )
      assertEquals(typeKey(mismatches.head.actual, result.module), namedType("Bool", result.module))
    }

  test("the trial identity lambda supplies no independent type evidence"):
    val source = "let value = { item -> item; };"
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = lambda(binding(initial.module, "value").value)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(initial.errors, Vector.empty)
      assertEquals(binding(result.module, "value").typeSpec, None)
      result.visibleErrors match
        case Vector(
              SemanticError.TypeCheckingError(TypeError.UninferrableLambdaParam(parameter, _))
            ) =>
          assertEquals(parameter.id, original.params.head.id)
        case other => fail(s"Expected the identity lambda to need an expected type, got $other")
    }

  test("rejected candidates cannot leave inferred parameter types in the selected lambda"):
    val source = trialInstances + "let result = trial { value -> value; } 0;"
    parseNotFailed(source).map { parsed =>
      val initial       = prepareForTypeChecking(source, parsed)
      val rejected      = protocolImplementation(initial, "IntTrial", "Int", "trial")
      val expected      = protocolImplementation(initial, "BoolTrial", "Int", "trial")
      val originalCall  = application(binding(initial.module, "result").value)
      val originalInner = application(originalCall.fn)
      val original      = lambda(originalInner.arg)
      val candidates = ProtocolSelection
        .candidates(reference(originalInner.fn), initial.protocols, initial.module.resolvables)
        .fold(error => fail(s"Expected concrete trial candidates: $error"), identity)
      val eligible = ProtocolSelection.matchSignatures(
        candidates,
        List(
          ProtocolSelection.ApplicationEvidence(None, Nil),
          ProtocolSelection.ApplicationEvidence(
            Some(typeReference("Int", initial.module.resolvables)),
            Nil
          )
        ),
        None,
        initial.module.resolvables
      )

      assertEquals(initial.errors, Vector.empty)
      assertEquals(original.params.head.typeAsc, None)
      assertEquals(original.params.head.typeSpec, None)
      assertEquals(eligible.flatMap(_.implementationIds).size, 2)
      assertEquals(
        eligible.flatMap(_.implementationIds),
        rejected.id.toList ++ expected.id.toList
      )

      val result = TypeChecker.rewriteModule(initial)
      val outer  = application(binding(result.module, "result").value)
      val inner  = application(outer.fn)
      val body   = lambda(inner.arg)
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(inner.fn).resolvedId, expected.id)
      assertEquals(body.params.head.id, original.params.head.id)
      assertEquals(reference(body.body).resolvedId, original.params.head.id)
      assertEquals(computedType(body.params.head, result.module), bool)
      assertEquals(computedType(body.body, result.module), bool)
      assertEquals(computedType(body, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(computedType(outer, result.module), bool)
      assertEquals(result.diagnostics.records, Vector.empty)
    }

  test("the losing trial signature rejects the identity lambda's Int return"):
    val source = """
      fn trial(f: (Int -> Bool), tag: Int): Bool = f 1;;
      let result = trial { value -> value; } 0;
    """
    parseNotFailed(source).map { parsed =>
      val initial      = prepareForTypeChecking(source, parsed)
      val originalCall = application(binding(initial.module, "result").value)
      val original     = lambda(application(originalCall.fn).arg)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(initial.errors, Vector.empty)
      result.visibleErrors match

        case Vector(SemanticError.TypeCheckingError(error: TypeError.TypeMismatch)) =>
          error.node match
            case body: Lambda => assertEquals(body.source, original.source)
            case other => fail(s"Expected a lambda return mismatch, got $other")

          assertEquals(typeKey(error.expected, result.module), namedType("Bool", result.module))
          assertEquals(typeKey(error.actual, result.module), namedType("Int", result.module))

        case other => fail(s"Expected one concrete lambda return mismatch, got $other")
    }

  test("the losing concrete candidate cannot accept the identity lambda on its own"):
    val source = """
      protocol IntTrial 'T = fn trial(f: (Int -> Bool), tag: 'T): Bool; ;
      instance IntTrial Int = fn trial(f, tag) = f 1;; ;
      let result = trial { value -> value; } 0;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val original = rootReference(binding(initial.module, "result").value)

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(initial.errors, Vector.empty)
      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(ref), _)
            ) =>
          assertEquals(ref.source, original.source)
        case other => fail(s"Expected the concrete candidate to reject the lambda, got $other")
    }

  test("reversing candidate declarations preserves the selected lambda's inferred type"):
    val source = """
      protocol BoolTrial 'T = fn trial(f: (Bool -> Bool), tag: 'T): Bool; ;
      instance BoolTrial Int = fn trial(f, tag) = f true;; ;
      protocol IntTrial 'T = fn trial(f: (Int -> Bool), tag: 'T): Bool; ;
      instance IntTrial Int = fn trial(f, tag) = f 1;; ;
      let result = trial { value -> value; } 0;
    """
    parseNotFailed(source).map { parsed =>
      val initial       = prepareForTypeChecking(source, parsed)
      val expected      = protocolImplementation(initial, "BoolTrial", "Int", "trial")
      val rejected      = protocolImplementation(initial, "IntTrial", "Int", "trial")
      val originalCall  = application(binding(initial.module, "result").value)
      val originalInner = application(originalCall.fn)
      val original      = lambda(originalInner.arg)
      val candidates = ProtocolSelection
        .candidates(reference(originalInner.fn), initial.protocols, initial.module.resolvables)
        .fold(error => fail(s"Expected concrete trial candidates: $error"), identity)
      val eligible = ProtocolSelection.matchSignatures(
        candidates,
        List(
          ProtocolSelection.ApplicationEvidence(None, Nil),
          ProtocolSelection.ApplicationEvidence(
            Some(typeReference("Int", initial.module.resolvables)),
            Nil
          )
        ),
        None,
        initial.module.resolvables
      )

      assertEquals(initial.errors, Vector.empty)
      assertEquals(original.params.head.typeAsc, None)
      assertEquals(original.params.head.typeSpec, None)
      assertEquals(eligible.flatMap(_.implementationIds).size, 2)
      assertEquals(
        eligible.flatMap(_.implementationIds),
        expected.id.toList ++ rejected.id.toList
      )

      val result = TypeChecker.rewriteModule(initial)
      val outer  = application(binding(result.module, "result").value)
      val inner  = application(outer.fn)
      val body   = lambda(inner.arg)
      val bool   = namedType("Bool", result.module)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(inner.fn).resolvedId, expected.id)
      assertEquals(body.params.head.id, original.params.head.id)
      assertEquals(reference(body.body).resolvedId, original.params.head.id)
      assertEquals(computedType(body.params.head, result.module), bool)
      assertEquals(computedType(body.body, result.module), bool)
      assertEquals(computedType(body, result.module), ProtocolTypeKey.Function(List(bool), bool))
      assertEquals(computedType(outer, result.module), bool)
      assertEquals(result.diagnostics.records, Vector.empty)
    }

  test("an independent source failure inside an argument survives without candidate-error fanout"):
    val source = trialInstances + "let result = trial { value -> missing; } 0;"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      val original = initial.diagnostics.visibleRecords.filter { record =>
        record.error.isInstanceOf[SemanticError.UndefinedRef]
      }

      val result = TypeChecker.rewriteModule(initial)
      val selection = result.diagnostics.records.filter { record =>
        record.error.isInstanceOf[SemanticError.ProtocolErrorFound]
      }

      assertEquals(original.size, 1)
      assertEquals(original.head.relation, DiagnosticRelation.Primary)
      assertEquals(result.visibleErrors, original.map(_.error))
      assertEquals(result.diagnostics.lookup(original.head.id), Some(original.head))
      assertEquals(selection.size, 1)
      assertEquals(
        selection.head.relation,
        DiagnosticRelation.Consequence(Vector(original.head.id))
      )
    }

  test("named protocol inference keeps shadowed bindings separate in either statement order"):
    val source = """
      protocol Combine 'T = fn combine(left: 'T, right: 'T): 'T; ;
      instance Combine Float = fn combine(left, right) = right;; ;

      let before = { value ->
        let result = (let value = 1.0; combine value 2.0);
        let gate = not value;
        result;
      };
      let after = { value ->
        let gate = not value;
        let result = (let value = 1.0; combine value 2.0);
        result;
      };
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val expected = protocolImplementation(initial, "Combine", "Float", "combine")

      val result = TypeChecker.rewriteModule(initial)
      val bool   = namedType("Bool", result.module)
      val float  = namedType("Float", result.module)

      assertEquals(result.errors, Vector.empty)
      List("before", "after").foreach { name =>
        val original = lambda(binding(initial.module, name).value)
        val body     = lambda(binding(result.module, name).value)
        val selected = TermTraversal.collect(body.body) {
          case ref: Ref if ref.resolvedId == expected.id => ref
        }

        assertEquals(body.params.head.id, original.params.head.id)
        assertEquals(computedType(body, result.module), ProtocolTypeKey.Function(List(bool), float))
        assertEquals(selected.size, 1)
      }
    }

  private val identityInstances = """
    protocol Identity 'T = fn identity(value: 'T): 'T; ;
    instance Identity Int = fn identity(value) = value;; ;
    instance Identity Bool = fn identity(value) = value;; ;
  """

  private val chooseInstances = """
    protocol Choose 'T = fn choose(seed: Int, value: 'T): 'T; ;
    instance Choose Int = fn choose(seed, value) = value;; ;
    instance Choose Bool = fn choose(seed, value) = value;; ;
  """

  private val emptyInstances = """
    protocol Empty 'T = fn empty(): 'T; ;
    instance Empty Int = fn empty() = 0;; ;
    instance Empty Bool = fn empty() = false;; ;
  """

  private val trialInstances = """
    protocol IntTrial 'T = fn trial(f: (Int -> Bool), tag: 'T): Bool; ;
    instance IntTrial Int = fn trial(f, tag) = f 1;; ;
    protocol BoolTrial 'T = fn trial(f: (Bool -> Bool), tag: 'T): Bool; ;
    instance BoolTrial Int = fn trial(f, tag) = f true;; ;
  """

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case value: Bnd if value.name == name => value }
      .getOrElse(fail(s"Expected fixture binding $name"))

  private def typeKey(tpe: Type, module: Module): ProtocolTypeKey =
    ProtocolRegistry
      .typeKey(tpe, module.resolvables)
      .fold(error => fail(s"Expected concrete type: $error"), identity)

  private def namedType(name: String, module: Module): ProtocolTypeKey =
    typeKey(typeReference(name, module.resolvables), module)

  private def computedType(node: Typeable, module: Module): ProtocolTypeKey =
    typeKey(node.typeSpec.getOrElse(fail(s"Expected computed type on $node")), module)

  private def reference(term: Term): Ref =
    termReference(term).getOrElse(fail(s"Expected selected reference, got ${unwrapped(term)}"))

  private def application(term: Term): App =
    termApplication(term).getOrElse(fail(s"Expected ordinary application, got ${unwrapped(term)}"))

  private def applicationForParameter(term: Term, parameter: FnParam): App =
    TermTraversal
      .collect(term) { case call: App => call }
      .find { call =>
        call.fn match
          case body: Lambda => body.params.exists(_.id == parameter.id)
          case _ => false
      }
      .getOrElse(fail(s"Expected argument binding for parameter ${parameter.name}"))

  private def lambda(term: Term): Lambda =
    termLambda(term).getOrElse(fail(s"Expected authored lambda, got ${unwrapped(term)}"))

  private def rootReference(term: Term): Ref =
    callRootReference(term).getOrElse(fail(s"Expected reference callee, got ${unwrapped(term)}"))

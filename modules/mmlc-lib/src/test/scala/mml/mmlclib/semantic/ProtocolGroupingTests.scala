package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.{termApplication, termLambda, termReference, unwrappedTerm as unwrapped}

class ProtocolGroupingTests extends BaseEffFunSuite:

  test("coherent binary signatures retain both candidates while grouping to the left"):
    val source = """
      protocol First 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      protocol Second 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      let value = 1 + 2 + 3;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val outer   = binaryCall(binding(result.module, "value").value)
      val inner   = binaryCall(outer.left)

      assertEquals(result.errors, Vector.empty)
      assertEquals(expected.size, 2)
      assertEquals(outer.operator.candidateIds.toSet, expected)
      assertEquals(inner.operator.candidateIds.toSet, expected)
      assertEquals(outer.operator.resolvedId, None)
      assertEquals(inner.operator.resolvedId, None)
      assertEquals(intValue(inner.left), 1L)
      assertEquals(intValue(inner.right), 2L)
      assertEquals(intValue(outer.right), 3L)
    }

  test("reversing protocol declaration order retains the same binary grouping and both candidates"):
    val source = """
      protocol Second 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      protocol First 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      let value = 1 + 2 + 3;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val outer   = binaryCall(binding(result.module, "value").value)
      val inner   = binaryCall(outer.left)

      assertEquals(result.errors, Vector.empty)
      assertEquals(expected.size, 2)
      assertEquals(outer.operator.candidateIds.toSet, expected)
      assertEquals(inner.operator.candidateIds.toSet, expected)
      assertEquals(outer.operator.resolvedId, None)
      assertEquals(inner.operator.resolvedId, None)
      assertEquals(intValue(inner.left), 1L)
      assertEquals(intValue(inner.right), 2L)
      assertEquals(intValue(outer.right), 3L)
    }

  test("signature precedence groups multiplication inside addition without selecting instances"):
    val source = """
      protocol Number 'T = op +(left: 'T, right: 'T): 'T 60 left;
        op *(left: 'T, right: 'T): 'T 80 left; ;
      protocol More 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      let value = 1 + 2 * 3;
    """
    parseNotFailed(source).map { parsed =>
      val initial        = phaseInput(source, parsed)
      val declared       = signatures(initial.module)
      val additions      = declared.filter(_.meta.originalName == "+").flatMap(_.id).toSet
      val multiplication = declared.find(_.meta.originalName == "*").get

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val outer   = binaryCall(binding(result.module, "value").value)
      val inner   = binaryCall(outer.right)

      assertEquals(result.errors, Vector.empty)
      assertEquals(outer.operator.candidateIds.toSet, additions)
      assertEquals(inner.operator.candidateIds, multiplication.id.toList)
      assertEquals(outer.operator.resolvedId, None)
      assertEquals(inner.operator.resolvedId, None)
      assertEquals(intValue(outer.left), 1L)
      assertEquals(intValue(inner.left), 2L)
      assertEquals(intValue(inner.right), 3L)
    }

  test("prefix and binary uses narrow the same symbol to signatures of the matching arity"):
    val source = """
      protocol First 'T = op -(value: 'T): 'T 95 right;
        op -(left: 'T, right: 'T): 'T 60 left; ;
      protocol Second 'T = op -(value: 'T): 'T 95 right;
        op -(left: 'T, right: 'T): 'T 60 left; ;
      let left = 1;
      let right = 2;
      let value = -left - right;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val declared  = signatures(initial.module)
      val unaryIds  = declared.filter(_.meta.arity == CallableArity.Unary).flatMap(_.id).toSet
      val binaryIds = declared.filter(_.meta.arity == CallableArity.Binary).flatMap(_.id).toSet
      val leftId    = binding(initial.module, "left").id
      val rightId   = binding(initial.module, "right").id

      val checked  = OperatorGrouping.checkModule(initial)
      val result   = ExpressionRewriter.rewriteModule(checked)
      val outer    = binaryCall(binding(result.module, "value").value)
      val prefix   = application(outer.left)
      val operator = reference(prefix.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(outer.operator.candidateIds.toSet, binaryIds)
      assertEquals(operator.candidateIds.toSet, unaryIds)
      assertEquals(outer.operator.resolvedId, None)
      assertEquals(operator.resolvedId, None)
      assertEquals(reference(prefix.arg).resolvedId, leftId)
      assertEquals(reference(outer.right).resolvedId, rightId)
    }

  test("a postfix use retains only unary candidates even when a binary signature shares its name"):
    val source = """
      protocol First 'T = op !(value: 'T): 'T 95 left;
        op !(left: 'T, right: 'T): 'T 60 left; ;
      protocol Second 'T = op !(value: 'T): 'T 95 left; ;
      let value = 1 !;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val unaryIds = signatures(initial.module)
        .filter(_.meta.arity == CallableArity.Unary)
        .flatMap(_.id)
        .toSet

      val checked  = OperatorGrouping.checkModule(initial)
      val result   = ExpressionRewriter.rewriteModule(checked)
      val postfix  = application(binding(result.module, "value").value)
      val operator = reference(postfix.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(unaryIds.size, 2)
      assertEquals(operator.candidateIds.toSet, unaryIds)
      assertEquals(operator.resolvedId, None)
      assertEquals(intValue(postfix.arg), 1L)
    }

  test("an infix suffix chooses postfix candidates before multiplying the result"):
    val source = """
      protocol First 'T = op !(value: 'T): 'T 95 left;
        op !(left: 'T, right: 'T): 'T 60 left;
        op *(left: 'T, right: 'T): 'T 80 left; ;
      protocol Second 'T = op !(value: 'T): 'T 95 left;
        op !(left: 'T, right: 'T): 'T 60 left; ;
      let value = 1 ! * 2;
    """
    parseNotFailed(source).map { parsed =>
      val initial        = phaseInput(source, parsed)
      val declared       = signatures(initial.module)
      val postfixIds     = declared.filter(_.meta.arity == CallableArity.Unary).flatMap(_.id).toSet
      val multiplication = declared.find(_.meta.originalName == "*").get

      val checked  = OperatorGrouping.checkModule(initial)
      val result   = ExpressionRewriter.rewriteModule(checked)
      val outer    = binaryCall(binding(result.module, "value").value)
      val postfix  = application(outer.left)
      val operator = reference(postfix.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(outer.operator.candidateIds, multiplication.id.toList)
      assertEquals(operator.candidateIds.toSet, postfixIds)
      assertEquals(outer.operator.resolvedId, None)
      assertEquals(operator.resolvedId, None)
      assertEquals(intValue(postfix.arg), 1L)
      assertEquals(intValue(outer.right), 2L)
    }

  test("a prefix operand after a shared postfix and binary symbol selects binary candidates"):
    val source = """
      protocol First 'T = op !(value: 'T): 'T 95 left;
        op !(left: 'T, right: 'T): 'T 60 left;
        op -(value: 'T): 'T 95 right; ;
      protocol Second 'T = op !(value: 'T): 'T 95 left;
        op !(left: 'T, right: 'T): 'T 60 left;
        op -(value: 'T): 'T 95 right; ;
      let value = 1 ! -2;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val declared  = signatures(initial.module)
      val binaryIds = declared.filter(_.meta.arity == CallableArity.Binary).flatMap(_.id).toSet
      val prefixIds = declared.filter(_.meta.originalName == "-").flatMap(_.id).toSet

      val checked  = OperatorGrouping.checkModule(initial)
      val result   = ExpressionRewriter.rewriteModule(checked)
      val outer    = binaryCall(binding(result.module, "value").value)
      val prefix   = application(outer.right)
      val operator = reference(prefix.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(outer.operator.candidateIds.toSet, binaryIds)
      assertEquals(operator.candidateIds.toSet, prefixIds)
      assertEquals(outer.operator.resolvedId, None)
      assertEquals(operator.resolvedId, None)
      assertEquals(intValue(outer.left), 1L)
      assertEquals(intValue(prefix.arg), 2L)
    }

  test("unused binary signatures with different precedence conflict without any instance"):
    val source = """
      protocol First 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      protocol Second 'T = op +(left: 'T, right: 'T): 'T 80 left; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val declared = signatures(initial.module)

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.OperatorGroupingConflict(first, other),
                _
              )
            ) =>
          assertEquals(first.id, declared.head.id)
          assertEquals(other.id, declared(1).id)
        case other => fail(s"Expected one precedence conflict, got $other")
      assertEquals(
        result.module.members.collect { case instance: ProtocolInstance => instance },
        Nil
      )
    }

  test("unused binary signatures with different associativity conflict without any instance"):
    val source = """
      protocol First 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      protocol Second 'T = op +(left: 'T, right: 'T): 'T 60 right; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val declared = signatures(initial.module)

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.OperatorGroupingConflict(first, other),
                _
              )
            ) =>
          assertEquals(first.id, declared.head.id)
          assertEquals(other.id, declared(1).id)
        case other => fail(s"Expected one associativity conflict, got $other")
    }

  test("unary signatures cannot disagree about prefix and postfix placement"):
    val source = """
      protocol First 'T = op !(value: 'T): 'T 95 right; ;
      protocol Second 'T = op !(value: 'T): 'T 95 left; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val declared = signatures(initial.module)

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.OperatorGroupingConflict(first, other),
                _
              )
            ) =>
          assertEquals(first.id, declared.head.id)
          assertEquals(other.id, declared(1).id)
        case other => fail(s"Expected one unary placement conflict, got $other")
    }

  test("an ordinary free operator must agree with a protocol signature's grouping attributes"):
    val source = """
      op +(left: Int, right: Int): Int 60 left = left;;
      protocol Number 'T = op +(left: 'T, right: 'T): 'T 70 left; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val ordinary  = initial.module.members.collectFirst { case value: Bnd => value }.get
      val signature = signatures(initial.module).head

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.OperatorGroupingConflict(first, other),
                _
              )
            ) =>
          assertEquals(first.id, ordinary.id)
          assertEquals(other.id, signature.id)
        case other => fail(s"Expected one free/protocol operator conflict, got $other")
    }

  test("coherent free and protocol operators both survive grouping as candidates"):
    val source = """
      op +(left: Int, right: Int): Int 60 left = left;;
      protocol Number 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      let value = 1 + 2;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val ordinary = initial.module.members.collectFirst {
        case value: Bnd if value.meta.exists(_.origin == BindingOrigin.Operator) => value
      }.get
      val signature = signatures(initial.module).head

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val call    = binaryCall(binding(result.module, "value").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(call.operator.candidateIds.toSet, Set(ordinary.id.get, signature.id.get))
      assertEquals(call.operator.resolvedId, None)
      assertEquals(intValue(call.left), 1L)
      assertEquals(intValue(call.right), 2L)
    }

  test("a bare overloaded function remains a reference with both signature candidates"):
    val source = namedSignatures + "let value = combine;"
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val value   = binding(result.module, "value").value
      val ref     = reference(value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.resolvedId, None)
      assertEquals(TermTraversal.collect(value) { case lambda: Lambda => lambda }, Nil)
    }

  test("a grouped overloaded function keeps candidates without an invented lambda"):
    val source = namedSignatures + "let value = (combine);"
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val value   = binding(result.module, "value").value
      val ref     = reference(value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.resolvedId, None)
      assertEquals(TermTraversal.collect(value) { case lambda: Lambda => lambda }, Nil)
    }

  test("an overloaded function passed as an argument keeps candidates without eta expansion"):
    val source = namedSignatures + """
      fn keep(f: Int -> Int -> Int): Int = 0;;
      let value = keep combine;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet
      val keepId   = binding(initial.module, "keep").id

      val checked  = OperatorGrouping.checkModule(initial)
      val result   = ExpressionRewriter.rewriteModule(checked)
      val value    = binding(result.module, "value").value
      val call     = application(value)
      val argument = reference(call.arg)

      assertEquals(result.errors, Vector.empty)
      assertEquals(reference(call.fn).resolvedId, keepId)
      assertEquals(argument.candidateIds.toSet, expected)
      assertEquals(argument.resolvedId, None)
      assertEquals(TermTraversal.collect(value) { case lambda: Lambda => lambda }, Nil)
    }

  test("a partial overloaded function application retains candidates for later type selection"):
    val source = namedSignatures + "let value = combine 1;"
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val value   = binding(result.module, "value").value
      val call    = application(value)
      val ref     = reference(call.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.resolvedId, None)
      assertEquals(intValue(call.arg), 1L)
      assertEquals(TermTraversal.collect(value) { case lambda: Lambda => lambda }, Nil)
    }

  test("a grouped overloaded binary operator remains a callable reference"):
    val source = """
      protocol First 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      protocol Second 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      let value = (+);
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val value   = binding(result.module, "value").value
      val ref     = reference(value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.resolvedId, None)
      assertEquals(TermTraversal.collect(value) { case lambda: Lambda => lambda }, Nil)
    }

  test("a bare overloaded prefix operator remains a callable value without demanding an operand"):
    val source = """
      protocol First 'T = op !(value: 'T): 'T 95 right; ;
      protocol Second 'T = op !(value: 'T): 'T 95 right; ;
      let value = !;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val value   = binding(result.module, "value").value
      val ref     = reference(value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.resolvedId, None)
      assertEquals(TermTraversal.collect(value) { case lambda: Lambda => lambda }, Nil)
    }

  test("a grouped overloaded prefix operator retains candidates without demanding an operand"):
    val source = """
      protocol First 'T = op !(value: 'T): 'T 95 right; ;
      protocol Second 'T = op !(value: 'T): 'T 95 right; ;
      let value = (!);
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val value   = binding(result.module, "value").value
      val ref     = reference(value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.resolvedId, None)
      assertEquals(TermTraversal.collect(value) { case lambda: Lambda => lambda }, Nil)
    }

  test("a grouped overloaded binary operator accepts one argument without choosing a signature"):
    val source = """
      protocol First 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      protocol Second 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      let value = (+) 1;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val expected = signatures(initial.module).flatMap(_.id).toSet

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val value   = binding(result.module, "value").value
      val call    = application(value)
      val ref     = reference(call.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.resolvedId, None)
      assertEquals(intValue(call.arg), 1L)
      assertEquals(TermTraversal.collect(value) { case lambda: Lambda => lambda }, Nil)
    }

  test("a function parameter shadows protocol signatures before application grouping"):
    val source = namedSignatures + "fn use(combine: Int -> Int): Int = combine 1;;"
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val original  = lambda(binding(initial.module, "use"))
      val parameter = original.params.head

      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val body    = lambda(binding(result.module, "use")).body
      val call    = application(body)
      val ref     = reference(call.fn)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds, parameter.id.toList)
      assertEquals(ref.resolvedId, parameter.id)
      assertEquals(intValue(call.arg), 1L)
      assertEquals(TermTraversal.collect(body) { case nested: Lambda => nested }, Nil)
    }

  test("a used conflicting operator has no grouping in either declaration order: left first"):
    val source = """
      protocol First 'T = op <+>(left: 'T, right: 'T): 'T 60 left; ;
      protocol Second 'T = op <+>(left: 'T, right: 'T): 'T 60 right; ;
      let value = 1 <+> 2 <+> 3;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val use = binding(initial.module, "value").value.terms
        .collectFirst {
          case ref: Ref if ref.name == "<+>" => ref
        }
        .getOrElse(fail("Expected the operator in the source expression"))
      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)

      assertEquals(checked.visibleErrors.size, 1)
      assertEquals(OperatorGrouping.binary(use, initial.module.resolvables), None)
      assertEquals(
        TermTraversal.collect(binding(result.module, "value").value) { case app: App =>
          app
        },
        Nil
      )
    }

  test("a used conflicting operator has no grouping in either declaration order: right first"):
    val source = """
      protocol Second 'T = op <+>(left: 'T, right: 'T): 'T 60 right; ;
      protocol First 'T = op <+>(left: 'T, right: 'T): 'T 60 left; ;
      let value = 1 <+> 2 <+> 3;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val use = binding(initial.module, "value").value.terms
        .collectFirst {
          case ref: Ref if ref.name == "<+>" => ref
        }
        .getOrElse(fail("Expected the operator in the source expression"))
      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)

      assertEquals(checked.visibleErrors.size, 1)
      assertEquals(OperatorGrouping.binary(use, initial.module.resolvables), None)
      assertEquals(
        TermTraversal.collect(binding(result.module, "value").value) { case app: App =>
          app
        },
        Nil
      )
    }

  test("a source signature cannot change the precedence of injected Num addition"):
    val source = """
      protocol Add 'T = op +(left: 'T, right: 'T): 'T 80 left; ;
      let value = 1 + 2;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, injectStandardOperators(parsed))
      val checked = OperatorGrouping.checkModule(initial)
      val use = binding(initial.module, "value").value.terms
        .collectFirst {
          case ref: Ref if ref.name == "+" => ref
        }
        .getOrElse(fail("Expected the addition reference"))

      assertEquals(
        checked.visibleErrors.map(_.message),
        Vector(
          "Operator '+' has conflicting precedence or associativity for the same arity"
        )
      )
      assertEquals(OperatorGrouping.binary(use, initial.module.resolvables), None)
    }

  test("a source signature cannot change the associativity of a free standard operator"):
    val source = """
      protocol Remainder 'T = op %(left: 'T, right: 'T): 'T 80 right; ;
      let value = 5 % 2;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, injectStandardOperators(parsed))
      val checked = OperatorGrouping.checkModule(initial)
      val use = binding(initial.module, "value").value.terms
        .collectFirst {
          case ref: Ref if ref.name == "%" => ref
        }
        .getOrElse(fail("Expected the remainder reference"))

      assertEquals(
        checked.visibleErrors.map(_.message),
        Vector(
          "Operator '%' has conflicting precedence or associativity for the same arity"
        )
      )
      assertEquals(OperatorGrouping.binary(use, initial.module.resolvables), None)
    }

  test("a coherent source signature preserves Num addition as an unresolved grouping candidate"):
    val source = """
      protocol Add 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      let value = 1 + 2;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, injectStandardOperators(parsed))
      val expected = signatures(initial.module)
        .filter(signature =>
          signature.meta.originalName == "+" &&
            signature.meta.arity == CallableArity.Binary
        )
        .flatMap(_.id)
        .toSet
      val checked = OperatorGrouping.checkModule(initial)
      val result  = ExpressionRewriter.rewriteModule(checked)
      val call    = binaryCall(binding(result.module, "value").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(expected.size, 2)
      assertEquals(call.operator.candidateIds.toSet, expected)
      assertEquals(call.operator.resolvedId, None)
      assertEquals(intValue(call.left), 1L)
      assertEquals(intValue(call.right), 2L)
    }

  private val namedSignatures = """
    protocol First 'T = fn combine(left: 'T, right: 'T): 'T; ;
    protocol Second 'T = fn combine(left: 'T, right: 'T): 'T; ;
  """

  private def phaseInput(source: String, parsed: Module): CompilerState =
    val initial = CompilerState.empty(
      injectBasicTypes(parsed),
      SourceInfo(source),
      CompilerConfig.default
    )
    val checked  = DuplicateNameChecker.rewriteModule(initial)
    val assigned = IdAssigner.rewriteModule(checked)
    val typed    = TypeResolver.rewriteModule(assigned)
    val lowered  = ProtocolChecker.rewriteModule(typed)
    val resolved = RefResolver.rewriteModule(lowered)
    resolved.withModule(ResolvablesIndexer.refresh(resolved.module))

  private def signatures(module: Module): List[ProtocolSignature] =
    module.members.collect { case protocol: Protocol => protocol.signatures }.flatten

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case value: Bnd if value.name == name => value }
      .getOrElse(fail(s"Expected fixture binding $name"))

  private def application(term: Term): App =
    termApplication(term).getOrElse(fail(s"Expected ordinary application, got ${unwrapped(term)}"))

  private def reference(term: Term): Ref =
    termReference(term).getOrElse(fail(s"Expected retained reference, got ${unwrapped(term)}"))

  private def intValue(term: Term): Long = unwrapped(term) match
    case literal: LiteralInt => literal.value
    case other => fail(s"Expected integer operand, got $other")

  private def lambda(value: Bnd): Lambda =
    termLambda(value.value).getOrElse(
      fail(s"Expected authored lambda, got ${unwrapped(value.value)}")
    )

  private case class BinaryCall(operator: Ref, left: Expr, right: Expr)

  private def binaryCall(term: Term): BinaryCall =
    val second = application(term)
    val first  = application(second.fn)
    BinaryCall(reference(first.fn), first.arg, second.arg)

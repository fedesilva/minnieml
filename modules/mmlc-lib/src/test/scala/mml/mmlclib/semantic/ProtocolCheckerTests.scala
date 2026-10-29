package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.lsp.{AstLookup, DiagnosticSeverity, Diagnostics, Range, SemanticTokens}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.ErrorPrinter

class ProtocolCheckerTests extends BaseEffFunSuite:

  test("an operator inherits concrete types and grouping from its protocol signature"):
    val source = """
      protocol Combine 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      instance Combine Int = op +(left, right) = left;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val protocol  = protocols(initial.module).head
      val signature = protocol.signatures.head

      val result   = ProtocolChecker.rewriteModule(initial)
      val instance = instances(result.module).head
      val index    = result.module.resolvables
      val binding = result.protocols
        .memberBinding(instance.id.get, signature.id.get, index)
        .toOption
        .getOrElse(fail("Expected registered operator implementation"))
      val body     = lambda(binding)
      val intKey   = ProtocolRegistry.typeKey(typeReference("Int", index), index)
      val callable = functionType(binding.typeSpec, index)

      assertEquals(result.errors, Vector.empty)
      assertEquals(instance.protocol.resolvedId, protocol.id)
      assertEquals(
        result.protocols.instance(protocol.id.get, instance.concreteType, index),
        Right(instance.id.get)
      )
      assertEquals(binding.meta.map(_.originalName), Some("+"))
      assertEquals(binding.meta.map(_.precedence), Some(60))
      assertEquals(binding.meta.flatMap(_.associativity), Some(Associativity.Left))
      assertEquals(body.params.map(_.consuming), List(false, false))
      assertEquals(
        body.params.map(param => ProtocolRegistry.typeKey(param.typeAsc.get, index)),
        List(intKey, intKey)
      )
      assertEquals(
        body.params.map(param => ProtocolRegistry.typeKey(param.typeSpec.get, index)),
        List(intKey, intKey)
      )
      assertEquals(ProtocolRegistry.typeKey(body.typeAsc.get, index), intKey)
      assertEquals(
        callable.paramTypes.toList.map(ProtocolRegistry.typeKey(_, index)),
        List(intKey, intKey)
      )
      assertEquals(ProtocolRegistry.typeKey(callable.returnType, index), intKey)
      assertEquals(body.typeSpec, binding.typeSpec)
    }

  test("lowering retains binding and parameter identities, sources, and one executable body"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial          = phaseInput(source, parsed)
      val signature        = protocols(initial.module).head.signatures.head
      val originalInstance = instances(initial.module).head
      val originalBinding  = originalInstance.members.head.binding
      val originalLambda   = lambda(originalBinding)

      val result   = ProtocolChecker.rewriteModule(initial)
      val instance = instances(result.module).head
      val index    = result.module.resolvables
      val binding = result.protocols
        .memberBinding(instance.id.get, signature.id.get, index)
        .toOption
        .getOrElse(fail("Expected registered identity implementation"))
      val body = lambda(binding)

      assertEquals(result.errors, Vector.empty)
      assertEquals(instance.members, Nil)
      assertEquals(instance.memberIds, List(originalBinding.id.get))
      assertEquals(instance.id, originalInstance.id)
      assertEquals(instance.source, originalInstance.source)
      assertEquals(binding.id, originalBinding.id)
      assertEquals(binding.source, originalBinding.source)
      assertEquals(body.source, originalLambda.source)
      assertEquals(body.body, originalLambda.body)
      assertEquals(body.params.map(_.id), originalLambda.params.map(_.id))
      assertEquals(body.params.map(_.source), originalLambda.params.map(_.source))
      assertEquals(binding.id.flatMap(index.lookup), Some(binding))
      assertEquals(body.params.head.id.flatMap(index.lookup), Some(body.params.head))
      assertEquals(result.module.members.collect { case b: Bnd => b }, List(binding))
      assertEquals(
        ResolvablesIndexer.definitions(result.module).count(_.id == originalBinding.id),
        1
      )
    }

  test("lowered member names and parameters retain their written editor locations"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val binding = instances(initial.module).head.members.head.binding
      val param   = lambda(binding).params.head
      val nameAt  = binding.nameNode.spanOpt.getOrElse(fail("Expected member name span")).start
      val paramAt = param.nameNode.spanOpt.getOrElse(fail("Expected parameter name span")).start

      val result = ProtocolChecker.rewriteModule(initial)

      assertEquals(result.errors, Vector.empty)
      assertEquals(
        AstLookup.findAt(result.module, nameAt.line, nameAt.col).flatMap(_.name),
        Some("identity")
      )
      assertEquals(
        AstLookup.findAt(result.module, paramAt.line, paramAt.col).flatMap(_.name),
        Some("value")
      )
      assertEquals(
        AstLookup.findDefinitionAt(result.module, paramAt.line, paramAt.col),
        param.spanOpt.toList
      )
    }

  List(
    ("implicit FFI", "@metal:ffi[mem=static]", Some("abs")),
    ("explicit FFI", """@metal:ffi[name="host_abs", mem=static]""", Some("host_abs")),
    ("unnamed template", """@metal:tpl["add i32 %operand, 0", mem=static]""", None),
    (
      "named template",
      """@metal:tpl["add i32 %operand, 0", name="template_metadata", mem=static]""",
      Some("template_metadata")
    )
  ).foreach { (label, implementation, expectedSymbol) =>
    test(s"lowering preserves $label symbol semantics and declaration identity"):
      val source = s"""
        protocol Absolute 'T = fn abs(value: 'T): 'T; ;
        instance Absolute Int = fn abs(value) = $implementation;; ;
      """
      parseNotFailed(source).map { parsed =>
        val initial         = phaseInput(source, parsed)
        val originalBinding = instances(initial.module).head.members.head.binding
        val originalLambda  = lambda(originalBinding)
        val originalNative  = nativeImplementation(originalBinding)

        val result  = ProtocolChecker.rewriteModule(initial)
        val binding = protocolImplementation(result, "Absolute", "Int", "abs")
        val body    = lambda(binding)
        val native  = nativeImplementation(binding)
        val again   = ProtocolChecker.rewriteModule(result)

        assertEquals(result.errors, Vector.empty)
        assertEquals(binding.id, originalBinding.id)
        assertEquals(body.params.map(_.id), originalLambda.params.map(_.id))
        assertEquals(binding.id.flatMap(result.module.resolvables.lookup), Some(binding))
        assertEquals(native.nativeSymbol, expectedSymbol)
        assertEquals(native.copy(nativeSymbol = originalNative.nativeSymbol), originalNative)
        assertEquals(again.errors, Vector.empty)
        assertEquals(again.module, result.module)
        assertEquals(again.protocols, result.protocols)
      }
  }

  test("explicit alias types and repeated operator attributes match the signature"):
    val source = """
      type Count = Int;
      protocol Combine 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      instance Combine Int = op +(left: Count, right: Int): Count 60 left = left;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val protocol  = protocols(initial.module).head
      val signature = protocol.signatures.head

      val result   = ProtocolChecker.rewriteModule(initial)
      val instance = instances(result.module).head
      val index    = result.module.resolvables
      val binding = result.protocols
        .memberBinding(instance.id.get, signature.id.get, index)
        .toOption
        .getOrElse(fail("Expected registered operator implementation"))
      val callable = functionType(binding.typeSpec, index)
      val intKey   = ProtocolRegistry.typeKey(typeReference("Int", index), index)

      assertEquals(result.errors, Vector.empty)
      assertEquals(
        callable.paramTypes.toList.map(ProtocolRegistry.typeKey(_, index)),
        List(intKey, intKey)
      )
      assertEquals(ProtocolRegistry.typeKey(callable.returnType, index), intKey)
      assertEquals(binding.meta.map(_.precedence), Some(60))
      assertEquals(binding.meta.flatMap(_.associativity), Some(Associativity.Left))
      assertEquals(
        result.protocols.instance(protocol.id.get, typeReference("Count", index), index),
        Right(instance.id.get)
      )
    }

  test("explicit alias annotations retain their written types and source spans after lowering"):
    val source = """
      type Count = Int;
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value: Count): Count = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial         = phaseInput(source, parsed)
      val signature       = protocols(initial.module).head.signatures.head
      val originalBinding = instances(initial.module).head.members.head.binding
      val originalLambda  = lambda(originalBinding)

      val result   = ProtocolChecker.rewriteModule(initial)
      val instance = instances(result.module).head
      val binding = result.protocols
        .memberBinding(instance.id.get, signature.id.get, result.module.resolvables)
        .toOption
        .getOrElse(fail("Expected registered identity implementation"))
      val body = lambda(binding)

      assertEquals(result.errors, Vector.empty)
      assertEquals(body.params.head.typeAsc, originalLambda.params.head.typeAsc)
      assertEquals(body.typeAsc, originalLambda.typeAsc)
      assertEquals(binding.typeAsc, originalBinding.typeAsc)
      assertEquals(
        body.params.head.typeAsc.flatMap(_.spanOpt),
        originalLambda.params.head.typeAsc.flatMap(_.spanOpt)
      )
      assertEquals(body.typeAsc.flatMap(_.spanOpt), originalLambda.typeAsc.flatMap(_.spanOpt))
      assert(body.params.head.typeAsc.exists(_.source.isFromSource))
      assert(body.typeAsc.exists(_.source.isFromSource))
    }

  test("inherited annotations are derived and do not add semantic source tokens"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val signature = protocols(initial.module).head.signatures.head
      val tokens    = SemanticTokens.compute(initial.module)

      val result   = ProtocolChecker.rewriteModule(initial)
      val instance = instances(result.module).head
      val binding = result.protocols
        .memberBinding(instance.id.get, signature.id.get, result.module.resolvables)
        .toOption
        .getOrElse(fail("Expected registered identity implementation"))
      val body        = lambda(binding)
      val annotations = List(body.params.head.typeAsc, body.typeAsc, binding.typeAsc).flatten
      val derivedSpans = annotations.map(_.source).collect { case SourceOrigin.Derived(span) =>
        span
      }

      assertEquals(result.errors, Vector.empty)
      assertEquals(annotations.size, 3)
      assertEquals(derivedSpans.size, 3)
      assertEquals(SemanticTokens.compute(result.module).data.toList, tokens.data.toList)
    }

  test("checking a lowered instance again retains its registry and one executable body"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)

      val once  = ProtocolChecker.rewriteModule(initial)
      val twice = ProtocolChecker.rewriteModule(once)
      val first = instances(once.module).head
      val again = instances(twice.module).head

      assertEquals(once.errors, Vector.empty)
      assertEquals(twice.errors, Vector.empty)
      assertEquals(twice.module, once.module)
      assertEquals(twice.protocols, once.protocols)
      assertEquals(again.memberIds, first.memberIds)
      assertEquals(again.members, Nil)
      assertEquals(twice.module.members.collect { case b: Bnd => b.id.get }, first.memberIds)
      assertEquals(first.memberIds.size, 1)
    }

  test("an explicit parameter type must match the substituted signature"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value: Bool) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val param   = lambda(instances(initial.module).head.members.head.binding).params.head

      val result = ProtocolChecker.rewriteModule(initial)
      val index  = result.module.resolvables

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.SignatureTypeMismatch(node, expected, actual),
                _
              )
            ) =>
          assertEquals(node, param)
          assertEquals(
            ProtocolRegistry.typeKey(expected, index),
            ProtocolRegistry.typeKey(typeReference("Int", index), index)
          )
          assertEquals(
            ProtocolRegistry.typeKey(actual, index),
            ProtocolRegistry.typeKey(typeReference("Bool", index), index)
          )
        case other => fail(s"Expected one parameter type mismatch, got $other")
    }

  test("an explicit result type must match the substituted signature"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value): Bool = true;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)

      val result = ProtocolChecker.rewriteModule(initial)
      val index  = result.module.resolvables

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.SignatureTypeMismatch(_, expected, actual),
                _
              )
            ) =>
          assertEquals(
            ProtocolRegistry.typeKey(expected, index),
            ProtocolRegistry.typeKey(typeReference("Int", index), index)
          )
          assertEquals(
            ProtocolRegistry.typeKey(actual, index),
            ProtocolRegistry.typeKey(typeReference("Bool", index), index)
          )
        case other => fail(s"Expected one result type mismatch, got $other")
    }

  test("nominal types with the same native representation have distinct instances"):
    val source = """
      type Metres = @metal:repr[t=i64];
      type Seconds = @metal:repr[t=i64];
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Metres = fn identity(value) = value;; ;
      instance Identity Seconds = fn identity(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val protocol = protocols(initial.module).head

      val result  = ProtocolChecker.rewriteModule(initial)
      val index   = result.module.resolvables
      val entries = instances(result.module)
      val metres = result.protocols.instance(
        protocol.id.get,
        typeReference("Metres", index),
        index
      )
      val seconds = result.protocols.instance(
        protocol.id.get,
        typeReference("Seconds", index),
        index
      )

      assertEquals(result.errors, Vector.empty)
      assertEquals(entries.size, 2)
      assertEquals(metres, Right(entries.head.id.get))
      assertEquals(seconds, Right(entries(1).id.get))
      assertNotEquals(metres, seconds)
      assertEquals(entries.flatMap(_.memberIds).distinct.size, 2)
    }

  test("an alias cannot supply a second instance of the same protocol and concrete type"):
    val source = """
      type Count = Int;
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      instance Identity Count = fn identity(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val original = instances(initial.module)

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.DuplicateInstance(instance, firstId),
                _
              )
            ) =>
          assertEquals(instance.id, original(1).id)
          assertEquals(instance.source, original(1).source)
          assertEquals(Some(firstId), original.head.id)
        case other => fail(s"Expected one duplicate instance, got $other")
    }

  test("a missing required member identifies its protocol signature"):
    val source = """
      protocol Pair 'T = fn first(value: 'T): 'T; fn second(value: 'T): 'T; ;
      instance Pair Int = fn first(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val protocol = protocols(initial.module).head
      val original = instances(initial.module).head

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.MissingMember(instance, signature),
                _
              )
            ) =>
          assertEquals(instance.id, original.id)
          assertEquals(signature.id, protocol.signatures(1).id)
        case other => fail(s"Expected one missing member, got $other")
    }

  test("an extra member identifies the binding outside the protocol's signatures"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int =
        fn identity(value) = value;;
        fn extra(value) = value;;
      ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val protocol = protocols(initial.module).head
      val extra    = instances(initial.module).head.members(1).binding

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.UnexpectedMember(binding, actualProtocol),
                _
              )
            ) =>
          assertEquals(binding.id, extra.id)
          assertEquals(binding.source, extra.source)
          assertEquals(actualProtocol.id, protocol.id)
        case other => fail(s"Expected one unexpected member, got $other")
    }

  test("an instance cannot implement the same member twice"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int =
        fn identity(value) = value;;
        fn identity(value) = value;;
      ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val original = instances(initial.module).head.members.map(_.binding)

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.DuplicateMember(binding, first),
                _
              )
            ) =>
          assertEquals(binding.source, original(1).source)
          assertEquals(first.source, original.head.source)
        case other => fail(s"Expected one duplicate member, got $other")
    }

  test("duplicate instance parameter names receive the ordinary duplicate-name diagnostic"):
    val source = """
      protocol Combine 'T = fn combine(left: 'T, right: 'T): 'T; ;
      instance Combine Int = fn combine(value, value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val original = lambda(instances(parsed).head.members.head.binding).params
      val initial  = phaseInput(source, parsed)

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(SemanticError.DuplicateName(_, duplicates, _)) =>
          assertEquals(
            duplicates.collect { case param: FnParam => param.source },
            original.map(_.source)
          )
          assertEquals(duplicates.size, 2)
          assertEquals(result.errors, initial.errors)
        case other => fail(s"Expected one duplicate parameter diagnostic, got $other")
    }

  test("duplicate local bindings in an instance receive the ordinary duplicate-name diagnostic"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int =
        fn identity(value) = let repeated = value; let repeated = value; repeated;;
      ;
    """
    parseNotFailed(source).map { parsed =>
      val body = lambda(instances(parsed).head.members.head.binding).body
      val original = TermTraversal
        .collect(body) { case nested: Lambda =>
          nested.params.filter(_.name == "repeated")
        }
        .flatten
      val initial = phaseInput(source, parsed)

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(SemanticError.DuplicateName(_, duplicates, _)) =>
          assertEquals(
            duplicates.collect { case param: FnParam => param.source },
            original.map(_.source)
          )
          assertEquals(duplicates.size, 2)
          assertEquals(result.errors, initial.errors)
        case other => fail(s"Expected one duplicate local binding diagnostic, got $other")
    }

  test("a named member must retain the signature's parameter count"):
    val source = """
      protocol Combine 'T = fn combine(left: 'T, right: 'T): 'T; ;
      instance Combine Int = fn combine(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val signature = protocols(initial.module).head.signatures.head
      val original  = instances(initial.module).head.members.head.binding

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.MemberShapeMismatch(binding, expected),
                _
              )
            ) =>
          assertEquals(binding.id, original.id)
          assertEquals(expected.id, signature.id)
          assertEquals(lambda(binding).params.size, 1)
          assertEquals(expected.params.size, 2)
        case other => fail(s"Expected one member shape mismatch, got $other")
    }

  test("an operator cannot implement a named function signature"):
    val source = """
      protocol Combine 'T = fn combine(left: 'T, right: 'T): 'T; ;
      instance Combine Int = op combine(left, right) = left;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val signature = protocols(initial.module).head.signatures.head
      val original  = instances(initial.module).head.members.head.binding

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.MemberShapeMismatch(binding, expected),
                _
              )
            ) =>
          assertEquals(binding.id, original.id)
          assertEquals(expected.id, signature.id)
          assertEquals(instances(initial.module).head.members.head.origin, BindingOrigin.Operator)
          assertEquals(binding.meta, None)
          assertEquals(expected.meta.origin, BindingOrigin.Function)
        case other => fail(s"Expected one member shape mismatch, got $other")
    }

  test("omitting a consuming marker cannot inherit ownership from the signature"):
    val source = """
      protocol Take 'T = fn take(~value: 'T): 'T; ;
      instance Take Int = fn take(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val param   = lambda(instances(initial.module).head.members.head.binding).params.head

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.OwnershipMismatch(actual, expectedConsuming),
                _
              )
            ) =>
          assertEquals(actual.id, param.id)
          assertEquals(actual.consuming, false)
          assertEquals(expectedConsuming, true)
        case other => fail(s"Expected one ownership mismatch, got $other")
    }

  test("a consuming implementation cannot replace a borrowing signature"):
    val source = """
      protocol View 'T = fn view(value: 'T): 'T; ;
      instance View Int = fn view(~value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val param   = lambda(instances(initial.module).head.members.head.binding).params.head

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.OwnershipMismatch(actual, expectedConsuming),
                _
              )
            ) =>
          assertEquals(actual.id, param.id)
          assertEquals(actual.consuming, true)
          assertEquals(expectedConsuming, false)
        case other => fail(s"Expected one ownership mismatch, got $other")
    }

  test("matching consuming markers remain on the lowered ordinary parameter"):
    val source = """
      protocol Take 'T = fn take(~value: 'T): 'T; ;
      instance Take Int = fn take(~value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val signature = protocols(initial.module).head.signatures.head
      val original  = lambda(instances(initial.module).head.members.head.binding).params.head

      val result   = ProtocolChecker.rewriteModule(initial)
      val instance = instances(result.module).head
      val binding = result.protocols
        .memberBinding(instance.id.get, signature.id.get, result.module.resolvables)
        .toOption
        .getOrElse(fail("Expected registered consuming implementation"))
      val param = lambda(binding).params.head

      assertEquals(result.errors, Vector.empty)
      assertEquals(param.consuming, true)
      assertEquals(param.id, original.id)
      assertEquals(param.source, original.source)
    }

  test("an explicit operator precedence must match the signature"):
    val source = """
      protocol Combine 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      instance Combine Int = op +(left, right) 70 = left;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val original = instances(initial.module).head.members.head.binding

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.PrecedenceMismatch(binding, expected, actual),
                _
              )
            ) =>
          assertEquals(binding.id, original.id)
          assertEquals(expected, 60)
          assertEquals(actual, 70)
        case other => fail(s"Expected one precedence mismatch, got $other")
    }

  test("an explicitly written default precedence still has to match the signature"):
    val source = """
      protocol Combine 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      instance Combine Int = op +(left, right) 50 = left;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val member  = instances(initial.module).head.members.head
      assertEquals(member.writtenPrecedence, Some(50))
      assertEquals(member.binding.meta, None)

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.PrecedenceMismatch(binding, expected, actual),
                _
              )
            ) =>
          assertEquals(binding.id, member.binding.id)
          assertEquals(expected, 60)
          assertEquals(actual, 50)
        case other => fail(s"Expected one precedence mismatch, got $other")
    }

  test("an explicit operator associativity must match the signature"):
    val source = """
      protocol Combine 'T = op +(left: 'T, right: 'T): 'T 60 left; ;
      instance Combine Int = op +(left, right) right = left;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val original = instances(initial.module).head.members.head.binding

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.AssociativityMismatch(binding, expected, actual),
                _
              )
            ) =>
          assertEquals(binding.id, original.id)
          assertEquals(expected, Associativity.Left)
          assertEquals(actual, Associativity.Right)
        case other => fail(s"Expected one associativity mismatch, got $other")
    }

  test("an unknown protocol reports the instance's written reference"):
    val source = "instance Unknown Int = fn identity(value) = value;; ;"
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val original = instances(initial.module).head.protocol

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(SemanticError.ProtocolErrorFound(ProtocolError.UnknownProtocol(ref), _)) =>
          assertEquals(ref, original)
          assertEquals(ref.resolvedId, None)
        case other => fail(s"Expected one unknown protocol, got $other")
    }

  test("different protocols may share a signature name and the same concrete type"):
    val source = """
      protocol First 'T = fn value(input: 'T): 'T; ;
      protocol Second 'T = fn value(input: 'T): 'T; ;
      instance First Int = fn value(input) = input;; ;
      instance Second Int = fn value(input) = input;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial      = phaseInput(source, parsed)
      val declarations = protocols(initial.module)

      val result  = ProtocolChecker.rewriteModule(initial)
      val index   = result.module.resolvables
      val entries = instances(result.module)
      val first = result.protocols
        .memberBinding(entries.head.id.get, declarations.head.signatures.head.id.get, index)
        .toOption
        .getOrElse(fail("Expected First implementation"))
      val second = result.protocols
        .memberBinding(entries(1).id.get, declarations(1).signatures.head.id.get, index)
        .toOption
        .getOrElse(fail("Expected Second implementation"))

      assertEquals(result.errors, Vector.empty)
      assertNotEquals(declarations.head.signatures.head.id, declarations(1).signatures.head.id)
      assertNotEquals(first.id, second.id)
      assertEquals(first.meta.map(_.originalName), Some("value"))
      assertEquals(second.meta.map(_.originalName), Some("value"))
      assertEquals(
        result.protocols.instance(declarations.head.id.get, typeReference("Int", index), index),
        Right(entries.head.id.get)
      )
      assertEquals(
        result.protocols.instance(declarations(1).id.get, typeReference("Int", index), index),
        Right(entries(1).id.get)
      )
      assertEquals(result.module.members.collect { case b: Bnd => b.id }, List(first.id, second.id))
    }

  test("an unknown protocol reaches CLI and LSP with the written protocol name's source span"):
    val source = """
      let before = 1;
      instance Missing Int = fn identity(value) = value;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = phaseInput(source, parsed)
      val ref     = instances(initial.module).head.protocol
      val span    = ref.spanOpt.getOrElse(fail("Expected the written protocol name's span"))

      val result      = ProtocolChecker.rewriteModule(initial)
      val diagnostics = Diagnostics.fromCompilerState(result)
      val rendered = ErrorPrinter.prettyPrintSemanticErrors(
        result.semanticErrors.toList,
        Some(result.sourceInfo)
      )

      assertEquals(result.errors.size, 1)
      assertEquals(diagnostics.map(_.range), List(Range.fromSrcSpan(span)))
      assertEquals(diagnostics.map(_.message), List("Unknown protocol 'Missing'"))
      assertEquals(diagnostics.map(_.severity), List(DiagnosticSeverity.Error))
      assertEquals(source.substring(span.start.index, span.end.index), "Missing")
      assert(rendered.contains("Unknown protocol 'Missing'"), rendered)
      assert(rendered.contains(ErrorPrinter.formatLocation(span)), rendered)
      assert(rendered.contains(s"${Console.RED}${Console.BOLD}Missing${Console.RESET}"), rendered)
    }

  test("duplicate protocol declarations retain their existing duplicate-name diagnostic"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
    """
    parseNotFailed(source).map { parsed =>
      val original = protocols(parsed)
      val initial  = phaseInput(source, parsed)

      val result = ProtocolChecker.rewriteModule(initial)

      assertEquals(result.errors, initial.errors)
      result.errors match
        case Vector(SemanticError.DuplicateName(_, duplicates, _)) =>
          assertEquals(
            duplicates.collect { case protocol: Protocol => protocol.source },
            original.map(_.source)
          )
          assertEquals(protocols(result.module).size, 1)
        case other => fail(s"Expected one duplicate protocol name, got $other")
    }

  test("a protocol cannot repeat a named signature"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; fn identity(value: 'T): 'T; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = phaseInput(source, parsed)
      val original = protocols(initial.module).head.signatures

      val result = ProtocolChecker.rewriteModule(initial)

      result.errors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.DuplicateSignature(signature, first),
                _
              )
            ) =>
          assertEquals(signature.source, original(1).source)
          assertEquals(first.source, original.head.source)
        case other => fail(s"Expected one duplicate signature, got $other")
    }

  test("unary and binary operators with the same name retain separate signature identities"):
    val source = """
      protocol Minus 'T = op -(value: 'T): 'T 95 right;
        op -(left: 'T, right: 'T): 'T 60 left; ;
      instance Minus Int = op -(value) = value;; op -(left, right) = left;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial    = phaseInput(source, parsed)
      val signatures = protocols(initial.module).head.signatures

      val result   = ProtocolChecker.rewriteModule(initial)
      val index    = result.module.resolvables
      val instance = instances(result.module).head
      val unary = result.protocols
        .memberBinding(instance.id.get, signatures.head.id.get, index)
        .toOption
        .getOrElse(fail("Expected unary implementation"))
      val binary = result.protocols
        .memberBinding(instance.id.get, signatures(1).id.get, index)
        .toOption
        .getOrElse(fail("Expected binary implementation"))

      assertEquals(result.errors, Vector.empty)
      assertNotEquals(unary.id, binary.id)
      assertEquals(lambda(unary).params.size, 1)
      assertEquals(lambda(binary).params.size, 2)
      assertEquals(unary.meta.map(_.precedence), Some(95))
      assertEquals(binary.meta.map(_.precedence), Some(60))
      assertEquals(unary.meta.flatMap(_.associativity), Some(Associativity.Right))
      assertEquals(binary.meta.flatMap(_.associativity), Some(Associativity.Left))
    }

  test("substitution reaches function types nested in parameters and results"):
    val source = """
      protocol Transform 'T = fn transform(f: ('T -> 'T)): ('T -> 'T); ;
      instance Transform Int = fn transform(f) = f;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val signature = protocols(initial.module).head.signatures.head

      val result   = ProtocolChecker.rewriteModule(initial)
      val index    = result.module.resolvables
      val instance = instances(result.module).head
      val binding = result.protocols
        .memberBinding(instance.id.get, signature.id.get, index)
        .toOption
        .getOrElse(fail("Expected registered transform implementation"))
      val body     = lambda(binding)
      val intKey   = ProtocolRegistry.typeKey(typeReference("Int", index), index)
      val callback = functionType(body.params.head.typeAsc, index)
      val returned = functionType(body.typeAsc, index)

      assertEquals(result.errors, Vector.empty)
      assertEquals(
        callback.paramTypes.toList.map(ProtocolRegistry.typeKey(_, index)),
        List(intKey)
      )
      assertEquals(ProtocolRegistry.typeKey(callback.returnType, index), intKey)
      assertEquals(
        returned.paramTypes.toList.map(ProtocolRegistry.typeKey(_, index)),
        List(intKey)
      )
      assertEquals(ProtocolRegistry.typeKey(returned.returnType, index), intKey)
      assertEquals(body.typeSpec, binding.typeSpec)
    }

  test("a nullary member gets the ordinary Unit callable input without an invented parameter"):
    val source = """
      protocol Empty 'T = fn empty(): 'T; ;
      instance Empty Int = fn empty() = 0;; ;
    """
    parseNotFailed(source).map { parsed =>
      val initial   = phaseInput(source, parsed)
      val signature = protocols(initial.module).head.signatures.head

      val result   = ProtocolChecker.rewriteModule(initial)
      val index    = result.module.resolvables
      val instance = instances(result.module).head
      val binding = result.protocols
        .memberBinding(instance.id.get, signature.id.get, index)
        .toOption
        .getOrElse(fail("Expected registered nullary implementation"))
      val body     = lambda(binding)
      val callable = functionType(binding.typeSpec, index)

      assertEquals(result.errors, Vector.empty)
      assertEquals(body.params, Nil)
      assertEquals(binding.meta.map(_.arity), Some(CallableArity.Nullary))
      assertEquals(
        callable.paramTypes.toList.map(ProtocolRegistry.typeKey(_, index)),
        List(Right(ProtocolTypeKey.Unit))
      )
      assertEquals(
        ProtocolRegistry.typeKey(callable.returnType, index),
        ProtocolRegistry.typeKey(typeReference("Int", index), index)
      )
      assertEquals(body.typeSpec, binding.typeSpec)
    }

  test("a rejected Bool instance leaves a valid Int instance usable through the full pipeline"):
    val source = """
      protocol Pair 'T =
        fn first(value: 'T): 'T;
        fn second(value: 'T): 'T;
      ;
      instance Pair Bool = fn first(value) = value;; ;
      instance Pair Int =
        fn first(value) = value;;
        fn second(value) = value;;
      ;
      let result = first 42;
    """
    semVisibleState(source).map { result =>
      result.errors match
        case List(SemanticError.ProtocolErrorFound(error: ProtocolError.MissingMember, _)) =>
          assertEquals(error.message, "Instance of protocol 'Pair' is missing member 'second'")
          val span = error.source.spanOpt.get
          assertEquals(
            source.substring(span.start.index, span.end.index),
            "instance Pair Bool = fn first(value) = value;; ;"
          )
        case other => fail(s"Expected only the missing Bool member, got $other")

      val index = result.module.resolvables
      assertEquals(
        binding(result.module, "result").typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Int", index), index))
      )
    }

  test("a use of the rejected concrete instance carries its declaration failure"):
    val source = """
      protocol Pair 'T = fn first(value: 'T): 'T; fn second(value: 'T): 'T; ;
      instance Pair Bool = fn first(value) = value;; ;
      let result = first true;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      val root = initial.diagnostics.visibleRecords match
        case Vector(record) => record.id
        case other => fail(s"Expected one missing-member root, got $other")

      val result = TypeChecker.rewriteModule(initial)

      assertEquals(result.visibleErrors, initial.visibleErrors)
      val consequences = result.diagnostics.records.filter { record =>
        record.error match
          case SemanticError.ProtocolErrorFound(_: ProtocolError.MissingInstance, _) => true
          case _ => false
      }
      assertEquals(consequences.size, 1)
      assertEquals(consequences.head.relation, DiagnosticRelation.Consequence(Vector(root)))
    }

  test("an incomplete Num Bool instance does not break Int or Float arithmetic"):
    val source = """
      instance Num Bool = op +(left, right) = left or right;; ;
      let integer = 40 + 2;
      let floating = 4.0 + 0.2;
    """
    semVisibleState(source).map { result =>
      val missing = result.errors.map {
        case SemanticError.ProtocolErrorFound(ProtocolError.MissingMember(_, signature), _) =>
          signature.meta.originalName
        case other => fail(s"Expected only missing Bool members, got $other")
      }
      assertEquals(missing, List("-", "*", "/", "==", "!=", "<", ">", "<=", ">=", "-"))

      val index = result.module.resolvables
      assertEquals(
        binding(result.module, "integer").typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Int", index), index))
      )
      assertEquals(
        binding(result.module, "floating").typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Float", index), index))
      )
    }

  test("an alias cannot replace an already rejected instance"):
    val source = """
      type Count = Int;
      protocol Pair 'T = fn first(value: 'T): 'T; fn second(value: 'T): 'T; ;
      instance Pair Int = fn first(value) = value;; ;
      instance Pair Count =
        fn first(value) = value;;
        fn second(value) = value;;
      ;
    """
    semVisibleState(source).map { result =>
      result.errors match
        case List(
              SemanticError.ProtocolErrorFound(_: ProtocolError.MissingMember, _),
              SemanticError.ProtocolErrorFound(ProtocolError.DuplicateInstance(instance, first), _)
            ) =>
          val entries = instances(result.module).filter(_.protocol.nameNode.value == "Pair")
          assertEquals(Some(first), entries.head.id)
          assertEquals(instance.id, entries(1).id)
          val span = instance.source.spanOpt.get
          assert(
            source.substring(span.start.index, span.end.index).startsWith("instance Pair Count")
          )
        case other => fail(s"Expected missing member and duplicate alias instance, got $other")
    }

  test("an unused rejected protocol reports only its duplicate signature"):
    val source = """
      protocol Identity 'T =
        fn identity(value: 'T): 'T;
        fn identity(other: 'T): 'T;
      ;
      let after = 42;
    """
    semVisibleState(source).map { result =>
      result.errors match
        case List(SemanticError.ProtocolErrorFound(error: ProtocolError.DuplicateSignature, _)) =>
          assertEquals(error.message, "Duplicate protocol signature 'identity'")
          val span = error.source.spanOpt.get
          assertEquals(
            source.substring(span.start.index, span.end.index),
            "fn identity(other: 'T): 'T;"
          )
        case other => fail(s"Expected only the duplicate signature, got $other")
    }

  test("uses and aliases of a rejected signature retain the original declaration error"):
    val source = """
      protocol Identity 'T =
        fn identity(value: 'T): 'T;
        fn identity(other: 'T): 'T;
      ;
      let broken = identity 1;
      let alias = broken;
      let dependent = alias + 1;
      let after = 40 + 2;
    """
    semVisibleState(source).map { result =>
      result.errors match
        case List(SemanticError.ProtocolErrorFound(error: ProtocolError.DuplicateSignature, _)) =>
          assertEquals(error.message, "Duplicate protocol signature 'identity'")
        case other => fail(s"Expected only the duplicate signature, got $other")

      val index = result.module.resolvables
      assertEquals(
        binding(result.module, "after").typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Int", index), index))
      )
    }

  test("a valid protocol sharing a rejected signature name remains usable"):
    val source = """
      protocol Broken 'T = fn identity(value: 'T): 'T; fn identity(other: 'T): 'T; ;
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      let result = identity 42;
    """
    semVisibleState(source).map { result =>
      result.errors match
        case List(SemanticError.ProtocolErrorFound(_: ProtocolError.DuplicateSignature, _)) => ()
        case other => fail(s"Expected only the rejected protocol's signature error, got $other")

      val index = result.module.resolvables
      assertEquals(
        binding(result.module, "result").typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Int", index), index))
      )
    }

  test("a rejected operator use retains its declaration error through grouping"):
    val source = """
      protocol Combine 'T =
        op <&>(left: 'T, right: 'T): 'T 60 left;
        op <&>(first: 'T, second: 'T): 'T 60 left;
      ;
      let broken = 1 <&> 2;
      let after = 40 + 2;
    """
    semVisibleState(source).map { result =>
      result.errors match
        case List(SemanticError.ProtocolErrorFound(error: ProtocolError.DuplicateSignature, _)) =>
          assertEquals(error.message, "Duplicate protocol signature '<&>'")
        case other => fail(s"Expected only the duplicate operator signature, got $other")

      val index = result.module.resolvables
      assertEquals(
        binding(result.module, "after").typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Int", index), index))
      )
    }

  test("source Num Int cannot duplicate the injected instance"):
    val source = """
      instance Num Int = op +(left, right) = left;; ;
      let result = 40 + 2;
    """
    semVisibleState(source).map { result =>
      result.errors match
        case List(SemanticError.ProtocolErrorFound(error: ProtocolError.DuplicateInstance, _)) =>
          assertEquals(error.message, "Duplicate instance of protocol 'Num' for Int")
          val span = error.source.spanOpt.get
          assertEquals(
            source.substring(span.start.index, span.end.index),
            "instance Num Int = op +(left, right) = left;; ;"
          )
        case other => fail(s"Expected one duplicate Num instance, got $other")

      val index = result.module.resolvables
      assertEquals(
        binding(result.module, "result").typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Int", index), index))
      )
    }

  test("source Num cannot replace the injected protocol"):
    val source = """
      protocol Num 'T = fn number(value: 'T): 'T; ;
      let result = 40 + 2;
    """
    semVisibleState(source).map { result =>
      result.errors match
        case List(SemanticError.DuplicateName("Num", duplicates, _)) =>
          val declarations = duplicates.collect { case protocol: Protocol => protocol }
          assertEquals(declarations.size, 2)
          assertEquals(declarations.count(_.source.spanOpt.nonEmpty), 1)
        case other => fail(s"Expected one duplicate Num declaration, got $other")

      val index = result.module.resolvables
      assertEquals(
        binding(result.module, "result").typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Int", index), index))
      )
    }

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case binding: Bnd if binding.name == name => binding }
      .getOrElse(fail(s"Expected binding '$name'"))

  private def phaseInput(source: String, parsed: Module): CompilerState =
    val initial = CompilerState.empty(
      injectBasicTypes(parsed),
      SourceInfo(source),
      CompilerConfig.default
    )
    val checked  = DuplicateNameChecker.rewriteModule(initial)
    val assigned = IdAssigner.rewriteModule(checked)
    TypeResolver.rewriteModule(assigned)

  private def protocols(module: Module): List[Protocol] =
    module.members.collect { case protocol: Protocol => protocol }

  private def instances(module: Module): List[ProtocolInstance] =
    module.members.collect { case instance: ProtocolInstance => instance }

  private def lambda(binding: Bnd): Lambda = binding.value.terms match
    case (body: Lambda) :: Nil => body
    case other => fail(s"Expected one ordinary lambda, got $other")

  private def nativeImplementation(binding: Bnd): NativeImpl = lambda(binding).body.terms match
    case (native: NativeImpl) :: Nil => native
    case other => fail(s"Expected one native implementation, got $other")

  private def functionType(tpe: Option[Type], index: ResolvablesIndex): TypeFn =
    tpe.flatMap(TypeUtils.canonical(_, index)) match
      case Some(function: TypeFn) => function
      case other => fail(s"Expected a concrete callable type, got $other")

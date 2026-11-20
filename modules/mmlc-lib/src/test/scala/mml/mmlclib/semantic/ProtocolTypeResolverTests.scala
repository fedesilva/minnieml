package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.lsp.{DiagnosticSeverity, Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.ErrorPrinter

class ProtocolTypeResolverTests extends BaseEffFunSuite:

  private def phaseInput(code: String, parsed: Module): CompilerState =
    IdAssigner.rewriteModule(
      CompilerState.empty(injectBasicTypes(parsed), SourceInfo(code), CompilerConfig.default)
    )

  private def onlyProtocol(module: Module): Protocol =
    val protocols = module.members.collect { case protocol: Protocol => protocol }
    assertEquals(protocols.size, 1)
    protocols.head

  private def onlyInstance(module: Module): ProtocolInstance =
    val instances = module.members.collect { case instance: ProtocolInstance => instance }
    assertEquals(instances.size, 1)
    instances.head

  private def constrainedProtocol(module: Module): Protocol =
    val protocols = module.members.collect {
      case protocol: Protocol if protocol.constraints.nonEmpty => protocol
    }
    assertEquals(protocols.size, 1)
    protocols.head

  private def onlyLambda(binding: Bnd): Lambda = binding.value.terms match
    case (lambda: Lambda) :: Nil => lambda
    case other => fail(s"Expected one lambda, got $other")

  private def ascription(value: Typeable): Type =
    value.typeAsc.getOrElse(fail(s"Expected a type ascription, got $value"))

  private def resolvedTypeId(tpe: Type, module: Module): String = tpe match
    case ref: TypeRef =>
      val id = ref.resolvedId.getOrElse(fail(s"Unresolved type: $ref"))
      assert(module.resolvables.lookupType(id).nonEmpty, s"Missing type identity: $id")
      id

    case other => fail(s"Expected a nominal type reference, got $other")

  private def groupedFunction(tpe: Type): TypeFn = tpe match
    case TypeGroup(_, (function: TypeFn) :: Nil) => function
    case other => fail(s"Expected a grouped function type, got $other")

  private def undefinedType(tpe: Type): UndefinedTypeError = tpe match
    case error: UndefinedTypeError => error
    case other => fail(s"Expected an undefined type, got $other")

  test("protocol signatures resolve nested concrete references and preserve bound variables"):
    val code = """
      protocol Apply 'T =
        fn apply(~value: 'T, f: ('T -> Result)): Result;
      ;
      type Result = Int;
    """

    parseNotFailed(code).map { parsed =>
      val initial           = phaseInput(code, parsed)
      val original          = onlyProtocol(initial.module)
      val originalSignature = original.signatures.head
      val originalFunction  = groupedFunction(ascription(originalSignature.params(1)))
      val resolved          = TypeResolver.rewriteModule(initial)
      val protocol          = onlyProtocol(resolved.module)
      val signature         = protocol.signatures.head
      val function          = groupedFunction(ascription(signature.params(1)))
      val alias = resolved.module.members
        .collectFirst {
          case value: TypeAlias if value.name == "Result" => value
        }
        .getOrElse(fail("Expected the Result alias"))

      assertEquals(resolved.errors, Vector.empty)
      assert(protocol.typeParameter.resolvedId.nonEmpty)
      assertEquals(protocol.typeParameter.copy(resolvedId = None), original.typeParameter)
      val occurrences = List(ascription(signature.params.head), function.paramTypes.head)
      val originals =
        List(ascription(originalSignature.params.head), originalFunction.paramTypes.head)
      occurrences.zip(originals).foreach { (resolved, original) =>
        resolved match
          case variable: TypeVariable =>
            assertEquals(variable.resolvedId, protocol.typeParameter.resolvedId)
            assertEquals(variable.copy(resolvedId = None), original)
          case other => fail(s"Expected resolved protocol variable, got $other")
      }
      assertEquals(resolvedTypeId(function.returnType, resolved.module), alias.id.get)
      assertEquals(resolvedTypeId(signature.returnType, resolved.module), alias.id.get)
      assertEquals(protocol.id, original.id)
      assertEquals(protocol.source, original.source)
      assertEquals(signature.id, originalSignature.id)
      assertEquals(signature.source, originalSignature.source)
      assertEquals(signature.meta, originalSignature.meta)
      assertEquals(signature.params.map(_.id), originalSignature.params.map(_.id))
      assertEquals(signature.params.map(_.source), originalSignature.params.map(_.source))
      assertEquals(signature.params.map(_.consuming), List(true, false))
      assertEquals(resolved.module.resolvables.lookup(protocol.id.get), Some(protocol))
      assertEquals(resolved.module.resolvables.lookup(signature.id.get), Some(signature))
      assertEquals(
        resolved.module.resolvables.lookup(signature.params(1).id.get),
        Some(signature.params(1))
      )
    }

  test("prerequisites resolve later protocols using the declaring protocol's parameter identity"):
    val code = """
      protocol Render 'T where 'T: Describe & Display & Copy =
        fn render(value: 'T): String;
      ;
      protocol Describe 'T = fn describe(value: 'T): String; ;
      protocol Display 'T = fn display(value: 'T): String; ;
      protocol Copy 'T = fn copy(value: 'T): 'T; ;
    """
    parseNotFailed(code).map { parsed =>
      val initial  = phaseInput(code, parsed)
      val original = constrainedProtocol(initial.module)
      val resolved = TypeResolver.rewriteModule(initial)
      val protocol = constrainedProtocol(resolved.module)
      val prerequisites = resolved.module.members.collect {
        case declaration: Protocol if declaration.id != protocol.id => declaration
      }

      assertEquals(resolved.errors, Vector.empty)
      val binder = protocol.typeParameter.resolvedId
      assert(binder.nonEmpty)
      assertEquals(protocol.constraints.map(_.variable.resolvedId), List(binder))
      val signatureVariable = ascription(protocol.signatures.head.params.head) match
        case variable: TypeVariable => variable
        case other => fail(s"Expected the protocol parameter, got $other")
      assertEquals(signatureVariable.resolvedId, binder)
      assert(prerequisites.forall(_.typeParameter.resolvedId != binder))
      val references = protocol.constraints.flatMap(_.constraints)
      assertEquals(references.map(_.resolvedId), prerequisites.map(_.id))
      protocol.constraints.zip(original.constraints).foreach { (entry, written) =>
        assertEquals(entry.source, written.source)
        assertEquals(entry.variable.copy(resolvedId = None), written.variable)
        assertEquals(entry.constraints.map(_.copy(resolvedId = None)), written.constraints)
      }
      assertEquals(resolved.module.resolvables.lookup(protocol.id.get), Some(protocol))

      val repeated = TypeResolver.rewriteModule(resolved)
      assertEquals(repeated.errors, Vector.empty)
      assertEquals(constrainedProtocol(repeated.module), protocol)
      assertEquals(repeated.bindingIds, resolved.bindingIds)
    }

  test("invalid prerequisite variables and protocols report independent written failures"):
    val code = """
      protocol Render 'T where 'U: Missing, 'T: Describe = fn render(value: 'T): String; ;
      protocol Describe 'T = fn describe(value: 'T): String; ;
    """
    parseNotFailed(code).map { parsed =>
      val resolved = TypeResolver.rewriteModule(phaseInput(code, parsed))
      val protocol = constrainedProtocol(resolved.module)
      val invalid  = protocol.constraints.head
      val valid    = protocol.constraints(1)
      assertEquals(invalid.variable.resolvedId, None)
      assertEquals(invalid.constraints.head.resolvedId, None)
      assertEquals(valid.variable.resolvedId, protocol.typeParameter.resolvedId)
      val prerequisite = resolved.module.members
        .collectFirst {
          case declaration: Protocol if declaration.id != protocol.id => declaration
        }
        .getOrElse(fail("Expected prerequisite protocol"))
      assertEquals(valid.constraints.head.resolvedId, prerequisite.id)
      assertEquals(resolved.errors.size, 2)
      val variableErrors = resolved.errors.collect {
        case SemanticError.UndefinedTypeVariable(variable, _, _) => variable
      }
      val protocolErrors = resolved.errors.collect {
        case SemanticError.ProtocolErrorFound(ProtocolError.UnknownProtocol(ref), _) => ref
      }
      assertEquals(variableErrors, Vector(invalid.variable))
      assertEquals(protocolErrors, Vector(invalid.constraints.head))
    }

  test("one protocol's prerequisite parameter does not become another protocol's binder"):
    val code = """
      protocol Render 'T where 'T: Describe = fn render(value: 'T): String; ;
      protocol Describe 'U where 'T: Render = fn describe(value: 'U): String; ;
    """
    parseNotFailed(code).map { parsed =>
      val resolved  = TypeResolver.rewriteModule(phaseInput(code, parsed))
      val protocols = resolved.module.members.collect { case protocol: Protocol => protocol }
      assertEquals(protocols.size, 2)
      val first  = protocols.head
      val second = protocols(1)
      assertEquals(first.constraints.head.variable.resolvedId, first.typeParameter.resolvedId)
      assertEquals(second.constraints.head.variable.resolvedId, None)
      assertNotEquals(first.typeParameter.resolvedId, second.typeParameter.resolvedId)
      val errors = resolved.errors.collect {
        case SemanticError.UndefinedTypeVariable(variable, _, _) => variable
      }
      assertEquals(errors, Vector(second.constraints.head.variable))
      assertEquals(resolved.errors.size, 1)
    }

  test("an unknown prerequisite identifies its written reference in CLI and LSP diagnostics"):
    val code = "protocol Render 'T where 'T: Missing = fn render(value: 'T): String; ;"
    parseNotFailed(code).map { parsed =>
      val initial     = phaseInput(code, parsed)
      val reference   = constrainedProtocol(initial.module).constraints.head.constraints.head
      val span        = reference.spanOpt.getOrElse(fail("Expected the written prerequisite span"))
      val resolved    = TypeResolver.rewriteModule(initial)
      val diagnostics = Diagnostics.fromCompilerState(resolved)
      val rendered = ErrorPrinter.prettyPrintSemanticErrors(
        resolved.semanticErrors.toList,
        Some(resolved.sourceInfo)
      )

      assertEquals(resolved.errors.size, 1)
      assertEquals(diagnostics.map(_.range), List(Range.fromSrcSpan(span)))
      assertEquals(diagnostics.map(_.message), List("Unknown protocol 'Missing'"))
      assertEquals(diagnostics.map(_.severity), List(DiagnosticSeverity.Error))
      assertEquals(code.substring(span.start.index, span.end.index), "Missing")
      assert(rendered.contains("Unknown protocol 'Missing'"), rendered)
      assert(rendered.contains(ErrorPrinter.formatLocation(span)), rendered)
    }

  test("instance headers and member annotations resolve while omissions remain absent"):
    val code = """
      type Number = Int;
      instance Apply Number =
        fn apply(value: Number): Number = value: Number;;
        fn identity(value) = value;;
      ;
    """

    parseNotFailed(code).map { parsed =>
      val initial           = phaseInput(code, parsed)
      val original          = onlyInstance(initial.module)
      val originalBinding   = original.members.head.binding
      val originalLambda    = onlyLambda(originalBinding)
      val resolved          = TypeResolver.rewriteModule(initial)
      val instance          = onlyInstance(resolved.module)
      val binding           = instance.members.head.binding
      val lambda            = onlyLambda(binding)
      val unannotated       = instance.members(1).binding
      val unannotatedLambda = onlyLambda(unannotated)
      val headerId          = resolvedTypeId(instance.concreteType, resolved.module)
      val alias = resolved.module.members
        .collectFirst {
          case value: TypeAlias if value.name == "Number" => value
        }
        .getOrElse(fail("Expected the Number alias"))

      assertEquals(resolved.errors, Vector.empty)
      assertEquals(headerId, alias.id.get)
      assertEquals(resolvedTypeId(ascription(lambda.params.head), resolved.module), headerId)
      assertEquals(resolvedTypeId(ascription(lambda), resolved.module), headerId)
      assertEquals(resolvedTypeId(ascription(binding), resolved.module), headerId)
      assertEquals(lambda.body.typeAsc, None)
      assertEquals(resolvedTypeId(ascription(lambda.body.terms.head), resolved.module), headerId)
      assertEquals(unannotated.typeAsc, None)
      assertEquals(unannotatedLambda.typeAsc, None)
      assertEquals(unannotatedLambda.params.map(_.typeAsc), List(None))
      assertEquals(instance.id, original.id)
      assertEquals(instance.source, original.source)
      assertEquals(instance.protocol, original.protocol)
      assertEquals(instance.concreteType.source, original.concreteType.source)
      assertEquals(binding.id, originalBinding.id)
      assertEquals(binding.source, originalBinding.source)
      assertEquals(binding.meta, originalBinding.meta)
      assertEquals(lambda.source, originalLambda.source)
      assertEquals(lambda.params.map(_.id), originalLambda.params.map(_.id))
      assertEquals(lambda.params.map(_.source), originalLambda.params.map(_.source))
      assertEquals(resolved.module.resolvables.lookup(binding.id.get), Some(binding))
      assertEquals(
        resolved.module.resolvables.lookup(lambda.params.head.id.get),
        Some(lambda.params.head)
      )
    }

  test("signature failures retain separate roots and preserve valid sibling types"):
    val code = "protocol Broken 'T = fn broken(first: Missing, valid: Int): Missing; ;"

    parseNotFailed(code).map { parsed =>
      val initial   = phaseInput(code, parsed)
      val original  = onlyProtocol(initial.module).signatures.head
      val resolved  = TypeResolver.rewriteModule(initial)
      val signature = onlyProtocol(resolved.module).signatures.head
      val parameter = undefinedType(ascription(signature.params.head))
      val result    = undefinedType(signature.returnType)

      assertEquals(
        resolved.errors.map(undefinedTypeName),
        Vector("Missing", "Missing")
      )
      assertEquals(parameter.originalType, ascription(original.params.head))
      assertEquals(result.originalType, original.returnType)
      assertEquals(parameter.source, ascription(original.params.head).source)
      assertEquals(result.source, original.returnType.source)
      assertEquals(parameter.causes ++ result.causes, resolved.diagnostics.records.map(_.id))
      assertEquals((parameter.causes ++ result.causes).distinct.size, 2)
      resolvedTypeId(ascription(signature.params(1)), resolved.module)
      assertEquals(resolved.module.resolvables.lookup(signature.id.get), Some(signature))
    }

  test("instance type failures accumulate once per header and written member annotation"):
    val code = """
      instance Broken MissingHeader =
        fn broken(value: MissingParam, valid: Int): MissingResult = 1: MissingBody;;
      ;
    """

    parseNotFailed(code).map { parsed =>
      val initial        = phaseInput(code, parsed)
      val original       = onlyInstance(initial.module)
      val originalLambda = onlyLambda(original.members.head.binding)
      val resolved       = TypeResolver.rewriteModule(initial)
      val instance       = onlyInstance(resolved.module)
      val binding        = instance.members.head.binding
      val lambda         = onlyLambda(binding)
      val header         = undefinedType(instance.concreteType)
      val parameter      = undefinedType(ascription(lambda.params.head))
      val body           = undefinedType(ascription(lambda.body.terms.head))
      val result         = undefinedType(ascription(lambda))
      val failures       = Vector(header, parameter, body, result)
      val writtenTypes = Vector(
        original.concreteType,
        ascription(originalLambda.params.head),
        ascription(originalLambda.body.terms.head),
        ascription(originalLambda)
      )

      assertEquals(
        resolved.errors.map(undefinedTypeName),
        Vector(
          "MissingHeader",
          "MissingParam",
          "MissingBody",
          "MissingResult"
        )
      )
      assertEquals(failures.map(_.originalType), writtenTypes)
      assertEquals(failures.map(_.source), writtenTypes.map(_.source))
      assertEquals(failures.flatMap(_.causes), resolved.diagnostics.records.map(_.id))
      assertEquals(failures.map(_.causes.size), Vector(1, 1, 1, 1))
      assertEquals(lambda.body.typeAsc, None)
      assertEquals(undefinedType(ascription(binding)).causes, result.causes)
      resolvedTypeId(ascription(lambda.params(1)), resolved.module)
      assertEquals(resolved.module.resolvables.lookup(binding.id.get), Some(binding))
    }

  test("an ambiguous instance header retains its written reference and exact type candidates"):
    val code = """
      type Choice = @metal:repr[t=i32];
      type Choice = @metal:repr[t=i64];
      instance Empty Choice = ;
    """

    parseNotFailed(code).map { parsed =>
      val assigned = phaseInput(code, parsed)
      val members = assigned.module.members.zipWithIndex.map {
        case (definition: TypeDef, index) if definition.source.isFromSource =>
          definition.copy(id = Some(s"candidate-$index"))
        case (member, _) => member
      }
      val initial = assigned.withModule(
        ResolvablesIndexer.refresh(assigned.module.copy(members = members))
      )
      val original = onlyInstance(initial.module)
      val candidates = members.collect {
        case definition: TypeDef if definition.source.isFromSource => definition
      }
      val resolved = TypeResolver.rewriteModule(initial)
      val failure = onlyInstance(resolved.module).concreteType match
        case error: AmbiguousTypeError => error
        case other => fail(s"Expected an ambiguous type, got $other")

      assertEquals(candidates.size, 2)
      assertEquals(resolved.errors.map(ambiguousTypeName), Vector("Choice"))
      assertEquals(failure.originalType, original.concreteType)
      assertEquals(failure.source, original.concreteType.source)
      assertEquals(failure.candidates, candidates)
      assertEquals(failure.causes, resolved.diagnostics.records.map(_.id))
    }

  test("distinct signature references with identical contents retain separate diagnostic roots"):
    val code = "protocol Broken 'T = fn broken(value: Missing): Missing; ;"

    parseNotFailed(code).map { parsed =>
      val initial   = phaseInput(code, parsed)
      val protocol  = onlyProtocol(initial.module)
      val signature = protocol.signatures.head
      val written = ascription(signature.params.head) match
        case ref: TypeRef => ref
        case other => fail(s"Expected a written type reference, got $other")
      val distinct = signature.copy(returnType = written.copy())
      val members = initial.module.members.map {
        case value: Protocol if value.id == protocol.id =>
          value.copy(signatures = List(distinct))
        case member => member
      }
      val resolved = TypeResolver.rewriteModule(
        initial.withModule(initial.module.copy(members = members))
      )
      val result     = onlyProtocol(resolved.module).signatures.head
      val parameter  = undefinedType(ascription(result.params.head))
      val returnType = undefinedType(result.returnType)

      assertEquals(parameter.originalType, returnType.originalType)
      assertEquals(
        resolved.errors.map(undefinedTypeName),
        Vector("Missing", "Missing")
      )
      assertEquals(parameter.causes ++ returnType.causes, resolved.diagnostics.records.map(_.id))
      assertEquals((parameter.causes ++ returnType.causes).distinct.size, 2)
    }

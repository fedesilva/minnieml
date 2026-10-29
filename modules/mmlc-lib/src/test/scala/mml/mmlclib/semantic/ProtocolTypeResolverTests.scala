package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

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
      assertEquals(protocol.typeParameter, original.typeParameter)
      assertEquals(ascription(signature.params.head), ascription(originalSignature.params.head))
      assertEquals(function.paramTypes, originalFunction.paramTypes)
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
        resolved.errors.map(_.message),
        Vector("Undefined type: 'Missing'", "Undefined type: 'Missing'")
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
        resolved.errors.map(_.message),
        Vector(
          "Undefined type: 'MissingHeader'",
          "Undefined type: 'MissingParam'",
          "Undefined type: 'MissingBody'",
          "Undefined type: 'MissingResult'"
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
      assertEquals(resolved.errors.map(_.message), Vector("Ambiguous type: 'Choice'"))
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
        resolved.errors.map(_.message),
        Vector("Undefined type: 'Missing'", "Undefined type: 'Missing'")
      )
      assertEquals(parameter.causes ++ returnType.causes, resolved.diagnostics.records.map(_.id))
      assertEquals((parameter.causes ++ returnType.causes).distinct.size, 2)
    }

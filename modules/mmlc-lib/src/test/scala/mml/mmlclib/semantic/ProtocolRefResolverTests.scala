package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class ProtocolRefResolverTests extends BaseEffFunSuite:

  test("source and generated constructor names resolve to the same binding"):
    val source = """
      struct Inner { value: Int };
      let sourceConstructor = Inner;
      let generatedConstructor = Inner;
    """
    beforeResolution(source).map { initial =>
      val generated = ConstructorGenerator.rewriteModule(initial)
      val constructor = generated.module.members
        .collectFirst {
          case binding: Bnd
              if binding.meta.exists(meta =>
                meta.origin == BindingOrigin.Constructor && meta.originalName == "Inner"
              ) =>
            binding
        }
        .getOrElse(fail("Expected generated constructor"))
      val generatedUse = binding(generated.module, "generatedConstructor")
      val generatedRef = onlyRef(generatedUse.value).copy(
        source     = SourceOrigin.Synth,
        name       = constructor.name,
        resolvedId = constructor.id
      )
      val updatedUse =
        generatedUse.copy(value = generatedUse.value.copy(terms = List(generatedRef)))
      val members = generated.module.members.map {
        case candidate: Bnd if candidate.id == generatedUse.id => updatedUse
        case member => member
      }

      val result      = resolve(generated.withModule(generated.module.copy(members = members)))
      val sourceRef   = onlyRef(binding(result.module, "sourceConstructor").value)
      val internalRef = onlyRef(binding(result.module, "generatedConstructor").value)

      assertEquals(result.errors, Vector.empty)
      assertNotEquals(sourceRef.name, internalRef.name)
      assertEquals(sourceRef.candidateIds, constructor.id.toList)
      assertEquals(sourceRef.resolvedId, constructor.id)
      assertEquals(internalRef.candidateIds, constructor.id.toList)
      assertEquals(internalRef.resolvedId, constructor.id)
    }

  test("one signature remains a candidate when no instance exists"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      let use = identity;
    """
    beforeResolution(source).map { initial =>
      val signature = protocol(initial.module, "Identity").signatures.head

      val result = resolve(initial)
      val ref    = onlyRef(binding(result.module, "use").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds, signature.id.toList)
      assertEquals(ref.resolvedId, None)
    }

  test("Identity before OtherIdentity retains both signatures"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      protocol OtherIdentity 'T = fn identity(value: 'T): 'T; ;
      let use = identity;
    """
    beforeResolution(source).map { initial =>
      val identity = protocol(initial.module, "Identity").signatures.head
      val other    = protocol(initial.module, "OtherIdentity").signatures.head
      val expected = (identity.id.toList ++ other.id.toList).toSet

      val result = resolve(initial)
      val ref    = onlyRef(binding(result.module, "use").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.candidateIds.size, 2)
      assertEquals(ref.resolvedId, None)
    }

  test("OtherIdentity before Identity retains both signatures"):
    val source = """
      protocol OtherIdentity 'T = fn identity(value: 'T): 'T; ;
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      let use = identity;
    """
    beforeResolution(source).map { initial =>
      val identity = protocol(initial.module, "Identity").signatures.head
      val other    = protocol(initial.module, "OtherIdentity").signatures.head
      val expected = (identity.id.toList ++ other.id.toList).toSet

      val result = resolve(initial)
      val ref    = onlyRef(binding(result.module, "use").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, expected)
      assertEquals(ref.candidateIds.size, 2)
      assertEquals(ref.resolvedId, None)
    }

  test("instance implementations stay indexed without joining source candidates"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      instance Identity Bool = fn identity(value) = value;; ;
      let use = identity;
    """
    beforeResolution(source).map { initial =>
      val signature = protocol(initial.module, "Identity").signatures.head
      val implementationIds = initial.module.members.collect { case instance: ProtocolInstance =>
        instance.memberIds
      }.flatten
      assertEquals(implementationIds.size, 2)
      val integerImplementation = implementationIds.head
      val booleanImplementation = implementationIds(1)

      val result = resolve(initial)
      val ref    = onlyRef(binding(result.module, "use").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds, signature.id.toList)
      assertEquals(ref.resolvedId, None)
      assert(result.module.resolvables.lookup(integerImplementation).exists(_.isInstanceOf[Bnd]))
      assert(result.module.resolvables.lookup(booleanImplementation).exists(_.isInstanceOf[Bnd]))
      assert(!ref.candidateIds.contains(integerImplementation))
      assert(!ref.candidateIds.contains(booleanImplementation))
    }

  test("an internal instance binding name is not a source name"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      let use = identity;
    """
    beforeResolution(source).map { initial =>
      val instance       = initial.module.members.collectFirst { case i: ProtocolInstance => i }.get
      val implementation = initial.module.resolvables.lookup(instance.memberIds.head).get
      val use            = binding(initial.module, "use")
      val internalRef    = onlyRef(use.value).copy(name = implementation.name)
      val updatedUse     = use.copy(value = use.value.copy(terms = List(internalRef)))
      val members = initial.module.members.map {
        case candidate: Bnd if candidate.id == use.id => updatedUse
        case member => member
      }

      val result = resolve(initial.withModule(initial.module.copy(members = members)))

      val errors = result.semanticErrors.collect { case error: SemanticError.UndefinedRef => error }
      assertEquals(errors.map(_.ref), Vector(internalRef))
      assertEquals(result.errors.size, 1)
    }

  test("a function parameter hides a protocol signature with the same name"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      fn use(identity: Int): Int = identity;;
    """
    beforeResolution(source).map { initial =>
      val result = resolve(initial)
      val body   = lambda(binding(result.module, "use"))
      val param  = body.params.head
      val ref    = onlyRef(body.body)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds, param.id.toList)
      assertEquals(ref.resolvedId, param.id)
    }

  test("the nearest local name hides both an outer parameter and a protocol signature"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      fn use(identity: Int): Int = let identity = 1; identity;;
    """
    beforeResolution(source).map { initial =>
      val result = resolve(initial)
      val body   = lambda(binding(result.module, "use"))
      val local = TermTraversal
        .collect(body.body) { case scope: Lambda =>
          scope.params
        }
        .flatten
      assertEquals(local.size, 1)
      val ref = onlyRef(body.body)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds, local.head.id.toList)
      assertEquals(ref.resolvedId, local.head.id)
      assertNotEquals(ref.resolvedId, body.params.head.id)
    }

  test("a recursive local initializer and its continuation hide a protocol signature"):
    val source = """
      protocol Identity 'T = fn identity(): 'T; ;
      fn use() = let identity = { identity (); }; identity ();;
    """
    beforeResolution(source).map { initial =>
      val result = resolve(initial)
      val body   = lambda(binding(result.module, "use"))
      val local = TermTraversal
        .collect(body.body) { case scope: Lambda =>
          scope.params
        }
        .flatten
      val refs = TermTraversal.collect(body.body) { case ref: Ref => ref }

      assertEquals(result.errors, Vector.empty)
      assertEquals(local.size, 1)
      assertEquals(refs.size, 2)
      val continuationRef = refs.head
      val initializerRef  = refs(1)
      assertEquals(continuationRef.candidateIds, local.head.id.toList)
      assertEquals(continuationRef.resolvedId, local.head.id)
      assertEquals(initializerRef.candidateIds, local.head.id.toList)
      assertEquals(initializerRef.resolvedId, local.head.id)
    }

  test("an identifier-shaped operator retains unary and binary signatures before grouping"):
    val source = """
      protocol Shift 'T =
        op shift(value: 'T): 'T 90 right;
        op shift(left: 'T, right: 'T): 'T 60 left;
      ;
      let use = shift;
    """
    beforeResolution(source).map { initial =>
      val signatures = protocol(initial.module, "Shift").signatures

      val result = resolve(initial)
      val ref    = onlyRef(binding(result.module, "use").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(ref.candidateIds.toSet, signatures.flatMap(_.id).toSet)
      assertEquals(ref.candidateIds.size, 2)
      assertEquals(ref.resolvedId, None)
    }

  test("resolving again clears an old binding target when a signature becomes visible"):
    val source = """
      fn identity(value: Int): Int = value;;
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      let use = identity;
    """
    beforeResolution(source).map { initial =>
      val requirement = protocol(initial.module, "Identity")
      val ordinary    = binding(initial.module, "identity")
      val ordinaryOnly = initial.module.copy(
        members = initial.module.members.filterNot(_.isInstanceOf[Protocol])
      )
      val first    = resolve(initial.withModule(ordinaryOnly))
      val firstRef = onlyRef(binding(first.module, "use").value)
      assertEquals(first.errors, Vector.empty)
      assertEquals(firstRef.resolvedId, ordinary.id)

      val expanded = first.module.copy(members = first.module.members :+ requirement)
      val result   = resolve(first.withModule(ResolvablesIndexer.refresh(expanded)))
      val ref      = onlyRef(binding(result.module, "use").value)

      assertEquals(result.errors, Vector.empty)
      assertEquals(
        ref.candidateIds.toSet,
        (ordinary.id.toList ++ requirement.signatures.flatMap(_.id)).toSet
      )
      assertEquals(ref.candidateIds.size, 2)
      assertEquals(ref.resolvedId, None)
    }

  test("a missing visible signature produces a registry diagnostic"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      let use = identity;
    """
    beforeResolution(source).map { initial =>
      val signatureId = protocol(initial.module, "Identity").signatures.head.id.get
      val index       = initial.module.resolvables
      val incomplete = initial.module.copy(
        resolvables = index.copy(resolvables = index.resolvables - signatureId)
      )

      val result = resolve(initial.withModule(incomplete))
      val reasons = result.semanticErrors.collect {
        case SemanticError.ProtocolErrorFound(ProtocolError.RegistryFailure(_, reason), _) => reason
      }

      assertEquals(reasons, Vector(ProtocolLookupError.MissingNode(signatureId)))
      assertEquals(result.errors.size, 1)
    }

  private def beforeResolution(source: String): IO[CompilerState] =
    parseNotFailed(source).map { parsed =>
      val initial = CompilerState.empty(
        injectBasicTypes(parsed),
        SourceInfo(source),
        CompilerConfig.default
      )
      val checked  = DuplicateNameChecker.rewriteModule(initial)
      val assigned = IdAssigner.rewriteModule(checked)
      val typed    = TypeResolver.rewriteModule(assigned)
      val result   = ProtocolChecker.rewriteModule(typed)
      assertEquals(result.errors, Vector.empty)
      result
    }

  private def resolve(state: CompilerState): CompilerState =
    ResolvablesIndexer.rewriteModule(RefResolver.rewriteModule(state))

  private def protocol(module: Module, name: String): Protocol =
    module.members
      .collectFirst { case protocol: Protocol if protocol.name == name => protocol }
      .getOrElse(fail(s"Expected protocol $name"))

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case binding: Bnd if binding.name == name => binding }
      .getOrElse(fail(s"Expected binding $name"))

  private def lambda(binding: Bnd): Lambda = binding.value.terms match
    case (body: Lambda) :: Nil => body
    case other => fail(s"Expected one lambda, got $other")

  private def onlyRef(term: Term): Ref =
    val refs = TermTraversal.collect(term) { case ref: Ref => ref }
    assertEquals(refs.size, 1)
    refs.head

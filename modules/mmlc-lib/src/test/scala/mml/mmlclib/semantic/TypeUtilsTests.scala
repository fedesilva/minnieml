package mml.mmlclib.semantic

import cats.syntax.option.*
import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.{
  DestructionTargets,
  MemoryFunctions,
  NativeTypeQueries,
  TypeMemoryQueries,
  TypeQueries
}
import mml.mmlclib.test.BaseEffFunSuite

class TypeUtilsTests extends BaseEffFunSuite:

  List(false, true).foreach { heap =>
    test(s"recursive struct classification terminates with heap field $heap") {
      val extra = if heap then ", payload: Int -> Int" else ""
      parseNotFailed(s"struct Recursive { next: (Recursive)$extra };").map { module =>
        val structs = module.members.collect { case struct: TypeStruct =>
          struct.copy(id = Some(s"Test::typestruct::${struct.name}"))
        }
        val index =
          structs.foldLeft(module.resolvables)((index, struct) => index.updatedType(struct))
        assertEquals(TypeMemoryQueries.hasHeapFields(structs.head, index), heap)
        assertEquals(TypeMemoryQueries.isHeapType(typeReference("Recursive", index), index), heap)
      }
    }
  }

  List("String", "IntArray").foreach { fieldType =>
    test(s"grouped $fieldType fields use the matching memory functions") {
      semNotFailed(s"struct Grouped { value: ($fieldType) };").map { module =>
        val struct       = module.members.collectFirst { case struct: TypeStruct => struct }.get
        val field        = struct.fields.head
        val expectedFree = DestructionTargets.forType(field.typeSpec, module.resolvables)
        assert(expectedFree.nonEmpty)
        val destructor = module.members.collectFirst {
          case binding: Bnd
              if binding.meta.exists(_.origin == BindingOrigin.Destructor) &&
                binding.value.terms.exists {
                  case lambda: Lambda =>
                    lambda.params.exists(_.typeSpec.exists {
                      case ref: TypeRef => ref.resolvedId == struct.id
                      case _ => false
                    })
                  case _ => false
                } =>
            binding
        }.get
        val calls = TermTraversal
          .collect(destructor.value) { case app: App =>
            app.fn
          }
          .collect { case ref: Ref => ref.resolvedId }
          .flatten
        assert(calls.contains(expectedFree.get), calls)
        val cloneName =
          cloneNameFor(typeReference(fieldType, module.resolvables), module)
        val clone = module.members.collectFirst {
          case binding: Bnd if cloneName.contains(binding.name) => binding
        }.get
        val fieldClones =
          module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
            TermTraversal.collect(binding.value) {
              case ref: Ref if ref.resolvedId == clone.id => ref
            }
          }
        assert(fieldClones.nonEmpty)
      }
    }
  }

  test("grouped heap fields produce valid LLVM layouts and cleanup") {
    compileAndGenerate("""
      struct Grouped { text: (String), values: (IntArray) };
      fn main(): Int =
        let value = Grouped (int_to_str 123) (ar_int_new 4);
        ar_int_set value.values 0 42;
        ar_int_get value.values 0;
      ;
    """).void
  }

  test("freeFnFor returns explicit free function name when provided"):
    val code = """
      type Handle = @metal:repr[t=*i8, mem=heap, free=close_handle];
    """

    semNotFailed(code).map { module =>
      val result =
        TypeMemoryQueries.freeFnFor(typeReference("Handle", module.resolvables), module.resolvables)
      assertEquals(result, Some("close_handle"))
    }

  test("freeFnFor returns convention name when no free attribute"):
    val code = """
      type MyBuf = @metal:repr[t=*i8, mem=heap];
    """

    semNotFailed(code).map { module =>
      val result =
        TypeMemoryQueries.freeFnFor(typeReference("MyBuf", module.resolvables), module.resolvables)
      assertEquals(result, Some("__free_MyBuf"))
    }

  test("freeFnFor returns None for non-heap native type"):
    val code = """
      type MyInt = @metal:repr[t=i64];
    """

    semNotFailed(code).map { module =>
      val result =
        TypeMemoryQueries.freeFnFor(typeReference("MyInt", module.resolvables), module.resolvables)
      assertEquals(result, None)
    }

  test("freeFnFor returns explicit free for native struct"):
    val code = """
      type MyStr = @metal:repr[mem=heap, free=destroy_str] {
        length: Int,
        data: Int
      };
    """

    semNotFailed(code).map { module =>
      val result =
        TypeMemoryQueries.freeFnFor(typeReference("MyStr", module.resolvables), module.resolvables)
      assertEquals(result, Some("destroy_str"))
    }

  test("heap native aliases resolve to the underlying memory functions") {
    val code = """
      type HeapName = String;
      type OtherHeapName = HeapName;
    """

    semNotFailed(code).map { module =>
      assert(
        TypeMemoryQueries.isHeapType(
          typeReference("HeapName", module.resolvables),
          module.resolvables
        )
      )
      assert(
        TypeMemoryQueries.isHeapType(
          typeReference("OtherHeapName", module.resolvables),
          module.resolvables
        )
      )
      assertEquals(
        TypeMemoryQueries
          .freeFnFor(typeReference("HeapName", module.resolvables), module.resolvables),
        Some("__free_String")
      )
      assertEquals(
        TypeMemoryQueries
          .freeFnFor(typeReference("OtherHeapName", module.resolvables), module.resolvables),
        Some("__free_String")
      )
      val stringId = module.resolvables.resolvableTypes.values
        .find(_.name == "String")
        .flatMap(_.id)
      assert(stringId.nonEmpty)
      val aliases = module.members.collect {
        case alias: TypeAlias if Set("HeapName", "OtherHeapName").contains(alias.name) => alias
      }
      assertEquals(aliases.size, 2)
      aliases.foreach { alias =>
        val reference = TypeRef(alias.source, alias.name, alias.id)
        val canonicalId = TypeQueries
          .canonical(reference, module.resolvables)
          .collect { case ref: TypeRef =>
            ref.resolvedId
          }
          .flatten
        assertEquals(canonicalId, stringId)
      }
      assertEquals(
        cloneNameFor(typeReference("HeapName", module.resolvables), module),
        cloneNameFor(typeReference("String", module.resolvables), module)
      )
      assertEquals(
        cloneNameFor(typeReference("OtherHeapName", module.resolvables), module),
        cloneNameFor(typeReference("String", module.resolvables), module)
      )
    }
  }

  test("heap native aliases without explicit free use the underlying type name"):
    val code = """
      type BaseBuf = @metal:repr[t=*i8, mem=heap];
      type AliasBuf = BaseBuf;
    """

    semNotFailed(code).map { module =>
      assert(
        TypeMemoryQueries.isHeapType(
          typeReference("AliasBuf", module.resolvables),
          module.resolvables
        )
      )
      assertEquals(
        TypeMemoryQueries
          .freeFnFor(typeReference("AliasBuf", module.resolvables), module.resolvables),
        Some("__free_BaseBuf")
      )
      assertEquals(
        cloneNameFor(typeReference("AliasBuf", module.resolvables), module),
        none[String]
      )
    }

  test("heap struct aliases resolve to the underlying struct memory functions"):
    val code = """
      struct Person { name: String };
      type PersonAlias = Person;
      type OtherPersonAlias = PersonAlias;
    """

    semNotFailed(code).map { module =>
      assert(
        TypeMemoryQueries.isHeapType(
          typeReference("PersonAlias", module.resolvables),
          module.resolvables
        )
      )
      assert(
        TypeMemoryQueries.isHeapType(
          typeReference("OtherPersonAlias", module.resolvables),
          module.resolvables
        )
      )
      assert(
        TypeMemoryQueries.isStructWithHeapFields(
          typeReference("PersonAlias", module.resolvables),
          module.resolvables
        )
      )
      assert(
        TypeMemoryQueries.isStructWithHeapFields(
          typeReference("OtherPersonAlias", module.resolvables),
          module.resolvables
        )
      )
      assertEquals(
        TypeMemoryQueries
          .freeFnFor(typeReference("PersonAlias", module.resolvables), module.resolvables),
        Some("__free_Person")
      )
      assertEquals(
        TypeMemoryQueries
          .freeFnFor(typeReference("OtherPersonAlias", module.resolvables), module.resolvables),
        Some("__free_Person")
      )
      assertEquals(
        cloneNameFor(typeReference("PersonAlias", module.resolvables), module),
        cloneNameFor(typeReference("Person", module.resolvables), module)
      )
      assertEquals(
        cloneNameFor(typeReference("OtherPersonAlias", module.resolvables), module),
        cloneNameFor(typeReference("Person", module.resolvables), module)
      )
    }

  test("aliases preserve nominal heap types and non-cloneable function fields") {
    val source = """
      type First = @metal:repr[t=ptr, mem=heap];
      type Second = @metal:repr[t=ptr, mem=heap];
      type FirstAlias = First;
      type SecondAlias = Second;
      type Count = Int;
      struct Callback { invoke: Int -> Int };
      struct Wrapper { callback: Callback };
      type CallbackAlias = Callback;
      type WrapperAlias = Wrapper;
    """

    semNotFailed(source).map { module =>
      val index = module.resolvables
      assertEquals(
        cloneNameFor(typeReference("FirstAlias", index), module),
        none[String]
      )
      assertEquals(
        cloneNameFor(typeReference("SecondAlias", index), module),
        none[String]
      )
      assertEquals(cloneNameFor(typeReference("Count", index), module), none[String])
      List("CallbackAlias", "WrapperAlias").foreach { name =>
        assert(TypeMemoryQueries.isStructWithHeapFields(typeReference(name, index), index))
        assertEquals(cloneNameFor(typeReference(name, index), module), none[String])
      }
    }
  }

  test("scalar struct aliases share their generated Clone instance") {
    val source = """
      struct P { x: Int };
      type PA = P;
      type PAA = PA;
    """

    semNotFailed(source).map { module =>
      List("P", "PA", "PAA").foreach { name =>
        assertEquals(
          cloneNameFor(typeReference(name, module.resolvables), module),
          cloneNameFor(typeReference("P", module.resolvables), module),
          name
        )
        assert(
          !TypeMemoryQueries
            .isStructWithHeapFields(typeReference(name, module.resolvables), module.resolvables),
          name
        )
      }
    }
  }

  private val synthetic = SourceOrigin.Synth

  private def nominal(owner: String, name: String, native: NativeType): TypeDef =
    TypeDef(
      source   = synthetic,
      nameNode = Name.synth(name),
      typeSpec = native.some,
      id       = BindingIds.declaration(owner, name, "typedef").some
    )

  private def refTo(declaration: ResolvableType): TypeRef =
    TypeRef(synthetic, declaration.name, declaration.id)

  test("resolved aliases distinguish a scalar from a same-name stdlib heap type") {
    val heap   = nominal("stdlib", "String", NativePointer(synthetic, "i8", MemEffect.Alloc.some))
    val scalar = nominal("User", "String", NativePrimitive(synthetic, "i64"))
    val alias = TypeAlias(
      source   = synthetic,
      nameNode = Name.synth("Alias"),
      typeRef  = refTo(scalar),
      id       = "User::typealias::Alias".some
    )
    val index = ResolvablesIndex().updatedAllTypes(List(heap, scalar, alias))
    val tpe   = refTo(alias)
    assertEquals(TypeQueries.declaration(tpe, index).flatMap(_.id), scalar.id)
    assert(!TypeMemoryQueries.isHeapType(tpe, index))
    assert(!TypeMemoryQueries.requiresDestruction(tpe, index))
    assertEquals(TypeMemoryQueries.freeFnFor(tpe, index), none[String])
    assertEquals(NativeTypeQueries.resolveNativeType(tpe, index), scalar.typeSpec)
    assert(!NativeTypeQueries.isPointerLike(tpe, index))
    assert(TypeMemoryQueries.isHeapType(refTo(heap), index))
  }

  test("missing identities never fall back to same-name declarations") {
    val heap  = nominal("stdlib", "String", NativePointer(synthetic, "i8", MemEffect.Alloc.some))
    val index = ResolvablesIndex().updatedType(heap)
    List(none[String], "missing".some).foreach { id =>
      val tpe = TypeRef(synthetic, heap.name, id)
      assertEquals(TypeQueries.declaration(tpe, index), none[ResolvableType])
      assert(!TypeMemoryQueries.requiresDestruction(tpe, index))
      assertEquals(NativeTypeQueries.resolveNativeType(tpe, index), none[NativeType])
      assertEquals(TypeMemoryQueries.freeFnFor(tpe, index), none[String])
    }
  }

  test("recursive property queries distinguish same-name struct identities") {
    val firstRef  = TypeRef(synthetic, "Node", "First::typestruct::Node".some)
    val secondRef = TypeRef(synthetic, "Node", "Second::typestruct::Node".some)
    val function =
      TypeFn(synthetic, cats.data.NonEmptyList.one(TypeUnit(synthetic)), TypeUnit(synthetic))
    def node(ref: TypeRef, fields: Vector[Field]): TypeStruct =
      TypeStruct(
        synthetic,
        None,
        Visibility.Protected,
        Name.synth(ref.name),
        fields,
        ref.resolvedId
      )
    val first = node(firstRef, Vector(Field(synthetic, Name.synth("next"), secondRef)))
    val second = node(
      secondRef,
      Vector(
        Field(synthetic, Name.synth("back"), firstRef),
        Field(synthetic, Name.synth("callback"), function)
      )
    )
    val index = ResolvablesIndex().updatedAllTypes(List(first, second))
    List(firstRef, secondRef).foreach { ref =>
      assert(TypeMemoryQueries.requiresDestruction(ref, index))
      assert(TypeMemoryQueries.isStructWithHeapFields(ref, index))
      assert(TypeMemoryQueries.containsFunction(ref, index))
    }
    val scalarSecond = second.copy(fields = second.fields.take(1))
    val scalarIndex  = index.updatedType(scalarSecond)
    assert(!TypeMemoryQueries.requiresDestruction(firstRef, scalarIndex))
    assert(!TypeMemoryQueries.containsFunction(firstRef, scalarIndex))
  }

  test("alias cycles terminate across property queries") {
    val a = TypeAlias(
      source   = synthetic,
      nameNode = Name.synth("A"),
      typeRef  = TypeRef(synthetic, "B", "Test::typealias::B".some),
      id       = "Test::typealias::A".some
    )
    val b = TypeAlias(
      source   = synthetic,
      nameNode = Name.synth("B"),
      typeRef  = refTo(a),
      id       = "Test::typealias::B".some
    )
    val index = ResolvablesIndex().updatedAllTypes(List(a, b))
    assertEquals(TypeQueries.canonical(refTo(a), index), none[Type])
    assertEquals(NativeTypeQueries.resolveNativeType(refTo(a), index), none[NativeType])
    assert(!TypeMemoryQueries.requiresDestruction(refTo(a), index))
    assertEquals(TypeMemoryQueries.freeFnFor(refTo(a), index), none[String])
  }

  private def memoryBinding(owner: String, name: String): Bnd =
    Bnd(
      source   = synthetic,
      nameNode = Name.synth(name),
      value    = Expr(synthetic, Nil),
      id       = BindingIds.declaration(owner, name).some
    )

  test("named destruction lookup prefers destructor origin and retains ordinary fallback") {
    val ordinary = memoryBinding("User", "release")
    val destructor = memoryBinding("Generated", "release").copy(
      meta = BindingMeta(
        BindingKind.Destructor,
        CallableArity.Unary,
        "release",
        "release"
      ).some
    )
    val index = ResolvablesIndex().updatedAll(List(ordinary, destructor))
    assertEquals(DestructionTargets.named("release", index), destructor.id)
    assertEquals(
      DestructionTargets.named("release", ResolvablesIndex().updated(ordinary)),
      ordinary.id
    )
    assertEquals(DestructionTargets.named("missing", index), None)
  }

  test("memory targets retain declaration ownership with colliding function names") {
    val native      = NativePointer(synthetic, "i8", MemEffect.Alloc.some, "release".some)
    val first       = nominal("First", "Buffer", native)
    val second      = nominal("Second", "Buffer", native)
    val firstFree   = memoryBinding("First", "release")
    val secondFree  = memoryBinding("Second", "release")
    val firstClone  = memoryBinding("First", "__clone_Buffer")
    val secondClone = memoryBinding("Second", "__clone_Buffer")
    val alias = TypeAlias(
      source   = synthetic,
      nameNode = Name.synth("BufferAlias"),
      typeRef  = refTo(second),
      id       = "First::typealias::BufferAlias".some
    )
    val index = ResolvablesIndex()
      .updatedAllTypes(List(first, second, alias))
      .updatedAll(List(firstFree, secondFree, firstClone, secondClone))
    assertEquals(MemoryFunctions.free(refTo(first), index).map(_.id), firstFree.id)
    assertEquals(MemoryFunctions.free(refTo(alias), index).map(_.id), secondFree.id)
    assertEquals(DestructionTargets.forType(refTo(alias), index), secondFree.id)
    val missing = index.copy(resolvables = index.resolvables -- secondFree.id -- secondClone.id)
    assertEquals(MemoryFunctions.free(refTo(alias), missing), none[MemoryFunctions.Target])
  }

  test("pending struct helpers are available only to generation in their owning module") {
    val heap = nominal("stdlib", "Buffer", NativePointer(synthetic, "i8", MemEffect.Alloc.some))
    val struct = TypeStruct(
      synthetic,
      None,
      Visibility.Protected,
      Name.synth("Box"),
      Vector(Field(synthetic, Name.synth("payload"), refTo(heap))),
      "User::typestruct::Box".some
    )
    val index = ResolvablesIndex().updatedAllTypes(List(heap, struct))
    assertEquals(MemoryFunctions.free(struct, index), none[MemoryFunctions.Target])
    assertEquals(MemoryFunctions.free(struct, index, "Other".some), none[MemoryFunctions.Target])
    val free  = memoryBinding("User", "__free_Box")
    val clone = memoryBinding("User", "__clone_Box")
    assertEquals(MemoryFunctions.free(struct, index, "User".some).map(_.id), free.id)
    val registered = index.updatedAll(List(free, clone))
    assertEquals(MemoryFunctions.free(struct, registered).map(_.id), free.id)
  }

  test("struct generation reports missing native destructors") {
    val heap = nominal("User", "Buffer", NativePointer(synthetic, "i8", MemEffect.Alloc.some))
    val struct = TypeStruct(
      synthetic,
      None,
      Visibility.Protected,
      Name.synth("Box"),
      Vector(Field(synthetic, Name.synth("payload"), refTo(heap))),
      "User::typestruct::Box".some
    )
    val module = ResolvablesIndexer.refresh(
      Module(synthetic, "User", Visibility.Protected, List(heap, struct))
    )
    val state = mml.mmlclib.compiler.CompilerState
      .empty(module, mml.mmlclib.parser.SourceInfo(""), mml.mmlclib.compiler.CompilerConfig.default)
    val result = MemoryFunctionGenerator.rewriteModule(state)
    val missing = result.semanticErrors.collect {
      case SemanticError.InvalidExpression(_, message, "memory-function-generator")
          if message == "Missing destructor for field 'payload'" =>
        message
    }
    assertEquals(missing.size, 1)
  }

  test("explicit native destructors can name stdlib bindings without borrowing clone contracts") {
    val name = "__clone_Buffer"
    val heap =
      nominal("User", "Buffer", NativePointer(synthetic, "i8", MemEffect.Alloc.some, name.some))
    val imported = memoryBinding("stdlib", name)
    val local    = memoryBinding("User", name)
    val index    = ResolvablesIndex().updatedType(heap).updated(imported)
    assertEquals(MemoryFunctions.free(refTo(heap), index).map(_.id), imported.id)
    assertEquals(MemoryFunctions.free(refTo(heap), index.updated(local)).map(_.id), local.id)
  }

  test("failed type nodes cannot select a declaration from their written name") {
    val heap    = nominal("stdlib", "String", NativePointer(synthetic, "i8", MemEffect.Alloc.some))
    val index   = ResolvablesIndex().updatedType(heap)
    val written = TypeRef(synthetic, "String")
    val failures = List[Type](
      UndefinedTypeError(synthetic, written),
      AmbiguousTypeError(synthetic, written, List(heap)),
      ParseTypeError(synthetic, "invalid type", None)
    )
    failures.foreach { tpe =>
      assertEquals(TypeQueries.declaration(tpe, index), none[ResolvableType])
      assert(!TypeMemoryQueries.requiresDestruction(tpe, index))
      assertEquals(NativeTypeQueries.resolveNativeType(tpe, index), none[NativeType])
      assertEquals(MemoryFunctions.free(tpe, index), none[MemoryFunctions.Target])
    }
  }

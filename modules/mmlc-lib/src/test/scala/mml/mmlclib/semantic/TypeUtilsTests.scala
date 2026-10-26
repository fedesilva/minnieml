package mml.mmlclib.semantic

import cats.syntax.option.*
import mml.mmlclib.ast.*
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
        assertEquals(TypeUtils.hasHeapFields(structs.head, index), heap)
        assertEquals(TypeUtils.isHeapType(typeReference("Recursive", index), index), heap)
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
          TypeUtils.cloneFnFor(typeReference(fieldType, module.resolvables), module.resolvables)
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
        TypeUtils.freeFnFor(typeReference("Handle", module.resolvables), module.resolvables)
      assertEquals(result, Some("close_handle"))
    }

  test("freeFnFor returns convention name when no free attribute"):
    val code = """
      type MyBuf = @metal:repr[t=*i8, mem=heap];
    """

    semNotFailed(code).map { module =>
      val result =
        TypeUtils.freeFnFor(typeReference("MyBuf", module.resolvables), module.resolvables)
      assertEquals(result, Some("__free_MyBuf"))
    }

  test("freeFnFor returns None for non-heap native type"):
    val code = """
      type MyInt = @metal:repr[t=i64];
    """

    semNotFailed(code).map { module =>
      val result =
        TypeUtils.freeFnFor(typeReference("MyInt", module.resolvables), module.resolvables)
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
        TypeUtils.freeFnFor(typeReference("MyStr", module.resolvables), module.resolvables)
      assertEquals(result, Some("destroy_str"))
    }

  test("heap native aliases resolve to the underlying memory functions") {
    val code = """
      type HeapName = String;
      type OtherHeapName = HeapName;
    """

    semNotFailed(code).map { module =>
      assert(
        TypeUtils.isHeapType(typeReference("HeapName", module.resolvables), module.resolvables)
      )
      assert(
        TypeUtils.isHeapType(typeReference("OtherHeapName", module.resolvables), module.resolvables)
      )
      assertEquals(
        TypeUtils.freeFnFor(typeReference("HeapName", module.resolvables), module.resolvables),
        Some("__free_String")
      )
      assertEquals(
        TypeUtils.freeFnFor(typeReference("OtherHeapName", module.resolvables), module.resolvables),
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
        val canonicalId = TypeUtils
          .canonical(reference, module.resolvables)
          .collect { case ref: TypeRef =>
            ref.resolvedId
          }
          .flatten
        assertEquals(canonicalId, stringId)
      }
      assertEquals(
        TypeUtils.cloneFnFor(typeReference("HeapName", module.resolvables), module.resolvables),
        Some("__clone_String")
      )
      assertEquals(
        TypeUtils
          .cloneFnFor(typeReference("OtherHeapName", module.resolvables), module.resolvables),
        Some("__clone_String")
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
        TypeUtils.isHeapType(typeReference("AliasBuf", module.resolvables), module.resolvables)
      )
      assertEquals(
        TypeUtils.freeFnFor(typeReference("AliasBuf", module.resolvables), module.resolvables),
        Some("__free_BaseBuf")
      )
      assertEquals(
        TypeUtils.cloneFnFor(typeReference("AliasBuf", module.resolvables), module.resolvables),
        Some("__clone_BaseBuf")
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
        TypeUtils.isHeapType(typeReference("PersonAlias", module.resolvables), module.resolvables)
      )
      assert(
        TypeUtils.isHeapType(
          typeReference("OtherPersonAlias", module.resolvables),
          module.resolvables
        )
      )
      assert(
        TypeUtils.isStructWithHeapFields(
          typeReference("PersonAlias", module.resolvables),
          module.resolvables
        )
      )
      assert(
        TypeUtils.isStructWithHeapFields(
          typeReference("OtherPersonAlias", module.resolvables),
          module.resolvables
        )
      )
      assertEquals(
        TypeUtils.freeFnFor(typeReference("PersonAlias", module.resolvables), module.resolvables),
        Some("__free_Person")
      )
      assertEquals(
        TypeUtils
          .freeFnFor(typeReference("OtherPersonAlias", module.resolvables), module.resolvables),
        Some("__free_Person")
      )
      assertEquals(
        TypeUtils.cloneFnFor(typeReference("PersonAlias", module.resolvables), module.resolvables),
        Some("__clone_Person")
      )
      assertEquals(
        TypeUtils
          .cloneFnFor(typeReference("OtherPersonAlias", module.resolvables), module.resolvables),
        Some("__clone_Person")
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
        TypeUtils.cloneFnFor(typeReference("FirstAlias", index), index),
        "__clone_First".some
      )
      assertEquals(
        TypeUtils.cloneFnFor(typeReference("SecondAlias", index), index),
        "__clone_Second".some
      )
      assertEquals(TypeUtils.cloneFnFor(typeReference("Count", index), index), none[String])
      List("CallbackAlias", "WrapperAlias").foreach { name =>
        assert(TypeUtils.isStructWithHeapFields(typeReference(name, index), index))
        assertEquals(TypeUtils.cloneFnFor(typeReference(name, index), index), none[String])
      }
    }
  }

  test("structs without heap fields and their aliases have no clone function") {
    val source = """
      struct P { x: Int };
      type PA = P;
      type PAA = PA;
    """

    semNotFailed(source).map { module =>
      List("P", "PA", "PAA").foreach { name =>
        assertEquals(
          TypeUtils.cloneFnFor(typeReference(name, module.resolvables), module.resolvables),
          none[String],
          name
        )
        assert(
          !TypeUtils
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
    assertEquals(TypeUtils.declaration(tpe, index).flatMap(_.id), scalar.id)
    assert(!TypeUtils.isHeapType(tpe, index))
    assert(!TypeUtils.requiresDestruction(tpe, index))
    assertEquals(TypeUtils.freeFnFor(tpe, index), none[String])
    assertEquals(TypeUtils.cloneFnFor(tpe, index), none[String])
    assertEquals(TypeUtils.resolveNativeType(tpe, index), scalar.typeSpec)
    assert(!TypeUtils.isPointerLike(tpe, index))
    assert(TypeUtils.isHeapType(refTo(heap), index))
  }

  test("missing identities never fall back to same-name declarations") {
    val heap  = nominal("stdlib", "String", NativePointer(synthetic, "i8", MemEffect.Alloc.some))
    val index = ResolvablesIndex().updatedType(heap)
    List(none[String], "missing".some).foreach { id =>
      val tpe = TypeRef(synthetic, heap.name, id)
      assertEquals(TypeUtils.declaration(tpe, index), none[ResolvableType])
      assert(!TypeUtils.requiresDestruction(tpe, index))
      assertEquals(TypeUtils.resolveNativeType(tpe, index), none[NativeType])
      assertEquals(TypeUtils.freeFnFor(tpe, index), none[String])
      assertEquals(TypeUtils.cloneFnFor(tpe, index), none[String])
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
      assert(TypeUtils.requiresDestruction(ref, index))
      assert(TypeUtils.isStructWithHeapFields(ref, index))
      assert(TypeUtils.containsFunction(ref, index))
      assertEquals(TypeUtils.cloneFnFor(ref, index), none[String])
    }
    val scalarSecond = second.copy(fields = second.fields.take(1))
    val scalarIndex  = index.updatedType(scalarSecond)
    assert(!TypeUtils.requiresDestruction(firstRef, scalarIndex))
    assert(!TypeUtils.containsFunction(firstRef, scalarIndex))
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
    assertEquals(TypeUtils.canonical(refTo(a), index), none[Type])
    assertEquals(TypeUtils.resolveNativeType(refTo(a), index), none[NativeType])
    assert(!TypeUtils.requiresDestruction(refTo(a), index))
    assertEquals(TypeUtils.freeFnFor(refTo(a), index), none[String])
    assertEquals(TypeUtils.cloneFnFor(refTo(a), index), none[String])
  }

  private def memoryBinding(owner: String, name: String): Bnd =
    Bnd(
      source   = synthetic,
      nameNode = Name.synth(name),
      value    = Expr(synthetic, Nil),
      id       = BindingIds.declaration(owner, name).some
    )

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
    assertEquals(MemoryFunctions.clone(refTo(first), index).map(_.id), firstClone.id)
    assertEquals(MemoryFunctions.free(refTo(alias), index).map(_.id), secondFree.id)
    assertEquals(MemoryFunctions.clone(refTo(alias), index).map(_.id), secondClone.id)
    assertEquals(DestructionTargets.forType(refTo(alias), index), secondFree.id)
    val missing = index.copy(resolvables = index.resolvables -- secondFree.id -- secondClone.id)
    assertEquals(MemoryFunctions.free(refTo(alias), missing), none[MemoryFunctions.Target])
    assertEquals(MemoryFunctions.clone(refTo(alias), missing), none[MemoryFunctions.Target])
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
    assertEquals(MemoryFunctions.clone(struct, index), none[MemoryFunctions.Target])
    assertEquals(MemoryFunctions.free(struct, index, "Other".some), none[MemoryFunctions.Target])
    val free  = memoryBinding("User", "__free_Box")
    val clone = memoryBinding("User", "__clone_Box")
    assertEquals(MemoryFunctions.free(struct, index, "User".some).map(_.id), free.id)
    assertEquals(MemoryFunctions.clone(struct, index, "User".some).map(_.id), clone.id)
    val registered = index.updatedAll(List(free, clone))
    assertEquals(MemoryFunctions.free(struct, registered).map(_.id), free.id)
    assertEquals(MemoryFunctions.clone(struct, registered).map(_.id), clone.id)
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
    assertEquals(MemoryFunctions.clone(refTo(heap), index), none[MemoryFunctions.Target])
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
      assertEquals(TypeUtils.declaration(tpe, index), none[ResolvableType])
      assert(!TypeUtils.requiresDestruction(tpe, index))
      assertEquals(TypeUtils.resolveNativeType(tpe, index), none[NativeType])
      assertEquals(MemoryFunctions.free(tpe, index), none[MemoryFunctions.Target])
      assertEquals(MemoryFunctions.clone(tpe, index), none[MemoryFunctions.Target])
    }
  }

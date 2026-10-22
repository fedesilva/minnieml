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
        assertEquals(TypeUtils.isHeapType("Recursive", index), heap)
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
        val cloneName = TypeUtils.cloneFnFor(fieldType, module.resolvables)
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
      type Handle = @native[t=*i8, mem=heap, free=close_handle];
    """

    semNotFailed(code).map { module =>
      val result = TypeUtils.freeFnFor("Handle", module.resolvables)
      assertEquals(result, Some("close_handle"))
    }

  test("freeFnFor returns convention name when no free attribute"):
    val code = """
      type MyBuf = @native[t=*i8, mem=heap];
    """

    semNotFailed(code).map { module =>
      val result = TypeUtils.freeFnFor("MyBuf", module.resolvables)
      assertEquals(result, Some("__free_MyBuf"))
    }

  test("freeFnFor returns None for non-heap native type"):
    val code = """
      type MyInt = @native[t=i64];
    """

    semNotFailed(code).map { module =>
      val result = TypeUtils.freeFnFor("MyInt", module.resolvables)
      assertEquals(result, None)
    }

  test("freeFnFor returns explicit free for native struct"):
    val code = """
      type MyStr = @native[mem=heap, free=destroy_str] {
        length: Int,
        data: Int
      };
    """

    semNotFailed(code).map { module =>
      val result = TypeUtils.freeFnFor("MyStr", module.resolvables)
      assertEquals(result, Some("destroy_str"))
    }

  test("heap native aliases resolve to the underlying memory functions") {
    val code = """
      type HeapName = String;
      type OtherHeapName = HeapName;
    """

    semNotFailed(code).map { module =>
      assert(TypeUtils.isHeapType("HeapName", module.resolvables))
      assert(TypeUtils.isHeapType("OtherHeapName", module.resolvables))
      assertEquals(TypeUtils.freeFnFor("HeapName", module.resolvables), Some("__free_String"))
      assertEquals(
        TypeUtils.freeFnFor("OtherHeapName", module.resolvables),
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
      assertEquals(TypeUtils.cloneFnFor("HeapName", module.resolvables), Some("__clone_String"))
      assertEquals(
        TypeUtils.cloneFnFor("OtherHeapName", module.resolvables),
        Some("__clone_String")
      )
    }
  }

  test("heap native aliases without explicit free use the underlying type name"):
    val code = """
      type BaseBuf = @native[t=*i8, mem=heap];
      type AliasBuf = BaseBuf;
    """

    semNotFailed(code).map { module =>
      assert(TypeUtils.isHeapType("AliasBuf", module.resolvables))
      assertEquals(TypeUtils.freeFnFor("AliasBuf", module.resolvables), Some("__free_BaseBuf"))
      assertEquals(TypeUtils.cloneFnFor("AliasBuf", module.resolvables), Some("__clone_BaseBuf"))
    }

  test("heap struct aliases resolve to the underlying struct memory functions"):
    val code = """
      struct Person { name: String };
      type PersonAlias = Person;
      type OtherPersonAlias = PersonAlias;
    """

    semNotFailed(code).map { module =>
      assert(TypeUtils.isHeapType("PersonAlias", module.resolvables))
      assert(TypeUtils.isHeapType("OtherPersonAlias", module.resolvables))
      assert(TypeUtils.isStructWithHeapFields("PersonAlias", module.resolvables))
      assert(TypeUtils.isStructWithHeapFields("OtherPersonAlias", module.resolvables))
      assertEquals(TypeUtils.freeFnFor("PersonAlias", module.resolvables), Some("__free_Person"))
      assertEquals(
        TypeUtils.freeFnFor("OtherPersonAlias", module.resolvables),
        Some("__free_Person")
      )
      assertEquals(
        TypeUtils.cloneFnFor("PersonAlias", module.resolvables),
        Some("__clone_Person")
      )
      assertEquals(
        TypeUtils.cloneFnFor("OtherPersonAlias", module.resolvables),
        Some("__clone_Person")
      )
    }

  test("aliases preserve nominal heap types and non-cloneable function fields") {
    val source = """
      type First = @native[t=ptr, mem=heap];
      type Second = @native[t=ptr, mem=heap];
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
      assertEquals(TypeUtils.cloneFnFor("FirstAlias", index), "__clone_First".some)
      assertEquals(TypeUtils.cloneFnFor("SecondAlias", index), "__clone_Second".some)
      assertEquals(TypeUtils.cloneFnFor("Count", index), none[String])
      List("CallbackAlias", "WrapperAlias").foreach { name =>
        assert(TypeUtils.isStructWithHeapFields(name, index))
        assertEquals(TypeUtils.cloneFnFor(name, index), none[String])
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
        assertEquals(TypeUtils.cloneFnFor(name, module.resolvables), none[String], name)
        assert(!TypeUtils.isStructWithHeapFields(name, module.resolvables), name)
      }
    }
  }

package mml.mmlclib.semantic

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

  // Pending migration: Parent TypeUtils has no sameResolvedTypeName API.
  test("heap native aliases resolve to the underlying memory functions".ignore) {
    // Source body requires the pending compiler API.
    /*:
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
      assert(TypeUtils.sameResolvedTypeName("HeapName", "String", module.resolvables))
      assert(TypeUtils.sameResolvedTypeName("OtherHeapName", "String", module.resolvables))
      assertEquals(TypeUtils.cloneFnFor("HeapName", module.resolvables), Some("__clone_String"))
      assertEquals(
        TypeUtils.cloneFnFor("OtherHeapName", module.resolvables),
        Some("__clone_String")
      )
    }
     */
    fail("Parent TypeUtils has no sameResolvedTypeName API.")
  }

  // Pending migration: Parent does not resolve heap ownership through the native alias.
  test("heap native aliases without explicit free use the underlying type name".ignore):
    val code = """
      type BaseBuf = @native[t=*i8, mem=heap];
      type AliasBuf = BaseBuf;
    """

    semNotFailed(code).map { module =>
      assert(TypeUtils.isHeapType("AliasBuf", module.resolvables))
      assertEquals(TypeUtils.freeFnFor("AliasBuf", module.resolvables), Some("__free_BaseBuf"))
      assertEquals(TypeUtils.cloneFnFor("AliasBuf", module.resolvables), Some("__clone_BaseBuf"))
    }

  // Pending migration: Parent does not resolve heap ownership through the struct aliases.
  test("heap struct aliases resolve to the underlying struct memory functions".ignore):
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

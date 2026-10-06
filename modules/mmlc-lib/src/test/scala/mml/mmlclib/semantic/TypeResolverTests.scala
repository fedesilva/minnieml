package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class TypeResolverTests extends BaseEffFunSuite:

  private def resolvedTypeId(tpe: Option[Type], module: Module): String =
    tpe match
      case Some(ref: TypeRef) =>
        val id = ref.resolvedId.getOrElse(fail(s"Unresolved type: $ref"))
        assert(module.resolvables.lookupType(id).nonEmpty, s"Missing type identity: $id")
        id

      case other => fail(s"Expected a nominal type, got $other")

  test("TypeResolver resolves cached global literal types at the phase boundary"):
    val code = """
      type Number = Int;
      let integer: Number = 30;
      let boolean = true;
      let string = "hello";
      let float = 1.5;
      let computed = 0 + 30;
    """

    parseNotFailed(code).map { parsed =>
      val initial = CompilerState.empty(
        injectBasicTypes(parsed),
        SourceInfo(code),
        CompilerConfig.default
      )
      val resolved = TypeResolver.rewriteModule(IdAssigner.rewriteModule(initial))
      assertEquals(resolved.errors, Vector.empty)

      val bindings = resolved.module.members.collect { case b: Bnd => b }
      bindings.filterNot(_.name == "computed").foreach { binding =>
        val bindingType = resolvedTypeId(binding.typeSpec, resolved.module)
        assertEquals(resolvedTypeId(binding.value.typeSpec, resolved.module), bindingType)
        val literals = TermTraversal.collect(binding.value) { case lit: LiteralValue => lit }
        assertEquals(literals.size, 1)
        assertEquals(resolvedTypeId(literals.head.typeSpec, resolved.module), bindingType)
        assertEquals(resolved.module.resolvables.lookup(binding.id.get), Some(binding))
      }

      val integer = bindings.find(_.name == "integer").get
      val alias = resolved.module.members.collectFirst {
        case a: TypeAlias if a.name == "Number" => a
      }.get
      assertEquals(resolvedTypeId(integer.typeAsc, resolved.module), alias.id.get)

      val computed = bindings.find(_.name == "computed").get
      assertEquals(computed.typeSpec, None)
      assertEquals(computed.value.typeSpec, None)
    }

  private val literalCases = List(
    ("inferred integer", "let value = 30;", "int_to_str value"),
    ("Int annotation", "let value: Int = 30;", "int_to_str value"),
    ("Int32 annotation", "let value: Int32 = 30;", "int_to_str value"),
    ("term annotation", "let value = 30: Int32;", "int_to_str value"),
    ("arithmetic initializer", "let value = 0 + 30;", "int_to_str value"),
    ("boolean", "let value = true;", "if value then \"yes\"; else \"no\";"),
    ("string", "let value = \"hello\";", "value"),
    ("float", "let value = 1.5;", "int_to_str (float_to_int value)")
  )

  literalCases.foreach { (label, declaration, use) =>
    List(true, false).foreach { forward =>
      val order = if forward then "before" else "after"
      test(s"global $label references $order its declaration have resolved types"):
        val reader = s"fn read(): String = $use;;"
        val code =
          if forward then s"$reader\n$declaration"
          else s"$declaration\n$reader"

        semNotFailed(code).map { module =>
          val binding = module.members.collectFirst { case b: Bnd if b.name == "value" => b }.get
          val readerBinding = module.members.collectFirst {
            case b: Bnd if b.name == "read" => b
          }.get
          val refs = TermTraversal.collect(readerBinding.value) {
            case ref: Ref if ref.resolvedId == binding.id => ref
          }
          assert(refs.nonEmpty)
          val bindingType = resolvedTypeId(binding.typeSpec, module)
          refs.foreach(ref => assertEquals(resolvedTypeId(ref.typeSpec, module), bindingType))
        }
    }
  }

  test("global literal annotations still reject incompatible types"):
    semFailed("fn read(): Int = value;; let value: Bool = 30;")

  test("TypeResolver should resolve simple type references in bindings"):
    val code = """
      let x: Int = 42;
    """

    semNotFailed(code).map { module =>
      // Find the binding (skip stdlib injected bindings)
      val binding = module.members.collectFirst {
        case b: Bnd if b.name == "x" => b
      }.get

      // Check that the type ascription has been resolved
      clue(binding.typeAsc) match
        case Some(TypeRef(_, "Int", resolvedId, _)) =>
          assert(clue(resolvedId).isDefined, "Expected TypeRef to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(clue(resolved.map(_.name)), Some("Int"))
        case other =>
          fail(s"Expected TypeRef with resolved type, got: ${clue(other)}")
    }

  test("TypeResolver should resolve type references in function parameters"):
    val code = """
      fn greet(name: String): String = name ++ "!";;
    """

    semNotFailed(code).map { module =>
      // Find the function (now Bnd with Lambda)
      val bnd = module.members.collectFirst {
        case b: Bnd
            if b.meta.exists(_.origin == BindingOrigin.Function) &&
              b.meta.exists(_.originalName == "greet") =>
          b
      }.get

      // Extract lambda params
      val lambda = bnd.value.terms.head.asInstanceOf[Lambda]

      // Check that the parameter type has been resolved
      lambda.params.head.typeAsc match
        case Some(TypeRef(_, "String", resolvedId, _)) =>
          assert(clue(resolvedId.isDefined), "Expected TypeRef to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(clue(resolved.map(_.name)), Some("String"))
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type references in function return types"):
    val code = """
      fn isTrue(): Bool = true;;
    """

    semNotFailed(code).map { module =>
      // Find the function (now Bnd with Lambda)
      val bnd = module.members.collectFirst {
        case b: Bnd
            if b.meta.exists(_.origin == BindingOrigin.Function) &&
              b.meta.exists(_.originalName == "isTrue") =>
          b
      }.get

      // Extract lambda and check return type
      val lambda = bnd.value.terms.head.asInstanceOf[Lambda]

      // Return type ascription is on lambda or bnd
      val returnTypeAsc = lambda.typeAsc.orElse(bnd.typeAsc)
      returnTypeAsc match
        case Some(TypeRef(_, "Bool", resolvedId, _)) =>
          assert(resolvedId.isDefined, "Expected TypeRef to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(resolved.map(_.name), Some("Bool"))
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type aliases"):
    val code = """      
      type TestNumber = Int64;
      let x: TestNumber = int_to_int64 42;
    """

    semNotFailed(code).map { module =>
      // Find the TestNumber type alias
      val typeAlias = module.members.collectFirst {
        case t: TypeAlias if t.name == "TestNumber" => t
      }.get

      // Check that the type reference in the alias has been resolved
      typeAlias.typeRef match
        case TypeRef(_, "Int64", resolvedId, _) =>
          assert(resolvedId.isDefined, "Expected Int64 to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(resolved.map(_.name), Some("Int64"))
        case _ =>
          fail("Expected TypeRef with resolved type")

      // Find the binding (skip stdlib injected bindings)
      val binding = module.members.collectFirst {
        case b: Bnd if b.name == "x" => b
      }.get

      // Check that the binding's type has been resolved to the alias
      clue(binding.typeAsc) match
        case Some(TypeRef(_, "TestNumber", resolvedId, _)) =>
          assert(clue(resolvedId).isDefined, "Expected TypeRef to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(clue(resolved.map(_.name)), Some("TestNumber"))
        case other =>
          fail(s"Expected TypeRef with resolved type, got: ${clue(other)}")
    }

  test("TypeResolver should report undefined type references"):
    val code = """
      let x: Unknown = 42;
    """

    semFailed(code)

  test("TypeResolver should resolve type alias chains to MML types, not native types"):
    val code = """
      type X = Int;
      let test: X = 42;
    """

    semNotFailed(code).map { module =>

      // Find the X type alias
      val typeAlias = module.members.collectFirst {
        case t: TypeAlias if t.name == "X" => t
      }.get

      // Check that the typeSpec resolves to Int32 (the MML type), not @native[t=i32]
      typeAlias.typeSpec match
        case Some(TypeRef(_, "Int32", resolvedId, _)) =>
          assert(resolvedId.isDefined, "Expected Int32 to be resolved")
          val td =
            resolvedId.flatMap(module.resolvables.lookupType).collect { case t: TypeDef => t }
          assert(td.isDefined, "Expected to resolve to TypeDef")
          assertEquals(td.get.name, "Int32")
          // Verify that Int32 itself has the native type, but that's not propagated to X
          assert(td.get.typeSpec.isDefined, "Int32 should have a native typeSpec")
          td.get.typeSpec match
            case Some(NativePrimitive(_, "i32", _, _)) => // correct
            case other => fail(s"Expected Int32 to have @native[t=i32], got $other")
        case other =>
          fail(s"Expected X to resolve to TypeRef(Int32), got $other")
    }

  test("TypeResolver should resolve type references to struct declarations"):
    val code = """
      struct Person {
        name: String
      };
      let p: Person = ???;
    """

    semNotFailed(code).map { module =>
      val binding = module.members.collectFirst {
        case b: Bnd if b.name == "p" => b
      }.get

      clue(binding.typeAsc) match
        case Some(TypeRef(_, "Person", resolvedId, _)) =>
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assert(
            resolved.exists(_.isInstanceOf[TypeStruct]),
            s"Expected TypeRef to resolve to TypeStruct, got: ${clue(resolved)}"
          )
        case other =>
          fail(s"Expected TypeRef to resolve to TypeStruct, got: ${clue(other)}")
    }

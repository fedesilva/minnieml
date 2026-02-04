package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeResolverTests extends BaseEffFunSuite:

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
      fn greet(name: String): String = name;
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
      fn isTrue(): Bool = true;
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
      let x: TestNumber = 42;
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

      // Check that the typeSpec resolves to Int64 (the MML type), not @native[t=i64]
      typeAlias.typeSpec match
        case Some(TypeRef(_, "Int64", resolvedId, _)) =>
          assert(resolvedId.isDefined, "Expected Int64 to be resolved")
          val td =
            resolvedId.flatMap(module.resolvables.lookupType).collect { case t: TypeDef => t }
          assert(td.isDefined, "Expected to resolve to TypeDef")
          assertEquals(td.get.name, "Int64")
          // Verify that Int64 itself has the native type, but that's not propagated to X
          assert(td.get.typeSpec.isDefined, "Int64 should have a native typeSpec")
          td.get.typeSpec match
            case Some(NativePrimitive(_, "i64", _)) => // correct
            case other => fail(s"Expected Int64 to have @native[t=i64], got $other")
        case other =>
          fail(s"Expected X to resolve to TypeRef(Int64), got $other")
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

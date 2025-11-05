package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeResolverTests extends BaseEffFunSuite:

  test("TypeResolver should resolve simple type references in bindings"):
    val code = """      
      let x: Int = 42;
    """

    semNotFailed(code).map { module =>
      // Find the binding
      val binding = module.members.collectFirst { case b: Bnd => b }.get

      // Check that the type ascription has been resolved
      binding.typeAsc match
        case Some(TypeRef(_, "Int", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeAlias].name, "Int")
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type references in function parameters"):
    val code = """      
      fn greet(name: String): String = name;
    """

    semNotFailed(code).map { module =>
      // Find the function
      val fnDef = module.members.collectFirst { case f: FnDef if f.name == "greet" => f }.get

      // Check that the parameter type has been resolved
      fnDef.params.head.typeAsc match
        case Some(TypeRef(_, "String", resolvedAs)) =>
          assert(clue(resolvedAs.isDefined), "Expected TypeRef to be resolved")
          assertEquals(clue(resolvedAs.get.asInstanceOf[TypeDef].name), "String")
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type references in function return types"):
    val code = """
      fn isTrue(): Bool = true;
    """

    semNotFailed(code).map { module =>
      // Find the function
      val fnDef = module.members.collectFirst { case f: FnDef if f.name == "isTrue" => f }.get

      // Check that the return type has been resolved
      fnDef.typeAsc match
        case Some(TypeRef(_, "Bool", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "Bool")
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
        case TypeRef(_, "Int64", resolvedAs) =>
          assert(resolvedAs.isDefined, "Expected Int64 to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "Int64")
        case _ =>
          fail("Expected TypeRef with resolved type")

      // Find the binding
      val binding = module.members.collectFirst { case b: Bnd => b }.get

      // Check that the binding's type has been resolved to the alias
      binding.typeAsc match
        case Some(TypeRef(_, "TestNumber", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeAlias].name, "TestNumber")
        case _ =>
          fail("Expected TypeRef with resolved type")
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

      // Check that the typeSpec resolves to Int64 (the MML type), not @native:i64
      typeAlias.typeSpec match
        case Some(TypeRef(_, "Int64", Some(td: TypeDef))) =>
          assertEquals(td.name, "Int64")
          // Verify that Int64 itself has the native type, but that's not propagated to X
          assert(td.typeSpec.isDefined, "Int64 should have a native typeSpec")
          td.typeSpec match
            case Some(NativePrimitive(_, "i64")) => // correct
            case other => fail(s"Expected Int64 to have @native:i64, got $other")
        case other =>
          fail(s"Expected X to resolve to TypeRef(Int64), got $other")
    }

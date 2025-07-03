package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeResolverTests extends BaseEffFunSuite:

  test("TypeResolver should resolve simple type references in bindings"):
    val code = """
      type TestInt = @native;
      let x: TestInt = 42;
    """

    semNotFailed(code).map { module =>
      // Find the binding
      val binding = module.members.collectFirst { case b: Bnd => b }.get

      // Check that the type ascription has been resolved
      binding.typeAsc match
        case Some(TypeRef(_, "TestInt", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "TestInt")
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type references in function parameters"):
    val code = """
      type TestString = @native;
      fn greet(name: TestString) = name;
    """

    semNotFailed(code).map { module =>
      // Find the function
      val fnDef = module.members.collectFirst { case f: FnDef => f }.get

      // Check that the parameter type has been resolved
      val param = fnDef.params.head
      param.typeAsc match
        case Some(TypeRef(_, "TestString", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "TestString")
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type references in function return types"):
    val code = """
      type TestBool = @native;
      fn isTrue(): TestBool = true;
    """

    semNotFailed(code).map { module =>
      // Find the function
      val fnDef = module.members.collectFirst { case f: FnDef => f }.get

      // Check that the return type has been resolved
      fnDef.typeAsc match
        case Some(TypeRef(_, "TestBool", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "TestBool")
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type aliases"):
    val code = """
      type TestInt = @native;
      type TestNumber = TestInt;
      let x: TestNumber = 42;
    """

    semNotFailed(code).map { module =>
      // Find the TestNumber type alias (not the injected Int -> Int64 alias)
      val typeAlias = module.members.collectFirst { 
        case t: TypeAlias if t.name == "TestNumber" => t 
      }.get

      // Check that the type reference in the alias has been resolved
      typeAlias.typeRef match
        case TypeRef(_, "TestInt", resolvedAs) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "TestInt")
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

    semFailed(code).map { _ =>
      // The test passes if semFailed succeeds (meaning semantic analysis failed as expected)
    }

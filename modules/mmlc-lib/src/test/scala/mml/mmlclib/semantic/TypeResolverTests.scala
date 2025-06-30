package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeResolverTests extends BaseEffFunSuite:

  test("TypeResolver should resolve simple type references in bindings"):
    val code = """
      type Int = @native;
      let x: Int = 42;
    """

    semNotFailed(code).map { module =>
      // Find the binding
      val binding = module.members.collectFirst { case b: Bnd => b }.get

      // Check that the type ascription has been resolved
      binding.typeAsc match
        case Some(TypeRef(_, "Int", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "Int")
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type references in function parameters"):
    val code = """
      type String = @native;
      fn greet(name: String) = name;
    """

    semNotFailed(code).map { module =>
      // Find the function
      val fnDef = module.members.collectFirst { case f: FnDef => f }.get

      // Check that the parameter type has been resolved
      val param = fnDef.params.head
      param.typeAsc match
        case Some(TypeRef(_, "String", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "String")
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type references in function return types"):
    val code = """
      type Bool = @native;
      fn isTrue(): Bool = true;
    """

    semNotFailed(code).map { module =>
      // Find the function
      val fnDef = module.members.collectFirst { case f: FnDef => f }.get

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
      type Int = @native;
      type Number = Int;
      let x: Number = 42;
    """

    semNotFailed(code).map { module =>
      // Find the type alias
      val typeAlias = module.members.collectFirst { case t: TypeAlias => t }.get

      // Check that the type reference in the alias has been resolved
      typeAlias.typeRef match
        case TypeRef(_, "Int", resolvedAs) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeDef].name, "Int")
        case _ =>
          fail("Expected TypeRef with resolved type")

      // Find the binding
      val binding = module.members.collectFirst { case b: Bnd => b }.get

      // Check that the binding's type has been resolved to the alias
      binding.typeAsc match
        case Some(TypeRef(_, "Number", resolvedAs)) =>
          assert(resolvedAs.isDefined, "Expected TypeRef to be resolved")
          assertEquals(resolvedAs.get.asInstanceOf[TypeAlias].name, "Number")
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

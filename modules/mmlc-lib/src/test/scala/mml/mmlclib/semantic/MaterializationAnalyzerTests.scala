package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class MaterializationAnalyzerTests extends BaseEffFunSuite:

  test("nullary immediate application has the body result type") {
    semNotFailed("let result = ({ 42; } ());").map { module =>
      val result = module.members
        .collectFirst {
          case binding: Bnd if binding.name == "result" =>
            binding
        }
        .getOrElse(fail("Expected result binding"))

      result.typeSpec match
        case Some(resultType: TypeRef) => assertEquals(resultType.name, "Int")
        case other => fail(s"Expected Int, got $other")
    }
  }

  test("nullary immediate application rejects non-Unit arguments") {
    semState("let result = ({ 42; } 1);").map { result =>
      val mismatches = result.errors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }
      assertEquals(mismatches.size, 1)
      val mismatch = mismatches.head
      mismatch.expected match
        case expected: TypeRef => assertEquals(expected.name, "Unit")
        case other => fail(s"Expected Unit parameter, got $other")
      mismatch.actual match
        case actual: TypeRef => assertEquals(actual.name, "Int")
        case other => fail(s"Expected Int argument, got $other")
    }
  }

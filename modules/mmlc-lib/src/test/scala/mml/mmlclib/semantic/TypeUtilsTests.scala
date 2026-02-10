package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeUtilsTests extends BaseEffFunSuite:

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

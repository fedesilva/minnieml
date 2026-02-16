package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class StructCodegenTest extends BaseEffFunSuite:

  test("emits LLVM type definition for struct") {
    val source =
      """
      struct Person {
        name: String,
        age: Int
      };
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("%struct.Person = type { %struct.String, i64 }"))
    }
  }

  test("constructor allocates and stores struct fields") {
    val source =
      """
      struct Person {
        name: String,
        age: Int
      };

      fn makePerson(age: Int): Person =
        Person (int_to_str age) age
      ;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("alloca %struct.Person"))
      assert(llvmIr.contains("getelementptr %struct.Person"))
    }
  }

  test("selection emits extractvalue") {
    val source =
      """
      struct Person {
        name: String,
        age: Int
      };

      fn getAge(p: Person): Int =
        p.age
      ;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("extractvalue %struct.Person"))
    }
  }

  test("native struct constructor allocates and stores fields") {
    val source =
      """
      type Vec2 = @native { x: Float, y: Float };

      fn makeVec(a: Float, b: Float): Vec2 =
        Vec2 a b
      ;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("%struct.Vec2 = type { float, float }"))
      assert(llvmIr.contains("alloca %struct.Vec2"))
      assert(llvmIr.contains("getelementptr %struct.Vec2"))
    }
  }

  test("native struct field access emits extractvalue") {
    val source =
      """
      type Vec2 = @native { x: Float, y: Float };

      fn getX(v: Vec2): Float =
        v.x
      ;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("extractvalue %struct.Vec2"))
    }
  }

  test("constructor does not emit clone calls for heap fields") {
    val source =
      """
      struct Person {
        name: String,
        age: Int
      };

      fn makePerson(age: Int): Person =
        Person (int_to_str age) age
      ;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Extract the __mk_Person function body (mangled with module prefix)
      val mkPersonStart = llvmIr.indexOf("__mk_Person(")
      assert(mkPersonStart >= 0, "Expected __mk_Person function in IR")
      val mkPersonEnd  = llvmIr.indexOf("\n}\n", mkPersonStart)
      val mkPersonBody = llvmIr.substring(mkPersonStart, mkPersonEnd)

      assert(
        !mkPersonBody.contains("__clone_String"),
        s"Constructor should not emit clone calls, got:\n$mkPersonBody"
      )
    }
  }

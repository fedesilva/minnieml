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

      fn makePerson(name: String, age: Int): Person =
        Person name age
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

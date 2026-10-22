package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.llvm.ClosureAssertions

import scala.util.matching.Regex

class LambdaEquivalenceCodegenTest extends BaseEffFunSuite with ClosureAssertions:

  private def countMatches(pattern: String, text: String): Int =
    pattern.r.findAllMatchIn(text).length

  test("direct-only equivalent forms avoid closure materialization") {
    val source =
      """
        fn top_inc(x: Int): Int = x + 1;;

        fn main(): Int =
          fn local_inc(y: Int): Int =
            y + 1;
          ;

          let let_inc = { y: Int -> y + 1; };

          let inline_result = { y: Int -> y + 1; } 10;
          (top_inc 10) + (local_inc 10) + (let_inc 10) + inline_result;
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val directCalls = "call i32 @([^ (]+)\\(i32 10\\)".r
        .findAllMatchIn(mainBody)
        .map(_.group(1))
        .toList

      assertEquals(directCalls.size, 3, mainBody)
      assertEquals(directCalls.distinct.size, 3, mainBody)
      assert(directCalls.contains("test_top_inc"), mainBody)
      directCalls.foreach { target =>
        val body   = functionBodyMatching(llvmIr, s"${Regex.quote(target)}\\(i32 %0\\) #0")
        val result = uniqueMatch(body, "(%[^ ]+) = add i32 %0, 1").group(1)
        assert(body.contains(s"ret i32 $result"), body)
      }
      uniqueMatch(mainBody, "%[^ ]+ = add i32 10, 1")
      assert(
        !llvmIr.contains("__closure_env") &&
          !mainBody.contains("insertvalue { ptr, ptr }") &&
          !mainBody.contains("extractvalue { ptr, ptr }") &&
          """call i32 %[^ (]+\(""".r.findFirstIn(mainBody).isEmpty,
        mainBody
      )
      assertCalls(mainBody, "malloc", 0)
    }
  }

  test("first-class non-capturing values use null-env closure values") {
    val source =
      """
        fn apply(f: Int -> Int, x: Int): Int = f x;;
        fn top_inc(x: Int): Int = x + 1;;

        fn main(): Int =
          let inc = { y: Int -> y + 1; };
          (apply top_inc 10) + (apply inc 10);
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val values =
        s"call i32 @test_apply\\(\\{ ptr, ptr \\} $nullEnvironmentClosure, i32 10\\)".r
          .findAllMatchIn(mainBody)
          .map(_.group("entry"))
          .toList

      assertEquals(values.size, 2, mainBody)
      assertEquals(values.distinct.size, 2, mainBody)
      val wrapperBody = functionBodyMatching(
        llvmIr,
        s"${Regex.quote(values.head)}\\(i32 %0, ptr %1\\) #0"
      )
      val lambdaBody = functionBodyMatching(
        llvmIr,
        s"${Regex.quote(values(1))}\\(i32 %0, ptr %1\\) #0"
      )
      assert(wrapperBody.contains("call i32 @test_top_inc(i32 %0)"), wrapperBody)
      assertCalls(wrapperBody, "test_top_inc", 1)
      val result = uniqueMatch(lambdaBody, "(%[^ ]+) = add i32 %0, 1").group(1)
      assert(lambdaBody.contains(s"ret i32 $result"), lambdaBody)
      val applyBody = functionBody(llvmIr, "test_apply")
      indirectClosureInvocation(applyBody, "%0", "i32", "i32 %1")
      assertCalls(mainBody, "test_apply", 2)
      assertCalls(mainBody, "malloc", 0)
      assert(!llvmIr.contains("__closure_env"), llvmIr)
    }
  }

  test("borrow-capturing first-class values use stack envs and no closure free") {
    val source =
      """
        fn apply(f: Int -> Int, x: Int): Int = f x;;

        fn main(): Int =
          let seed = 10;
          let add_seed = { y: Int -> y + seed; };
          apply add_seed 7;
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        """%struct\.__closure_env_\d+ = type \{ i32 \}""".r.findFirstIn(llvmIr).nonEmpty,
        s"Expected one-field borrow env type. IR:\n$llvmIr"
      )
      assert(
        mainBody.contains("alloca %struct.__closure_env_"),
        s"Expected borrow-capturing closure env on the stack. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call ptr @malloc") &&
          !mainBody.contains("call void @test___free_closure") &&
          !mainBody.contains("call void @test___free___closure_env_"),
        s"Borrow-capturing first-class values should not be freed. Body:\n$mainBody"
      )
    }
  }

  test("move-capturing first-class values use heap envs with one cleanup path") {
    val source =
      """
        fn apply(f: Int -> Int, x: Int): Int = f x;;

        fn main(): Int =
          let seed = 10;
          let add_seed = ~{ y: Int -> y + seed; };
          apply add_seed 7;
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")
      val directEnvFrees =
        countMatches("""call void @test___free___closure_env_\d+\(ptr %\d+\)""", mainBody)

      assert(
        """%struct\.__closure_env_\d+ = type \{ ptr, i32 \}""".r.findFirstIn(llvmIr).nonEmpty,
        s"Expected move env type with destructor field. IR:\n$llvmIr"
      )
      assert(
        mainBody.contains("call ptr @malloc"),
        s"Expected move-capturing closure env on the heap. Body:\n$mainBody"
      )
      assertEquals(
        directEnvFrees,
        1,
        s"Expected exactly one known-env cleanup call. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void @test___free_closure"),
        s"Known local move env should not route through universal closure free. Body:\n$mainBody"
      )
    }
  }

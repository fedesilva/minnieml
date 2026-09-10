package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class LambdaEquivalenceCodegenTest extends BaseEffFunSuite:

  private def countMatches(pattern: String, text: String): Int =
    pattern.r.findAllMatchIn(text).length

  // Pending migration: Parent emits closure entries; these assertions require Direct entries and captures.
  test("direct-only equivalent forms avoid closure materialization".ignore) {
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
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        mainBody.contains("call i64 @test_top_inc(i64 10)"),
        s"Expected direct top-level call. Body:\n$mainBody"
      )
      assert(
        """call i64 @test_local_inc_\d+\(i64 10\)""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected direct local function call. Body:\n$mainBody"
      )
      assert(
        """call i64 @test_let_inc_\d+\(i64 10\)""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected direct let-bound lambda call. Body:\n$mainBody"
      )
      assert(
        !llvmIr.contains("__closure_env") &&
          !mainBody.contains("insertvalue { ptr, ptr }") &&
          !mainBody.contains("extractvalue { ptr, ptr }") &&
          !mainBody.contains("call ptr @malloc") &&
          """call i64 %\d+\(""".r.findFirstIn(mainBody).isEmpty,
        s"Direct-only forms should not materialize closure values. Body:\n$mainBody"
      )
    }
  }

  // Pending migration: Parent uses anonymous eta wrappers instead of named closure entries.
  test("first-class non-capturing values use null-env closure values".ignore) {
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
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        llvmIr.contains("define internal i64 @test_top_inc__closure_entry"),
        s"Expected named closure-entry wrapper for top_inc. IR:\n$llvmIr"
      )
      assert(
        mainBody.contains("{ ptr @test_top_inc__closure_entry, ptr null }"),
        s"Expected top_inc to pass as a null-env closure. Body:\n$mainBody"
      )
      assert(
        """\{ ptr @test_inc_\d+, ptr null \}""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected let-bound non-capturing lambda env slot to be null. Body:\n$mainBody"
      )
      assert(
        !llvmIr.contains("__closure_env") && !mainBody.contains("call ptr @malloc"),
        s"Non-capturing function values should not allocate envs. Body:\n$mainBody"
      )
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
        """%struct\.__closure_env_\d+ = type \{ i64 \}""".r.findFirstIn(llvmIr).nonEmpty,
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
        """%struct\.__closure_env_\d+ = type \{ ptr, i64 \}""".r.findFirstIn(llvmIr).nonEmpty,
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

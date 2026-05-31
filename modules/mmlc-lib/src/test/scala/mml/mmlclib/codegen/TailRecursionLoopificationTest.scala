package mml.mmlclib.codegen

import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

class TailRecursionLoopificationTest extends BaseEffFunSuite:

  private def functionBody(llvmIr: String, signaturePattern: String): String =
    val pattern = (s"(?s)define .*@$signaturePattern \\{\\n(.*?)\\n\\}").r
    pattern
      .findFirstMatchIn(llvmIr)
      .map(_.group(1))
      .getOrElse(fail(s"Missing function definition for $signaturePattern. IR:\n$llvmIr"))

  test("emits tail-recursive function as a loop") {
    val source =
      """
      fn sum(i: Int, acc: Int): Int =
        if i < 10 then
          sum (i + 1) (acc + i);
        else
          acc;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
    }
  }

  test("top-level loopified function uses the plain direct-call ABI") {
    val source =
      """
      fn sum(i: Int, acc: Int): Int =
        if i < 10 then
          sum (i + 1) (acc + i);
        else
          acc;
        ;
      ;

      fn main(): Int = sum 0 0;;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")

      assert(
        llvmIr.contains("define internal i64 @test_sum(i64 %0, i64 %1) #0"),
        s"Loopified top-level function should not accept a closure env. IR:\n$llvmIr"
      )
      assert(
        !llvmIr.contains("define internal i64 @test_sum(i64 %0, i64 %1, ptr %2) #0"),
        s"Loopified top-level function must not use closure-entry ABI. IR:\n$llvmIr"
      )
      assert(
        mainBody.contains("call i64 @test_sum(i64 0, i64 0)") &&
          !mainBody.contains("call i64 @test_sum(i64 0, i64 0, ptr null)"),
        s"Direct caller should pass only user arguments. Body:\n$mainBody"
      )
    }
  }

  test("named loopified function used as a value keeps a closure-entry wrapper") {
    val source =
      """
      fn apply(f: Int -> Int): Int = f 41;;

      fn down(x: Int): Int =
        if x == 0 then
          0;
        else
          down (x - 1);
        ;
      ;

      fn main(): Int = (apply down) + (down 3);;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val applyBody   = functionBody(llvmIr, "test_apply\\(\\{ ptr, ptr \\} %0\\) #0")
      val mainBody    = functionBody(llvmIr, "test_main\\(\\) #0")
      val wrapperName = "test_down__closure_entry"
      val wrapperBody = functionBody(llvmIr, s"$wrapperName\\(i64 %0, ptr %1\\) #0")

      assert(
        llvmIr.contains("define internal i64 @test_down(i64 %0) #0"),
        s"Direct loopified function should use plain ABI. IR:\n$llvmIr"
      )
      assert(
        wrapperBody.contains("call i64 @test_down(i64 %0)"),
        s"Wrapper should forward to the plain direct symbol. Body:\n$wrapperBody"
      )
      assert(
        """call i64 %\d+\(i64 41, ptr %\d+\)""".r.findFirstIn(applyBody).nonEmpty,
        s"Higher-order call should still pass the extracted env. Body:\n$applyBody"
      )
      assert(
        mainBody.contains(s"call i64 @test_apply({ ptr, ptr } { ptr @$wrapperName, ptr null })") &&
          mainBody.contains("call i64 @test_down(i64 3)") &&
          !mainBody.contains("call i64 @test_down(i64 3, ptr null)"),
        s"main should use both the wrapper value and the direct plain call. Body:\n$mainBody"
      )
    }
  }

  test("local non-capturing loopified Direct function emits no closure-entry wrapper") {
    val source =
      """
      pub fn main() =
        let factorial_tco: Int -> Int -> Int =
          { n: Int, acc: Int ->
            if n <= 1 then acc;
            else factorial_tco (n - 1) (acc * n);
          }
        ;

        let fac = { n: Int -> factorial_tco n 1 };

        fac 3;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val directMatch =
        """define internal i64 @(test_factorial_tco_\d+)\(i64 %0, i64 %1\) #0 \{""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing plain direct factorial_tco entry. IR:\n$llvmIr"))
      val directName  = directMatch.group(1)
      val wrapperName = s"${directName}__closure_entry"
      val directBody =
        functionBody(llvmIr, s"$directName\\(i64 %0, i64 %1\\) #0")

      assert(
        directBody.contains("loop.header:") && directBody.contains("phi i64"),
        s"Direct factorial_tco entry should be loopified. Body:\n$directBody"
      )
      assert(
        !llvmIr.contains(s"define internal i64 @$directName(i64 %0, i64 %1, ptr %2) #0"),
        s"Direct factorial_tco entry must not accept a closure env. IR:\n$llvmIr"
      )
      assert(
        !llvmIr.contains(wrapperName) && !llvmIr.contains(s"{ ptr @$wrapperName, ptr null }"),
        s"Direct-only local tail-recursive function should not materialize a wrapper. IR:\n$llvmIr"
      )
    }
  }

  test("partial application of local loopified Direct function emits a PAP entry") {
    val source =
      """
      pub fn main(): Int =
        let factorial_tco: Int -> Int -> Int =
          { n: Int, acc: Int ->
            if n <= 1 then acc;
            else factorial_tco (n - 1) (acc * n);
            ;
          }
        ;

        let from5: Int -> Int = factorial_tco 5;

        from5 1;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val directMatch =
        """define internal i64 @(test_factorial_tco_\d+)\(i64 %0, i64 %1\) #0 \{""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing plain Direct factorial_tco entry. IR:\n$llvmIr"))
      val directName = directMatch.group(1)
      val papMatch =
        """define internal i64 @(test_factorial_tco_pap_\d+)\(i64 %0, ptr %1\) #0 \{""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing partial-application entry. IR:\n$llvmIr"))
      val papName  = papMatch.group(1)
      val papBody  = functionBody(llvmIr, s"$papName\\(i64 %0, ptr %1\\) #0")
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")

      assert(
        papBody.contains(s"call i64 @$directName(i64 %") && papBody.contains(", i64 %0)"),
        s"PAP entry should load fixed n and forward acc into Direct entry. Body:\n$papBody"
      )
      assert(
        !llvmIr.contains(s"${directName}__closure_entry"),
        s"Partial application should not materialize the full-arity closure wrapper. IR:\n$llvmIr"
      )
      assert(
        !mainBody.contains(s"call { ptr, ptr } @${directName}__closure_entry"),
        s"main must not reinterpret the scalar closure-entry ABI as a closure pair. Body:\n$mainBody"
      )
      assert(
        mainBody.contains(s"ptr @$papName"),
        s"main should build a closure pair with the PAP entry. Body:\n$mainBody"
      )
    }
  }

  test("escaped partial application of local loopified Direct function uses heap env") {
    val source =
      """
      pub fn main(): Int =
        let factorial_tco: Int -> Int -> Int =
          { n: Int, acc: Int ->
            if n <= 1 then acc;
            else factorial_tco (n - 1) (acc * n);
            ;
          }
        ;

        let make_fac: Int -> (Int -> Int) =
          { n: Int -> factorial_tco n }
        ;

        let f5: Int -> Int = make_fac 5;

        f5 1;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val directMatch =
        """define internal i64 @(test_factorial_tco_\d+)\(i64 %0, i64 %1\) #0 \{""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing plain Direct factorial_tco entry. IR:\n$llvmIr"))
      val makeFacMatch =
        """define internal \{ ptr, ptr \} @(test_make_fac_\d+)\(i64 %0\) #0 \{""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing Direct make_fac entry returning a closure pair. IR:\n$llvmIr"))
      val papMatch =
        """define internal i64 @(test_factorial_tco_pap_\d+)\(i64 %0, ptr %1\) #0 \{""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing returned partial-application entry. IR:\n$llvmIr"))
      val directName  = directMatch.group(1)
      val makeFacName = makeFacMatch.group(1)
      val papName     = papMatch.group(1)
      val makeFacBody = functionBody(llvmIr, s"$makeFacName\\(i64 %0\\) #0")
      val papBody     = functionBody(llvmIr, s"$papName\\(i64 %0, ptr %1\\) #0")
      val mainBody    = functionBody(llvmIr, "test_main\\(\\) #0")
      val dtorName =
        """store ptr @(test___free_factorial_tco_pap_env_\d+), ptr %\d+""".r
          .findFirstMatchIn(makeFacBody)
          .map(_.group(1))
          .getOrElse(fail(s"Missing PAP env destructor store. Body:\n$makeFacBody"))
      val dtorBody = functionBody(llvmIr, s"$dtorName\\(ptr %0\\) #0")

      assert(
        """%struct\.test_factorial_tco_pap_env_\d+ = type \{ ptr, i64 \}""".r
          .findFirstIn(llvmIr)
          .nonEmpty,
        s"Returned PAP env should use slot 0 for the destructor and slot 1 for n. IR:\n$llvmIr"
      )
      assert(
        papBody.contains("i32 0, i32 1") &&
          !papBody.contains("i32 0, i32 0") &&
          papBody.contains(s"call i64 @$directName(i64 %") &&
          papBody.contains(", i64 %0)"),
        s"PAP entry should load fixed n from field 1 and forward acc into Direct entry. Body:\n$papBody"
      )
      assert(
        dtorBody.contains("call void @mml_free_raw(ptr %0)"),
        s"PAP env destructor should free the raw env pointer. Body:\n$dtorBody"
      )
      assert(
        makeFacBody.contains("call ptr @malloc(i64 16)") &&
          !makeFacBody.contains("alloca %struct.test_factorial_tco_pap_env_"),
        s"Returned PAP env must not point at make_fac stack storage. Body:\n$makeFacBody"
      )
      assert(
        makeFacBody.contains(s"store ptr @$dtorName") &&
          makeFacBody.contains("i32 0, i32 0") &&
          makeFacBody.contains("i32 0, i32 1") &&
          makeFacBody.contains("store i64 %0"),
        s"Returned PAP env should store a destructor in field 0. Body:\n$makeFacBody"
      )
      assert(
        makeFacBody.contains(s"ptr @$papName"),
        s"make_fac should return a real closure pair backed by the generated PAP entry. Body:\n$makeFacBody"
      )
      assert(
        (s"""(?s).*call \\{ ptr, ptr \\} @$makeFacName\\(i64 5\\).*""" +
          """%\d+ = call i64 %\d+\(i64 1, ptr %\d+\).*""" +
          """%\d+ = extractvalue \{ ptr, ptr \} %\d+, 1.*""" +
          """call void @test___free_closure\(ptr %\d+\).*""").r
          .matches(mainBody),
        s"Caller should call the returned PAP and then drop its env. Body:\n$mainBody"
      )
    }
  }

  test("local capturing loopified Direct function uses trailing captures") {
    val source =
      """
      pub fn main(): Int =
        let step = 2;
        let loop: Int -> Int -> Int =
          { n: Int, acc: Int ->
            if n <= 0 then acc;
            else loop (n - 1) (acc + step);
            ;
          }
        ;

        loop 3 0;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val directMatch =
        """define internal i64 @(test_loop_\d+)\(i64 %0, i64 %1, i64 %2\) #0 \{""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing loopified direct loop entry with capture param. IR:\n$llvmIr"))
      val directName = directMatch.group(1)
      val directBody =
        functionBody(llvmIr, s"$directName\\(i64 %0, i64 %1, i64 %2\\) #0")

      assert(
        directBody.contains("loop.header:") && directBody.contains("phi i64"),
        s"Capturing Direct loop entry should be loopified. Body:\n$directBody"
      )
      assert(
        !llvmIr.contains(s"define internal i64 @$directName(i64 %0, i64 %1, ptr %2) #0"),
        s"Capturing Direct loop entry must not accept a closure env. IR:\n$llvmIr"
      )
      assert(
        !directBody.contains(s"call i64 @$directName"),
        s"Loopified body should jump through the loop header, not recurse. Body:\n$directBody"
      )
      assert(
        !llvmIr.contains("__closure_env") && !llvmIr.contains("malloc"),
        s"Capturing Direct loop entry should use trailing captures, not env allocation. IR:\n$llvmIr"
      )
    }
  }

  test("emits tail-recursive function with pre-statements as a loop") {
    val source =
      """
      fn loop(i: Int, to: Int): Unit =
        println (int_to_str i);
        if i <= to then
          loop (i + 1) to;
        else
          ();
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
      assert(llvmIr.contains("call void @println"))
    }
  }

  test("emits elif chain tail recursion as a loop") {
    val source =
      """
      fn find(i: Int, limit: Int): Int =
        if i > limit then 0;
        elif i == 42 then i;
        else find (i + 1) limit;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
    }
  }

  test("emits tail-recursive function with let binding as a loop") {
    val source =
      """
      fn count(i: Int, acc: Int): Int =
        if i < 10 then
          let next = i + 1;
          count next (acc + i);
        else
          acc;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
    }
  }

  test("emits both-branches-recursive tail recursion as a loop") {
    val source =
      """
      fn walk(i: Int, n: Int): Int =
        if i >= n then 0;
        elif i == 42 then walk (i + 2) n;
        else walk (i + 1) n;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(
        llvmIr.split("br label %loop.header").length >= 3,
        "expected at least 2 back edges"
      )
      assert(!llvmIr.contains("call i64 @walk"))
    }
  }

  test("emits nested both-branches-recursive with different args") {
    val source =
      """
      fn search(i: Int, step: Int, limit: Int): Int =
        if i >= limit then
          i;
        else
          if step > 5 then
            search (i + step) 1 limit;
          else
            search (i + 1) (step + 1) limit;
          ;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(
        llvmIr.split("br label %loop.header").length >= 3,
        "expected at least 2 back edges"
      )
      assert(!llvmIr.contains("call i64 @search"))
    }
  }

  test("emits deep nested conditionals mixing exits and calls") {
    val source =
      """
      fn deep(x: Int, y: Int): Int =
        if x <= 0 then
          y;
        else
          if y > 10 then
            if x > 5 then
              deep (x - 2) y;
            else
              999;
            ;
          else
            deep (x - 1) (y + 1);
          ;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
      assert(!llvmIr.contains("call i64 @deep"))
    }
  }

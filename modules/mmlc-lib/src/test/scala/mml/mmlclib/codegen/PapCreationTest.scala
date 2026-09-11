package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class PapCreationTest extends BaseEffFunSuite:

  test("consuming PAP creation transfers an owned argument without a clone") {
    compileAndGenerate("""
      fn take(~text: String, n: Int): Int = text.length + n;;
      pub fn main(): Int =
        let text = int_to_str 123;
        let p = take text;
        p 1;
      ;
    """).map { ir =>
      assert(
        !ir.linesIterator.exists(line => line.contains("call") && line.contains("__clone_")),
        ir
      )
      assert(ir.contains("store ptr @mml_free_raw"), ir)
    }
  }

  test("scalar PAP cleanup preserves tail recursion") {
    compileAndGenerate("""
      fn add(a: Int, b: Int): Int = a + b;;
      fn loop(n: Int, result: Int): Int =
        if n == 0 then result;
        else
          let p = add n;
          let r = p 1;
          loop (n - 1) (result + r);
        ;
      ;
    """).map { ir =>
      val body = functionBody(ir, "test_loop")
      assert(body.contains("loop.header"), body)
      assert(!body.contains("call i64 @test_loop"), body)
      assert(body.contains("call void @test___free_"), body)
    }
  }

  test("supplied PAP arguments run once at creation in source order") {
    val source =
      """
        fn first(): Int = println "first"; 10;;
        fn second(): Int = println "second"; 20;;
        fn add(a: Int, b: Int, c: Int): Int = a + b + c;;

        pub fn main(): Int =
          let p = add (first ()) (second ());
          println "created";
          (p 1) + (p 2);
        ;
      """

    compileAndGenerate(source).map { ir =>
      val body        = functionBody(ir, "test_main")
      val firstCalls  = body.linesIterator.filter(_.contains("call i64 @test_first(")).toList
      val secondCalls = body.linesIterator.filter(_.contains("call i64 @test_second(")).toList
      assertEquals(firstCalls.size, 1, body)
      assertEquals(secondCalls.size, 1, body)
      assert(body.indexOf(firstCalls.head) < body.indexOf(secondCalls.head), body)
      assert(body.indexOf(secondCalls.head) < body.indexOf("call void @println("), body)
      assertEquals(ir.linesIterator.count(_.contains("call i64 @test_first(")), 1, ir)
      assertEquals(ir.linesIterator.count(_.contains("call i64 @test_second(")), 1, ir)
    }
  }

  test("supplied Unit arguments run when an unused PAP is created") {
    val source =
      """
        fn signal(): Unit = println "created";;
        fn after(u: Unit, n: Int): Int = n;;

        pub fn main(): Int =
          let p = after (signal ());
          0;
        ;
      """

    compileAndGenerate(source).map { ir =>
      val body = functionBody(ir, "test_main")
      assertEquals(body.linesIterator.count(_.contains("call void @test_signal(")), 1, body)
      assertEquals(ir.linesIterator.count(_.contains("call void @test_signal(")), 1, ir)
      val afterBody = functionBody(ir, "test_after")
      assert(afterBody.contains("ret i64 %0"), afterBody)
    }
  }

  test("PAP payload preparation preserves preceding argument effects") {
    val source =
      """
        fn first(): Int = println "first"; 1;;
        fn second(): Int = println "second"; 2;;
        fn sum(a: Int, b: Int): Int = a + b;;
        fn apply(n: Int, f: Int -> Int): Int = f n;;

        pub fn main(): Int = apply (first ()) (sum (second ()));;
      """

    compileAndGenerate(source).map { ir =>
      val body   = functionBody(ir, "test_main")
      val first  = body.indexOf("call i64 @test_first(")
      val second = body.indexOf("call i64 @test_second(")
      assert(first >= 0 && second > first, body)
    }
  }

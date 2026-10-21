package mml.mmlclib.codegen

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.compileIndirectCall
import mml.mmlclib.codegen.emitter.{CodeGenState, compileExpr}
import mml.mmlclib.test.BaseEffFunSuite

class PapCreationTest extends BaseEffFunSuite:

  private def assertCallsOnceInOrder(ir: String, body: String, calls: String*): Unit =

    val positions = calls.map { call =>
      assertEquals(body.linesIterator.count(_.contains(call)), 1, body)
      assertEquals(ir.linesIterator.count(_.contains(call)), 1, ir)
      body.indexOf(call)
    }

    assertEquals(positions, positions.sorted, body)

  for count <- List(1, 3) do
    test(s"indirect emission rejects $count arguments for a binary callable at its call site") {
      val source    = SourceOrigin.Synth
      val integer   = TypeRef(source, "Int")
      val signature = TypeFn(source, NonEmptyList.of(integer, integer), integer)
      val callee    = Ref(source, "callee", typeSpec = signature.some)
      val argument =
        Expr(source, List(LiteralInt(source, 1, integer.some)), typeSpec = integer.some)
      val arguments = List.fill(count)(argument)
      val prefix = arguments.dropRight(1).zipWithIndex.foldLeft[Ref | App](callee) {
        case (fn, (arg, position)) =>
          val remaining = NonEmptyList.fromList(signature.paramTypes.toList.drop(position + 1))
          val result    = remaining.fold[Type](integer)(TypeFn(source, _, integer))
          App(source, fn, arg, typeSpec = result.some)
      }
      val resultType: Type =
        if count == 1 then TypeFn(source, NonEmptyList.one(integer), integer) else integer
      val call = App(source, prefix, argument, typeSpec = resultType.some)

      val result =
        compileIndirectCall(callee, arguments, call, CodeGenState(), Map.empty, compileExpr)
      assertEquals(result.left.toOption.flatMap(_.node), call.some)
      assertEquals(
        result.left.toOption.map(_.message),
        s"Indirect call 'callee' argument count: expected 2, received $count".some
      )
    }

  test("returned callee partial application evaluates each operand once in source order") {
    compileAndGenerate("""
      fn first(): Int = println "first"; 1;;
      fn second(): Int = println "second"; 2;;
      fn make(seed: Int): Int -> Int -> Int = ~{ a: Int, b: Int -> seed + a + b; };;
      pub fn main(): Int = let q = (make (first ())) (second ()); q 3;;
    """).map { ir =>
      val body = functionBody(ir, "test_main")
      assertCallsOnceInOrder(
        ir,
        body,
        "call i32 @test_first(",
        "call i32 @test_second(",
        "call { ptr, ptr } @test_make("
      )
    }
  }

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
      assert(!body.contains("call i32 @test_loop"), body)
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
      val body = expandNativeAdapters(ir, functionBody(ir, "test_main"))
      assertCallsOnceInOrder(ir, body, "call i32 @test_first(", "call i32 @test_second(")
      assert(body.indexOf("call i32 @test_second(") < body.indexOf("call void @println("), body)
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
      assert(afterBody.contains("ret i32 %0"), afterBody)
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
      val body = functionBody(ir, "test_main")
      assertCallsOnceInOrder(ir, body, "call i32 @test_first(", "call i32 @test_second(")
    }
  }

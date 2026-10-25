package mml.mmlclib.codegen

import cats.effect.IO
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.codegen.emitter.CodeGenError
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig}
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.llvm.ClosureAssertions
import munit.Location

import scala.util.matching.Regex

class ClosureCodegenTest extends BaseEffFunSuite with ClosureAssertions:

  private def compileCodegenErrors(
    source: String,
    name:   String = "Test",
    config: CompilerConfig
  ): IO[List[CodeGenError]] =
    FrontEndApi.compile(source, name, config).value.flatMap {
      case Right(state) =>
        if state.errors.nonEmpty then fail(s"Compilation failed before codegen: ${state.errors}")
        else
          val validated = CodegenStage.validate(state)
          if validated.hasErrors then fail(s"Validation failed: ${validated.errors}")
          else
            CodegenStage.emitIrOnly(validated).map { codegenState =>
              codegenState.errors.collect { case error: CodeGenError => error }.toList
            }
      case Left(error) =>
        fail(s"Compilation failed: $error")
    }

  private def assertOwnedStringCapture(
    llvmIr:         String,
    cloneCount:     Int,
    producerSymbol: String
  )(using Location): Unit =

    val main     = functionBody(llvmIr, "test_main")
    val expanded = expandNativeAdapters(llvmIr, main)

    assertCalls(expanded, "__clone_String", cloneCount)
    assertCalls(expanded, "__free_String", 0)

    val owned   = assertOwnedEnvironment(llvmIr, main, "%struct.String", "__free_String")
    val capture = owned.capture
    val producer = uniqueMatch(
      main,
      s"${Regex.quote(capture.storedValue)} = call %struct.String @([^ (]+)"
    ).group(1)
    assertCalls(functionBody(llvmIr, producer), producerSymbol, 1)

    val entry = closureEntry(main)
    assertCaptureLifetime(main, capture, entry, owned.destructor)
    assertCalls(expandNativeAdapters(llvmIr, functionBody(llvmIr, entry)), "__free_String", 0)

  test("returned capturing closures free through generated __free_closure body") {
    val source = """
      fn makeAdder(a: Int): Int -> Int =
        ~{ x: Int -> x + a; };
      ;

      fn main(): Int =
        let f = makeAdder 1;
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")
      val freeBody = functionBodyMatching(llvmIr, "test___free_closure\\(ptr %0\\) #\\d+")

      assert(
        mainBody.contains("call void @test___free_closure(ptr %"),
        s"Expected escaped closure cleanup to call the generated __free_closure. Body:\n$mainBody"
      )
      assert(
        freeBody.contains("icmp eq ptr %0, null"),
        s"Expected __free_closure to null-guard non-capturing closures. Body:\n$freeBody"
      )
      assert(
        freeBody.contains("load ptr, ptr %0") && freeBody.contains("call void %") &&
          freeBody.contains("(ptr %0)"),
        s"Expected __free_closure to dispatch through env field 0. Body:\n$freeBody"
      )
      assert(
        !mainBody.contains("call void %"),
        s"Caller should not inline the closure destructor dispatch anymore. Body:\n$mainBody"
      )
    }
  }

  test("local move capturing closures free through their specific env destructor") {
    val source = """
      fn apply(g: Int -> Int, n: Int): Int = g n;;
      fn main(): Int =
        let a = 1;
        let f = ~{ x: Int -> x + a; };
        apply f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        mainBody.contains("call ptr @malloc"),
        s"Expected local move closure to heap-allocate its env. Body:\n$mainBody"
      )
      assert(
        """store ptr @test___free___closure_env_\d+, ptr %\d+""".r
          .findFirstIn(mainBody)
          .nonEmpty,
        s"Expected local move closure to store its env destructor. Body:\n$mainBody"
      )
      assert(
        """call void @test___free___closure_env_\d+\(ptr %\d+\)""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected local move closure cleanup to call its specific env destructor. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void @test___free_closure"),
        s"Known env cleanup should not route through the universal destructor. Body:\n$mainBody"
      )
    }
  }

  test("local borrow capturing closures use alloca and no free") {
    val source = """
      fn apply(g: Int -> Int): Int = g 41;;
      fn main(): Int =
        let a = 1;
        let f = { x: Int -> x + a; };
        apply f;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        """%struct\.__closure_env_\d+ = type \{ i32 \}""".r.findFirstIn(llvmIr).nonEmpty,
        s"Expected borrow closure env type to contain captures only. IR:\n$llvmIr"
      )
      assert(
        mainBody.contains("alloca %struct.__closure_env_"),
        s"Expected borrow closure to use alloca for env. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call ptr @malloc"),
        s"Borrow closure should not heap-allocate env. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void @test___free_"),
        s"Borrow closure should not generate free calls. Body:\n$mainBody"
      )
    }
  }

  test("move lambda capturing a heap literal frees its clone once") {
    val source = """
      fn main(): Unit =
        let msg = "hello";
        let greet = ~{ println msg; };
        greet ();
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      assertOwnedStringCapture(llvmIr, cloneCount = 1, producerSymbol = "__clone_String")
    }
  }

  test("move lambda capturing an owned String frees it once without cloning") {
    val source = """
      fn main(): Unit =
        let msg = int_to_str 42;
        let greet = ~{ println msg; };
        greet ();
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      assertOwnedStringCapture(llvmIr, cloneCount = 0, producerSymbol = "int_to_str")
    }
  }

  test("move lambda capturing an owned struct frees it through its destructor") {
    val source = """
      struct Pair { a: String, b: String };
      fn main(): Unit =
        let p = Pair "hello" "world";
        let show = ~{ println p.a; };
        show ();
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val main    = functionBody(llvmIr, "test_main")
      val owned   = assertOwnedEnvironment(llvmIr, main, "%struct.Pair", "test___free_Pair")
      val capture = owned.capture

      assertCalls(main, "test___free_Pair", 0)
      val pairBody = expandNativeAdapters(llvmIr, functionBody(llvmIr, "test___free_Pair"))
      assertCalls(pairBody, "__free_String", 2)
      val fields = "extractvalue %struct.Pair %0, ([01])".r
        .findAllMatchIn(pairBody)
        .map(_.group(1))
        .toList
      assertEquals(fields, List("0", "1"))

      val entry = closureEntry(main)
      assertCaptureLifetime(main, capture, entry, owned.destructor)
    }
  }

  test("loopified move lambda frees its heap capture once before the back-edge") {
    val source = """
      fn run(i: Int): Unit =
        if i > 0 then
          let m = int_to_str i;
          let g = ~{ println m; };
          g ();
          run (i - 1);
        ;
      ;
    """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val loop  = functionBody(llvmIr, "test_run")
      val owned = assertOwnedEnvironment(llvmIr, loop, "%struct.String", "__free_String")

      assertCalls(loop, owned.destructor, 1)
      assertCalls(loop, "test_run", 0)
      assertPhiPredecessors(loop)
      assertEquals(phiCount(loop), 1)

      val backEdges = basicBlocks(loop).filter(_.instructions.last == "br label %loop.header")
      val iteration = backEdges.filter(_.body.contains(s"@${owned.destructor}("))
      assertEquals(iteration.length, 1, loop)

      val entry = closureEntry(iteration.head.body)
      assertCalls(loop, entry, 1)
      assertCaptureLifetime(iteration.head.body, owned.capture, entry, owned.destructor)
      assertCalls(expandNativeAdapters(llvmIr, loop), "__free_String", 0)
    }
  }

  test("loopified borrow closures carried across iterations are rejected") {
    val source =
      """
        fn seed(x: Int): Int = x;;

        fn loop(n: Int, current: Int -> Int): Int =
          let next = { x: Int -> x + n; };
          if n == 0 then
            current 1;
          else
            loop (n - 1) next;
          ;
        ;

        fn main(): Int =
          loop 2 seed;
        ;
      """

    compileCodegenErrors(source, config = CompilerConfig.default.copy(noTco = false)).map {
      errors =>
        assert(
          errors.exists(
            _.message.contains("Borrow closure on a loopified path may survive across iterations")
          ),
          s"Expected loopified borrow-closure reuse rejection, got: $errors"
        )
    }
  }

  test("nested local scalar shadowing preserves ordinary recursive calls") {
    val source =
      """
        fn loop(n: Int, acc: Int): Int =
          if n == 0 then
            acc;
          else
            let f = { x: Int -> x + n; };
            fn step(dummy: Int): Int =
              let f = acc + 1;
              loop (n - 1) f;
            ;
            step 0;
          ;
        ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val parameters = uniqueMatch(
        llvmIr,
        "(?m)^define [^\\n]+@test_loop\\(i32 (?<n>%[^ ,]+), i32 (?<acc>%[^ )]+)\\)"
      )
      val loop       = functionBody(llvmIr, "test_loop")
      val invocation = closureInvocation(loop, returnType = "i32", argumentPattern = "i32 0")
      val step       = invocation.group("entry")
      val pair       = closurePair(loop, step)
      val stepBody   = functionBody(llvmIr, step)
      val environment = uniqueMatch(
        llvmIr,
        s"(?m)^define [^\\n]+@${Regex.quote(step)}\\(i32 %[^ ,]+, ptr [^%\\n]*(%[^ )]+)\\)"
      ).group(1)

      assertEquals(invocation.group("pair"), pair.value)
      assertCalls(loop, step, 1)
      assertCalls(stepBody, "test_loop", 1)

      val n   = assertCaptureField(loop, stepBody, "i32", pair.environment, parameters.group("n"))
      val acc = assertCaptureField(loop, stepBody, "i32", pair.environment, parameters.group("acc"))
      assertEquals(n.consumerEnvironment, environment)
      assertEquals(acc.consumerEnvironment, environment)
      assertNotEquals(n.fieldIndex, acc.fieldIndex)

      val increment = uniqueMatch(
        stepBody,
        s"(%[^ ]+) = add i32 ${Regex.quote(acc.loadedValue)}, 1"
      ).group(1)
      val decrement = uniqueMatch(
        stepBody,
        s"(%[^ ]+) = sub i32 ${Regex.quote(n.loadedValue)}, 1"
      ).group(1)
      assert(stepBody.contains(s"@test_loop(i32 $decrement, i32 $increment)"), stepBody)

      assert(!loop.contains("loop.header:"), loop)
      assert(!stepBody.contains("loop.header:"), stepBody)
      assertPhiPredecessors(loop)
    }
  }

  test("loopified borrow closure validation respects lambda parameter shadowing") {
    val source =
      """
        fn loop(n: Int, acc: Int): Int =
          if n == 0 then
            acc;
          else
            let f = { x: Int -> x + n; };
            let g = { f: Int -> f + 1; };
            let next = g acc;
            loop (n - 1) next;
          ;
        ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val loopBody = functionBodyMatching(llvmIr, "test_loop\\(i32 %0, i32 %1\\) #0")

      assert(
        loopBody.contains("call i32 @test_g_"),
        s"Expected the inner lambda param to shadow the outer Direct lambda. Body:\n$loopBody"
      )
    }
  }

  test("capture-site env stores use semantic env names and TBAA tags") {
    val source = """
      fn apply(g: Int -> Int): Int = g 41;;
      fn main(): Int =
        let a = 1;
        let f = ~{ x: Int -> x + a; };
        apply f;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val envName = """%struct\.(__closure_env_\d+) = type \{ ptr, i32 \}""".r
        .findFirstMatchIn(llvmIr)
        .map(_.group(1))
        .getOrElse(fail(s"Expected closure env type definition. IR:\n$llvmIr"))
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        mainBody.contains(s"getelementptr %struct.$envName, ptr %"),
        s"Expected env setup to use semantic env struct name '$envName'. Body:\n$mainBody"
      )
      assert(
        s"""store ptr @test___free_$envName, ptr %\\d+, !tbaa !\\d+""".r
          .findFirstIn(mainBody)
          .nonEmpty,
        s"Expected destructor slot store with TBAA tag. Body:\n$mainBody"
      )
      assert(
        """store i32 (?:%\d+|[-]?\d+), ptr %\d+, !tbaa !\d+""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected capture store with TBAA tag. Body:\n$mainBody"
      )
    }
  }

  test("deferred non-capturing lambda body preserves emitted metadata") {
    final case class EmittedLiteral(value: String, start: Int)

    val source = """
      fn main(): String =
        let f = { x: Int ->
          println "lambda";
          "lambda";
        };
        f 1;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody   = functionBody(llvmIr, "test_main")
      val symbol     = uniqueMatch(mainBody, "call %struct.String @([^ (]+)\\(i32 1\\)").group(1)
      val lambdaBody = functionBody(llvmIr, symbol)
      val constant = uniqueMatch(
        llvmIr,
        "(@[^ ]+) = private constant \\[6 x i8\\] c\"lambda\""
      ).group(1)
      val literals = (
        "(?<bytes>%[^ ]+) = getelementptr \\[6 x i8\\], \\[6 x i8\\]\\* " +
          s"${Regex.quote(constant)}, i64 0, i64 0\\n  " +
          "(?<length>%[^ ]+) = insertvalue %struct.String undef, i32 6, 0\\n  " +
          "(?<value>%[^ ]+) = insertvalue %struct.String \\k<length>, ptr \\k<bytes>, 1"
      ).r
        .findAllMatchIn(lambdaBody)
        .map(literal => EmittedLiteral(value = literal.group("value"), start = literal.start))
        .toList

      assertEquals(literals.size, 2, lambdaBody)
      val printCall = uniqueMatch(
        lambdaBody,
        s"call void @[^ (]+\\(%struct.String ${Regex.quote(literals.head.value)}\\)"
      )
      assertCalls(expandNativeAdapters(llvmIr, printCall.matched), "println", 1)
      assertCalls(expandNativeAdapters(llvmIr, lambdaBody), "println", 1)
      assert(lambdaBody.endsWith(s"ret %struct.String ${literals.last.value}"), lambdaBody)
      assert(printCall.end < literals.last.start, lambdaBody)
    }
  }

  test("deferred lambda body preserves emitted metadata") {
    val source = """
      fn main(): String =
        let f = { x: Int ->
          let text = "lambda";
          let size = { text.length; };
          let length = size ();
          println text;
          text;
        };
        f 1;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody   = functionBody(llvmIr, "test_main")
      val symbol     = uniqueMatch(mainBody, "call %struct.String @([^ (]+)\\(i32 1\\)").group(1)
      val lambdaBody = functionBody(llvmIr, symbol)
      val sizeBody   = functionBody(llvmIr, closureEntry(lambdaBody))
      val capture    = assertCaptureField(lambdaBody, sizeBody, "%struct.String")
      val constant = uniqueMatch(
        llvmIr,
        "(@[^ ]+) = private constant \\[6 x i8\\] c\"lambda\""
      ).group(1)
      val bytes = uniqueMatch(
        lambdaBody,
        s"(%[^ ]+) = getelementptr \\[6 x i8\\], \\[6 x i8\\]\\* " +
          s"${Regex.quote(constant)}, i64 0, i64 0"
      ).group(1)

      uniqueMatch(
        lambdaBody,
        s"${Regex.quote(capture.storedValue)} = insertvalue %struct.String %[^,]+, " +
          s"ptr ${Regex.quote(bytes)}, 1"
      )
      assertCalls(expandNativeAdapters(llvmIr, lambdaBody), "println", 1)
      assert(sizeBody.contains(s"extractvalue %struct.String ${capture.loadedValue}, 0"), sizeBody)
      val tag = captureTbaaTag(lambdaBody, sizeBody, capture, "%struct.String")
      assertEnvironmentMetadata(llvmIr, capture.layout, List(tag -> 0))
    }
  }

  test("recursive capturing lambda rebuilds self closure from env") {
    val source = """
      fn main(): Int =
        fn inc(x: Int): Int = x + 1;;
        fn loop(n: Int): Int =
          if n == 0 then
            0;
          else
            let m = inc n;
            m + (loop (n - 1));
          ;
        ;
        loop 5;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val loopName = closureEntry(mainBody)
      val loopBody = functionBody(llvmIr, loopName)
      val environment = uniqueMatch(
        loopBody,
        "getelementptr %struct\\.[^,]+, ptr (%[^,]+), i32 0, i32 0"
      ).group(1)
      val self = closureValue(loopBody, loopName, environment)

      closureInvocation(
        loopBody,
        returnType      = "i32",
        argumentPattern = "i32 %[^,]+",
        pairPattern     = Regex.quote(self),
        entryPattern    = Regex.quote(loopName)
      )
      assertCalls(loopBody, loopName, 1)
      assert(!loopBody.contains(s"{ ptr @$loopName, ptr null }"), loopBody)
      assertPhiPredecessors(loopBody)
    }
  }

  test("non-recursive named capturing lambda does not rebuild unused self closure") {
    val source = """
      fn main(): Int =
        let seed = 1;
        fn inner(x: Int): Int =
          x + seed;
        ;
        inner 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody  = functionBody(llvmIr, "test_main")
      val innerName = closureEntry(mainBody)
      val innerBody = functionBody(llvmIr, innerName)
      val capture   = assertCaptureField(mainBody, innerBody, "i32")

      assertEquals(capture.storedValue, "1")
      assert(innerBody.contains(s"add i32 %0, ${capture.loadedValue}"), innerBody)
      assertCalls(mainBody, innerName, 1)
      assertCalls(innerBody, innerName, 0)
      assert(!innerBody.contains("insertvalue { ptr, ptr }"), innerBody)
    }
  }

  test("returned capturing closures free through generated __free_closure body [parent c7e9078]") {
    val source = """
      fn makeAdder(a: Int): Int -> Int =
        ~{ x: Int -> x + a; };
      ;

      fn main(): Int =
        let f = makeAdder 1;
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")
      val freeBody = functionBodyMatching(llvmIr, "test___free_closure\\(ptr %0\\) #0")

      assert(
        mainBody.contains("call void @test___free_closure(ptr %"),
        s"Expected escaped closure cleanup to call the generated __free_closure. Body:\n$mainBody"
      )
      assert(
        freeBody.contains("icmp eq ptr %0, null"),
        s"Expected __free_closure to null-guard non-capturing closures. Body:\n$freeBody"
      )
      assert(
        freeBody.contains("load ptr, ptr %0") && freeBody.contains("call void %") &&
          freeBody.contains("(ptr %0)"),
        s"Expected __free_closure to dispatch through env field 0. Body:\n$freeBody"
      )
      assert(
        !mainBody.contains("call void %"),
        s"Caller should not inline the closure destructor dispatch anymore. Body:\n$mainBody"
      )
    }
  }

  test(
    "local move capturing closures free through their specific env destructor [parent c7e9078]"
  ) {
    val source = """
      fn main(): Int =
        let a = 1;
        let f = ~{ x: Int -> x + a; };
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        """call void @test___free___closure_env_\d+\(ptr %\d+\)""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected local move closure cleanup to call its specific env destructor. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void @test___free_closure"),
        s"Known env cleanup should not route through the universal destructor. Body:\n$mainBody"
      )
    }
  }

  test("local borrow capturing closures use alloca and no free [parent c7e9078]") {
    val source = """
      fn main(): Int =
        let a = 1;
        let f = { x: Int -> x + a; };
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        mainBody.contains("alloca %struct.__closure_env_"),
        s"Expected borrow closure to use alloca for env. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call ptr @malloc"),
        s"Borrow closure should not heap-allocate env. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void @test___free_"),
        s"Borrow closure should not generate free calls. Body:\n$mainBody"
      )
    }
  }

  test("loopified borrow closures hoist env alloca to function entry") {
    val source =
      """
        fn loop(n: Int, acc: Int): Int =
          if n == 0 then
            acc;
          else
            let add = { x: Int -> x + n; };
            let next = add acc;
            loop (n - 1) next;
          ;
        ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val loopBody = functionBody(llvmIr, "test_loop")
      val entry    = closureEntry(loopBody)
      val body     = functionBody(llvmIr, entry)
      val capture  = assertCaptureField(loopBody, body, "i32")
      val alloca = uniqueMatch(
        loopBody,
        s"${Regex.quote(capture.ownerEnvironment)} = alloca ${Regex.quote(capture.layout)}"
      )
      val header = loopBody.indexOf("loop.header:")
      val branch = loopBody.indexOf("br label %loop.header")

      assert(header >= 0 && branch > alloca.end && header > branch, loopBody)
      assertEquals("\\balloca\\b".r.findAllIn(loopBody).size, 1, loopBody)
      assertCalls(loopBody, "malloc", 0)
      assertCalls(loopBody, entry, 1)
      assert(body.contains(s"add i32 %0, ${capture.loadedValue}"), body)
      uniqueMatch(
        loopBody,
        s"${Regex.quote(capture.storedValue)} = phi i32 \\[ %0, %entry \\]"
      )
      assertPhiPredecessors(loopBody)
    }
  }

  test("loopified borrow closure validation respects lambda parameter shadowing [parent c7e9078]") {
    val source =
      """
        fn loop(n: Int, acc: Int): Int =
          if n == 0 then
            acc;
          else
            let f = { x: Int -> x + n; };
            let g = { f: Int -> f + 1; };
            let next = g acc;
            loop (n - 1) next;
          ;
        ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val loopBody = functionBodyMatching(llvmIr, "test_loop\\(i32 %0, i32 %1\\) #0")

      assert(
        loopBody.contains("alloca %struct.__closure_env_"),
        s"Expected the inner lambda param to shadow the outer borrow closure. Body:\n$loopBody"
      )
    }
  }

  test("capture-site env stores use semantic env names and TBAA tags [parent c7e9078]") {
    val source = """
      fn main(): Int =
        let a = 1;
        let f = ~{ x: Int -> x + a; };
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val envName = """%struct\.(__closure_env_\d+) = type \{ ptr, i32 \}""".r
        .findFirstMatchIn(llvmIr)
        .map(_.group(1))
        .getOrElse(fail(s"Expected closure env type definition. IR:\n$llvmIr"))
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        mainBody.contains(s"getelementptr %struct.$envName, ptr %"),
        s"Expected env setup to use semantic env struct name '$envName'. Body:\n$mainBody"
      )
      assert(
        s"""store ptr @test___free_$envName, ptr %\\d+, !tbaa !\\d+""".r
          .findFirstIn(mainBody)
          .nonEmpty,
        s"Expected destructor slot store with TBAA tag. Body:\n$mainBody"
      )
      assert(
        """store i32 (?:%\d+|[-]?\d+), ptr %\d+, !tbaa !\d+""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected capture store with TBAA tag. Body:\n$mainBody"
      )
    }
  }

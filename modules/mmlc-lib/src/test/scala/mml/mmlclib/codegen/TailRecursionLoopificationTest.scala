package mml.mmlclib.codegen

import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.llvm.ClosureAssertions
import munit.Location

import scala.util.matching.Regex

class TailRecursionLoopificationTest extends BaseEffFunSuite with ClosureAssertions:

  private def papInvocation(
    body:       String,
    entry:      String,
    value:      String,
    argument:   Int,
    returnType: String = "i32"
  )(using Location): Regex.Match =
    assertCalls(body, entry, 1)
    closureInvocation(
      body,
      returnType      = returnType,
      argumentPattern = s"i32 $argument",
      pairPattern     = Regex.quote(value),
      entryPattern    = Regex.quote(entry)
    )

  private def assertPapDroppedAfterCall(
    ir:         String,
    body:       String,
    entry:      String,
    value:      String,
    argument:   Int,
    returnType: String = "i32"
  )(using Location): Unit =
    val invocation = papInvocation(body, entry, value, argument, returnType)
    val drop = closureInvocation(
      body,
      returnType      = "void",
      argumentPattern = "",
      pairPattern     = Regex.quote(value)
    )
    assert(drop.start > invocation.end, body)
    assertCalls(body, drop.group("entry"), 1)

    val dispatch = functionBody(ir, drop.group("entry"))
    val destructor = uniqueMatch(
      dispatch,
      "(%[^ ]+) = load ptr, ptr (%[^ \\n]+)\\n  call void \\1\\(ptr \\2\\)"
    )
    assert(dispatch.contains(s"icmp eq ptr ${destructor.group(2)}, null"), dispatch)

  private def assertBorrowedPap(ir: String)(using Location): Unit =
    val mainBody = functionBody(ir, "test_main")
    val papName  = closureEntry(mainBody)
    val papBody  = functionBody(ir, papName)
    val capture  = assertCaptureField(mainBody, papBody, "%struct.String")
    val value    = closureValue(mainBody, papName, capture.ownerEnvironment)
    val invoke   = papInvocation(mainBody, papName, value, 0, returnType = "void")
    val created = uniqueMatch(
      mainBody,
      s"${Regex.quote(capture.storedValue)} = call %struct.String @([^ (]+)\\(i32 7\\)"
    )
    val forwarded = uniqueMatch(
      papBody,
      s"call void @([^ (]+)\\(%struct.String ${Regex.quote(capture.loadedValue)}, i32 %0\\)"
    )
    val targetBody = expandNativeAdapters(ir, functionBody(ir, forwarded.group(1)))
    val freed = uniqueMatch(
      mainBody,
      s"call void @([^ (]+)\\(%struct.String ${Regex.quote(capture.storedValue)}\\)"
    )

    assertEquals(capture.fieldIndex, "0")
    assert(ir.contains(s"${capture.layout} = type { %struct.String }"), ir)
    assert(mainBody.contains(s"${capture.ownerEnvironment} = alloca ${capture.layout}"), mainBody)

    assertCalls(expandNativeAdapters(ir, functionBody(ir, created.group(1))), "int_to_str", 1)
    assertCalls(expandNativeAdapters(ir, mainBody), "__free_String", 1)
    assertCalls(expandNativeAdapters(ir, functionBody(ir, freed.group(1))), "__free_String", 1)
    assert(freed.start > invoke.end, mainBody)

    assertCalls(targetBody, "println", 1)
    assertCalls(targetBody, "__free_String", 0)
    assertCalls(papBody, forwarded.group(1), 1)
    assertCalls(expandNativeAdapters(ir, papBody), "__free_String", 0)

    assertCalls(ir, "__clone_String", 0)
    assertCalls(mainBody, "malloc", 0)
    assertCalls(mainBody, "mml_free_raw", 0)
    assertCalls(papBody, "mml_free_raw", 0)

    assertCalls(mainBody, "test___free_closure", 0)
    assertCalls(papBody, "test___free_closure", 0)
    assert(!mainBody.contains("store ptr @") && !papBody.contains("store ptr @"), ir)

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
      assert(llvmIr.contains("phi i32"))
      assert(llvmIr.contains("br label %loop.header"))
      assertPhiPredecessors(functionBody(llvmIr, "test_sum"))
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
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        llvmIr.contains("define internal i32 @test_sum(i32 %0, i32 %1) #0"),
        s"Loopified top-level function should not accept a closure env. IR:\n$llvmIr"
      )
      assert(
        !llvmIr.contains("define internal i32 @test_sum(i32 %0, i32 %1, ptr %2) #0"),
        s"Loopified top-level function must not use closure-entry ABI. IR:\n$llvmIr"
      )
      assert(
        mainBody.contains("call i32 @test_sum(i32 0, i32 0)") &&
          !mainBody.contains("call i32 @test_sum(i32 0, i32 0, ptr null)"),
        s"Direct caller should pass only user arguments. Body:\n$mainBody"
      )
    }
  }

  test("partial application forwards a literal argument to a plain loopified entry") {
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
      val mainBody = functionBody(llvmIr, "test_main")
      val papName  = closureEntry(mainBody)
      val papBody  = functionBody(llvmIr, papName)
      val target   = uniqueMatch(papBody, "call i32 @([^ (]+)\\(i32 5, i32 %0\\)").group(1)
      val loopBody = functionBodyMatching(llvmIr, s"${Regex.quote(target)}\\(i32 %0, i32 %1\\) #0")
      val destructor     = storedDestructor(mainBody)
      val destructorBody = functionBody(llvmIr, destructor)
      val slot           = destructorSlot(mainBody, destructor)
      val layout         = slot.layout
      val environment    = slot.environment
      val value          = closureValue(mainBody, papName, environment)
      val invocation     = papInvocation(mainBody, papName, value, 1)
      val drop = closureInvocation(
        mainBody,
        returnType      = "void",
        argumentPattern = "",
        pairPattern     = Regex.quote(value),
        entryPattern    = Regex.quote(destructor)
      )

      assert(llvmIr.contains(s"$layout = type { ptr }"), llvmIr)
      assert(mainBody.contains(s"$environment = call ptr @malloc(i64 8)"), mainBody)
      assertCalls(mainBody, "malloc", 1)
      assertCalls(mainBody, destructor, 1)
      assert(drop.start > invocation.end, mainBody)

      assertEquals("\\bcall\\b".r.findAllIn(destructorBody).size, 1, destructorBody)
      assert(destructorBody.contains("call void @mml_free_raw(ptr %0)"), destructorBody)

      assertCalls(papBody, target, 1)
      assert(!papBody.contains("load ") && !papBody.contains("extractvalue"), papBody)

      assert(loopBody.contains("loop.header:"), loopBody)
      assertEquals(phiCount(loopBody), 2)
      assertPhiPredecessors(loopBody)
      assertCalls(loopBody, target, 0)
    }
  }

  test("escaped PAP of a local loopified function owns a heap environment") {
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
      val mainBody    = functionBody(llvmIr, "test_main")
      val makeFacName = closureEntry(mainBody)
      val makeFacBody = functionBody(llvmIr, makeFacName)
      val papName     = closureEntry(makeFacBody)
      val papBody     = functionBody(llvmIr, papName)
      val capture     = assertCaptureField(makeFacBody, papBody, "i32")
      val dtorName    = storedDestructor(makeFacBody)
      val dtorBody    = functionBody(llvmIr, dtorName)
      val returned    = closureValue(makeFacBody, papName, capture.ownerEnvironment)
      val directCall = uniqueMatch(
        papBody,
        s"call i32 @([^ (]+)\\(i32 ${Regex.quote(capture.loadedValue)}, i32 %0\\)"
      )
      val directBody = functionBody(llvmIr, directCall.group(1))
      val factoryCapture = uniqueMatch(
        mainBody,
        "(?<field>%[^ ]+) = getelementptr (?<layout>%struct\\.[^,]+), " +
          "ptr (?<environment>%[^,]+), i32 0, i32 0\\n  " +
          s"store \\{ ptr, ptr \\} $nullEnvironmentClosure, ptr \\k<field>"
      )
      val factoryLayout      = factoryCapture.group("layout")
      val factoryEnvironment = factoryCapture.group("environment")
      val capturedAdapter    = factoryCapture.group("entry")
      val factoryValue       = closureValue(mainBody, makeFacName, factoryEnvironment)
      val factoryCall = closureInvocation(
        mainBody,
        returnType      = "{ ptr, ptr }",
        argumentPattern = "i32 5",
        pairPattern     = Regex.quote(factoryValue),
        entryPattern    = Regex.quote(makeFacName)
      )
      val factoryLoad = uniqueMatch(
        makeFacBody,
        s"(%[^ ]+) = getelementptr ${Regex.quote(factoryLayout)}, " +
          "ptr %1, i32 0, i32 0\\n  (%[^ ]+) = load \\{ ptr, ptr \\}, ptr \\1"
      )

      assert(mainBody.contains(s"${factoryEnvironment} = alloca ${factoryLayout}"))
      assert(llvmIr.contains(s"${factoryLayout} = type { { ptr, ptr } }"), llvmIr)
      assertCalls(makeFacBody, "malloc", 1)
      assert(factoryLoad.start < callsTo(makeFacBody, "malloc").head.start, makeFacBody)
      assert(!mainBody.contains("store ptr @"), mainBody)
      assertCalls(mainBody, "malloc", 0)
      assertCalls(mainBody, makeFacName, 1)
      assertCalls(functionBody(llvmIr, capturedAdapter), directCall.group(1), 1)
      assertCalls(papBody, directCall.group(1), 1)
      assert(directBody.contains("loop.header:") && directBody.contains("phi i32"), directBody)
      assertPhiPredecessors(directBody)

      assertEquals(capture.fieldIndex, "1")
      assertEquals(capture.storedValue, "%0")
      assert(llvmIr.contains(s"${capture.layout} = type { ptr, i32 }"), llvmIr)
      assert(makeFacBody.contains(s"${capture.ownerEnvironment} = call ptr @malloc(i64 16)"))
      assert(!makeFacBody.contains("alloca"), makeFacBody)
      assert(makeFacBody.contains(s"ret { ptr, ptr } $returned"), makeFacBody)
      assertCalls(dtorBody, "mml_free_raw", 1)
      assert(dtorBody.contains("call void @mml_free_raw(ptr %0)"), dtorBody)
      assertEquals("\\bcall\\b".r.findAllIn(dtorBody).size, 1, dtorBody)
      assertPapDroppedAfterCall(llvmIr, mainBody, papName, factoryCall.group("result"), 1)

      val destructorStore = destructorSlot(makeFacBody, dtorName)
      val destructorTag = destructorStore.tbaaTag.getOrElse(
        fail(s"Expected destructor-slot TBAA tag:\n$makeFacBody")
      )
      val payloadTag = captureTbaaTag(makeFacBody, papBody, capture, "i32")

      assertEquals(destructorStore.layout, capture.layout)
      assertEquals(destructorStore.environment, capture.ownerEnvironment)
      assertEnvironmentMetadata(
        llvmIr,
        capture.layout,
        List(destructorTag -> 0, payloadTag -> 8)
      )
    }
  }

  test("capturing partial application tags its projected environment pointer") {
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

        let from3: Int -> Int = loop 3;

        from3 0;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val papCall  = uniqueMatch(mainBody, "call i32 @([^ (]+)\\(i32 0, ptr %[^)]+\\)")
      val papName  = papCall.group(1)
      val papBody  = functionBody(llvmIr, papName)
      val capture  = assertCaptureField(mainBody, papBody, "ptr")
      val targetCall = uniqueMatch(
        papBody,
        s"call i32 @([^ (]+)\\(i32 3, i32 %0, ptr ${Regex.quote(capture.loadedValue)}\\)"
      )
      val loopName  = targetCall.group(1)
      val loopBody  = functionBody(llvmIr, loopName)
      val step      = assertCaptureField(mainBody, loopBody, "i32")
      val loopValue = closureValue(mainBody, loopName, step.ownerEnvironment)
      val papValue  = closureValue(mainBody, papName, capture.ownerEnvironment)

      assertEquals(step.storedValue, "2")
      assertEquals(step.fieldIndex, "0")
      assertEquals(capture.fieldIndex, "0")
      assert(llvmIr.contains(s"${step.layout} = type { i32 }"), llvmIr)
      assert(llvmIr.contains(s"${capture.layout} = type { ptr }"), llvmIr)
      assert(mainBody.contains(s"${step.ownerEnvironment} = alloca ${step.layout}"), mainBody)
      assert(mainBody.contains(s"${capture.ownerEnvironment} = alloca ${capture.layout}"), mainBody)
      assert(
        mainBody.contains(s"${capture.storedValue} = extractvalue { ptr, ptr } $loopValue, 1"),
        mainBody
      )
      papInvocation(mainBody, papName, papValue, 0)
      assertCalls(papBody, loopName, 1)
      assertCalls(loopBody, loopName, 0)
      assertCalls(mainBody, "malloc", 0)
      assertCalls(mainBody, "test___free_closure", 0)
      assert(!mainBody.contains("store ptr @"), mainBody)

      val accumulator = uniqueMatch(loopBody, "(%[^ ]+) = phi i32 \\[ %1, %entry \\]").group(1)
      assert(loopBody.contains(s"add i32 $accumulator, ${step.loadedValue}"), loopBody)
      assertEquals(phiCount(loopBody), 2)
      assertPhiPredecessors(loopBody)

      List((capture, papBody, "ptr", "RawPtr"), (step, loopBody, "i32", "Int")).foreach {
        case (field, consumer, llvmType, scalarName) =>
          val tag = captureTbaaTag(mainBody, consumer, field, llvmType)
          val scalar = uniqueMatch(
            llvmIr,
            s"(!\\d+) = !\\{!\"$scalarName\", !\\d+, i64 0\\}"
          ).group(1)
          val node = assertEnvironmentMetadata(llvmIr, field.layout, List(tag -> 0))
          assertEquals(node.scalarNodes, List(scalar))
      }
    }
  }

  test("escaped Direct PAP with borrowed heap capture is rejected by ownership") {
    val source =
      """
      fn make(): Int -> Unit =
        let msg = "hello";
        let say: Int -> Int -> Unit =
          ~{ x: Int, y: Int ->
            println msg;
          }
        ;

        say 1;
      ;

      pub fn main(): Unit =
        let g: Int -> Unit = make ();
        g 2;
      ;
      """

    semState(source).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowClosureEscapeViaReturn]),
        result.errors
      )
    }
  }

  test("PAP borrows a heap applied argument in a stack environment without cloning") {
    val source =
      """
      pub fn main(): Unit =
        let say: String -> Int -> Unit =
          { msg: String, n: Int ->
            println msg;
          }
        ;

        let msg = int_to_str 7;
        let f: Int -> Unit = say msg;

        f 0;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false))
      .map(assertBorrowedPap)
  }

  test("PAP borrows an aliased heap applied argument in a stack environment without cloning") {
    val source =
      """
      type Name = String;

      pub fn main(): Unit =
        let say: Name -> Int -> Unit =
          { msg: Name, n: Int ->
            println msg;
          }
        ;

        let msg: Name = int_to_str 7;
        let f: Int -> Unit = say msg;

        f 0;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false))
      .map(assertBorrowedPap)
  }

  test("PAP transfers its owned heap argument and then releases only its environment") {
    val source =
      """
      pub fn main(): Unit =
        let say: String -> Int -> Unit =
          { ~msg: String, n: Int ->
            println msg;
          }
        ;

        let msg = int_to_str 7;
        let f: Int -> Unit = say msg;

        f 0;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val papName  = closureEntry(mainBody)
      val papBody  = functionBody(llvmIr, papName)
      val capture  = assertCaptureField(mainBody, papBody, "%struct.String")
      val owned    = assertOwnedEnvironment(llvmIr, mainBody, "%struct.String", "__free_String")
      val dtorName = owned.destructor
      val dtorBody = functionBody(llvmIr, dtorName)
      val value    = closureValue(mainBody, papName, capture.ownerEnvironment)
      val created = uniqueMatch(
        mainBody,
        s"${Regex.quote(capture.storedValue)} = call %struct.String @([^ (]+)\\(i32 7\\)"
      )
      val disarmed = uniqueMatch(
        papBody,
        s"store ptr @mml_free_raw, ptr ${Regex.quote(capture.consumerEnvironment)}"
      )
      val forwarded = uniqueMatch(
        papBody,
        s"call void @([^ (]+)\\(%struct.String ${Regex.quote(capture.loadedValue)}, i32 %0\\)"
      )
      val targetBody = functionBody(llvmIr, forwarded.group(1))
      val payloadFree = uniqueMatch(
        dtorBody,
        s"call void @([^ (]+)\\(%struct.String ${Regex.quote(owned.capture.loadedValue)}\\)"
      )
      val targetFree = uniqueMatch(
        targetBody,
        s"call void @${Regex.quote(payloadFree.group(1))}\\(%struct.String %0\\)"
      )
      val targetPrint = uniqueMatch(
        targetBody,
        s"call void @(?!${Regex.quote(payloadFree.group(1))}\\()([^ (]+)\\(%struct.String %0\\)"
      )
      val slot = destructorSlot(mainBody, dtorName)

      assertEquals(capture.fieldIndex, "1")
      assertEquals(slot.layout, capture.layout)
      assertEquals(slot.environment, capture.ownerEnvironment)
      assert(llvmIr.contains(s"${capture.layout} = type { ptr, %struct.String }"), llvmIr)
      assert(mainBody.contains(s"${capture.ownerEnvironment} = call ptr @malloc(i64 24)"))
      assertCalls(mainBody, "malloc", 1)

      val creationBody = expandNativeAdapters(llvmIr, functionBody(llvmIr, created.group(1)))
      assertCalls(creationBody, "int_to_str", 1)
      val payloadStore = mainBody.indexOf(s"store %struct.String ${capture.storedValue}")
      assert(slot.start < payloadStore, mainBody)

      assertCalls(llvmIr, "__clone_String", 0)
      assertCalls(expandNativeAdapters(llvmIr, mainBody), "__free_String", 0)
      assertCalls(expandNativeAdapters(llvmIr, papBody), "__free_String", 0)
      assertCalls(papBody, "mml_free_raw", 0)

      assertEquals(storedDestructor(papBody), "mml_free_raw")
      assert(disarmed.end < forwarded.start, papBody)
      assertCalls(papBody, forwarded.group(1), 1)

      assertCalls(expandNativeAdapters(llvmIr, targetBody), "__free_String", 1)
      assertCalls(expandNativeAdapters(llvmIr, targetBody), "println", 1)
      assertCalls(functionBody(llvmIr, targetPrint.group(1)), "println", 1)
      assert(targetFree.start > targetPrint.end, targetBody)

      assert(
        dtorBody.contains(s"call void @mml_free_raw(ptr ${owned.capture.consumerEnvironment})")
      )
      assertPapDroppedAfterCall(llvmIr, mainBody, papName, value, 0, returnType = "void")
    }
  }

  test("local capturing loopified function loads its step from a stack environment") {
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
      val mainBody = functionBody(llvmIr, "test_main")
      val loopName = closureEntry(mainBody)
      val loopBody = functionBody(llvmIr, loopName)
      val capture  = assertCaptureField(mainBody, loopBody, "i32")
      val value    = closureValue(mainBody, loopName, capture.ownerEnvironment)
      val load = uniqueMatch(
        loopBody,
        s"${Regex.quote(capture.loadedValue)} = load i32, ptr %[^,]+"
      )
      val accumulator = uniqueMatch(loopBody, "(%[^ ]+) = phi i32 \\[ %1, %entry \\]").group(1)

      assertEquals(capture.storedValue, "2")
      assertEquals(capture.fieldIndex, "0")
      assert(llvmIr.contains(s"${capture.layout} = type { i32 }"), llvmIr)
      assert(mainBody.contains(s"${capture.ownerEnvironment} = alloca ${capture.layout}"), mainBody)
      closureInvocation(
        mainBody,
        returnType      = "i32",
        argumentPattern = "i32 3, i32 0",
        pairPattern     = Regex.quote(value),
        entryPattern    = Regex.quote(loopName)
      )
      assert(loopBody.indexOf("loop.header:") > load.end, loopBody)
      assert(loopBody.contains(s"add i32 $accumulator, ${capture.loadedValue}"), loopBody)
      assertEquals(phiCount(loopBody), 2)
      assertPhiPredecessors(loopBody)
      assertCalls(loopBody, loopName, 0)
      assertCalls(mainBody, "malloc", 0)
      assertCalls(mainBody, "test___free_closure", 0)
      assert(!mainBody.contains("store ptr @") && !loopBody.contains("alloca"), llvmIr)
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
      assert(llvmIr.contains("phi i32"))
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
      assert(llvmIr.contains("phi i32"))
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
      assert(llvmIr.contains("phi i32"))
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
      assert(llvmIr.contains("phi i32"))
      assert(
        llvmIr.split("br label %loop.header").length >= 3,
        "expected at least 2 back edges"
      )
      assert(!llvmIr.contains("call i32 @walk"))
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
      assert(llvmIr.contains("phi i32"))
      assert(
        llvmIr.split("br label %loop.header").length >= 3,
        "expected at least 2 back edges"
      )
      assert(!llvmIr.contains("call i32 @search"))
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
      assert(llvmIr.contains("phi i32"))
      assert(llvmIr.contains("br label %loop.header"))
      assert(!llvmIr.contains("call i32 @deep"))
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
      val applyBody = functionBody(llvmIr, "test_apply")
      val mainBody  = functionBody(llvmIr, "test_main")
      val wrapperName = uniqueMatch(
        mainBody,
        s"call i32 @test_apply\\(\\{ ptr, ptr \\} $nullEnvironmentClosure\\)"
      ).group("entry")
      val wrapperBody = functionBodyMatching(
        llvmIr,
        s"${Regex.quote(wrapperName)}\\(i32 %0, ptr %1\\) #0"
      )
      val loopBody = functionBodyMatching(llvmIr, "test_down\\(i32 %0\\) #0")

      assert(wrapperBody.contains("call i32 @test_down(i32 %0)"), wrapperBody)
      assertCalls(wrapperBody, "test_down", 1)
      indirectClosureInvocation(applyBody, "%0", "i32", "i32 41")
      assert(mainBody.contains("call i32 @test_down(i32 3)"), mainBody)
      assertCalls(mainBody, "test_down", 1)
      assert(loopBody.contains("loop.header:"), loopBody)
      assertEquals(phiCount(loopBody), 1)
      assertPhiPredecessors(loopBody)
      assertCalls(loopBody, "test_down", 0)
    }
  }

  test("local non-capturing loopified function materializes a closure-entry wrapper") {
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
      val mainBody = functionBody(llvmIr, "test_main")
      val wrapperName = uniqueMatch(
        mainBody,
        s"store \\{ ptr, ptr \\} $nullEnvironmentClosure"
      ).group("entry")
      val wrapperBody = functionBodyMatching(
        llvmIr,
        s"${Regex.quote(wrapperName)}\\(i32 %0, i32 %1, ptr %2\\) #0"
      )
      val directName = uniqueMatch(
        wrapperBody,
        "call i32 @([^ (]+)\\(i32 %0, i32 %1\\)"
      ).group(1)
      val directBody = functionBodyMatching(
        llvmIr,
        s"${Regex.quote(directName)}\\(i32 %0, i32 %1\\) #0"
      )
      val facBody = functionBody(llvmIr, closureEntry(mainBody))

      assertCalls(wrapperBody, directName, 1)
      assertCalls(facBody, directName, 1)
      assert(facBody.contains(s"call i32 @$directName(i32 %0, i32 1)"), facBody)
      assert(directBody.contains("loop.header:"), directBody)
      assertEquals(phiCount(directBody), 2)
      assertPhiPredecessors(directBody)
      assertCalls(directBody, directName, 0)
      assert(!directBody.contains("load ") && !directBody.contains("alloca"), directBody)
    }
  }

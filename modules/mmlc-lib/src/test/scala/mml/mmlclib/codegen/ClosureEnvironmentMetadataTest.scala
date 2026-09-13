package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

import scala.util.matching.Regex

class ClosureEnvironmentMetadataTest extends BaseEffFunSuite:

  private case class FieldAccess(envType: String, field: Int, tag: String)

  private def captureAccesses(body: String, operation: String): List[FieldAccess] =
    val gep =
      """%(\d+) = getelementptr (%struct\.__closure_env_\d+), ptr %\d+, i32 0, i32 (\d+)""".r
    gep
      .findAllMatchIn(body)
      .map { field =>
        val access = s"(?m)^.*\\b$operation [^\\n]*, ptr %${field.group(1)}, !tbaa (!(\\d+))$$".r
          .findFirstMatchIn(body)
          .getOrElse(fail(s"Missing tagged $operation for ${field.matched}:\n$body"))
        FieldAccess(field.group(2), field.group(3).toInt, access.group(1))
      }
      .toList

  for
    move <- List(false, true)
    tailRecursive <- List(false, true)
  do
    test(s"capture loads match stores and env layout: move=$move, tail=$tailRecursive") {
      val mode = if move then "~" else ""
      val body =
        if tailRecursive then
          "if flag then if n == 0 then seed; else choose (n - 1); ; else seed - n; ;"
        else "if flag then seed + n; else seed - n; ;"
      val source = s"""
        fn main(): Int =
          let flag = true;
          let seed = 42;
          fn ${mode}choose(n: Int): Int = $body;
          choose 2;
        ;
      """

      compileAndGenerate(source).map { ir =>
        val size = if move then 24 else 16
        val body = functionBodyMatching(
          ir,
          s"test_choose_\\d+\\(i64 %0, ptr align 8 dereferenceable\\($size\\) %1\\) #0"
        )
        val stores       = captureAccesses(functionBody(ir, "test_main"), "store")
        val loads        = captureAccesses(body, "load")
        val firstCapture = if move then 1 else 0
        assertEquals(loads.map(_.field), List(firstCapture, firstCapture + 1))
        assertEquals(loads, stores.filter(_.field >= firstCapture))
        assertEquals(loads.map(_.tag).distinct.size, 2)
        loads.foreach { access =>
          assert(ir.linesIterator.exists(_.startsWith(s"${access.tag} = ")))
        }
        assertEquals(body.contains("loop.header:"), tailRecursive)
        if tailRecursive then assertPhiPredecessors(body)
      }
    }

  test("a byte-sized borrow environment uses its actual alignment and size") {
    val source = """
      fn main(): Int =
        let flag = true;
        let f = { u: Unit -> if flag then 42; else 0; ; };
        f ();
      ;
    """

    compileAndGenerate(source).map { ir =>
      val body = functionBodyMatching(
        ir,
        "test_f_\\d+\\(ptr align 1 dereferenceable\\(1\\) %0\\) #0"
      )
      assertEquals(captureAccesses(body, "load").size, 1)
    }
  }

  test("captured function values retain matching aggregate tags and layout") {
    val source = """
      fn main(): Int =
        fn inc(n: Int): Int = n + 1;;
        fn applyInc(n: Int): Int = inc n;;
        applyInc 41;
      ;
    """

    compileAndGenerate(source).map { ir =>
      val body = functionBodyMatching(
        ir,
        "test_applyInc_\\d+\\(i64 %0, ptr align 8 dereferenceable\\(16\\) %1\\) #0"
      )
      val loads  = captureAccesses(body, "load")
      val stores = captureAccesses(functionBody(ir, "test_main"), "store")
      assertEquals(loads.size, 1)
      assertEquals(loads, stores)
      assert(body.contains("load { ptr, ptr }"))
    }
  }

  for move <- List(false, true) do
    test(s"non-capturing closure keeps a plain null-compatible environment: move=$move") {
      val mode = if move then "~" else ""
      val source = s"""
        fn apply(f: Int -> Int): Int = f 41;;
        fn main(): Int =
          let f = $mode{ n: Int -> n + 1; };
          apply f;
        ;
      """

      compileAndGenerate(source).map { ir =>
        val closure = """\{ ptr @([^, ]+), ptr null \}""".r
          .findFirstMatchIn(functionBody(ir, "test_main"))
          .getOrElse(fail(s"Missing null environment closure:\n$ir"))
        functionBodyMatching(ir, s"${Regex.quote(closure.group(1))}\\(i64 %0, ptr %1\\) #0")
      }
    }

  for nested <- List(false, true) do
    test(s"unknown native capture layout keeps a plain environment parameter: nested=$nested") {
      val capturedType = if nested then "Box" else "Half"
      val readCapture  = if nested then "h.value" else "h"
      val source = s"""
        type Half = @native[t=half];
        struct Box { value: Half };
        fn to_int(h: Half): Int = @native[tpl="fptosi half %operand to i64"];;
        fn with_half(h: $capturedType): Int =
          let f = { u: Unit -> to_int $readCapture; };
          f ();
        ;
      """

      compileAndGenerate(source).map { ir =>
        val body = functionBodyMatching(ir, "test_f_\\d+\\(ptr %0\\) #0")
        assertEquals(captureAccesses(body, "load").size, 1)
      }
    }

  test("empty native capture layout keeps a plain environment parameter") {
    val source = """
      type Empty = @native {};
      fn inspect(e: Empty): Int = @native[tpl="add i64 0, 42"];;
      fn with_empty(e: Empty): Int =
        let f = { u: Unit -> inspect e; };
        f ();
      ;
    """

    compileAndGenerate(source).map { ir =>
      val body = functionBodyMatching(ir, "test_f_\\d+\\(ptr %0\\) #0")
      assertEquals(captureAccesses(body, "load").size, 1)
    }
  }

package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class RecursiveCallableOwnershipTests extends BaseEffFunSuite:

  private val increment = "fn increment(x: Int): Int = x + 1;;"

  List(
    "direct" -> "apply_n f (n - 1) (f acc)",
    "alias" -> "let next = f; apply_n next (n - 1) (next acc)"
  ).foreach { (form, step) =>
    test(s"$form recursive callback forwarding accepts an observed target") {
      semNotFailed(s"""
        $increment
        fn apply_n(f: Int -> Int, n: Int, acc: Int): Int =
          if n == 0 then acc; else $step; ;
        ;
        fn example(): Int = apply_n increment 3 0;;
      """).map { module =>
        val reference = callbackRef(module, "apply_n")
        val flow      = CallableValues.fromModule(module)
        assert(flow.hasConsistentParameterOwnership(reference))
        assertEquals(flow.parameters(reference).map(_.consuming), List(false))
        assertEquals(flow.provenLambdas(reference), None)
      }
    }
  }

  test("mutually recursive callback forwarding accepts matching alternatives") {
    semNotFailed(s"""
      $increment
      fn twice(x: Int): Int = x * 2;;
      fn left(f: Int -> Int, n: Int, acc: Int): Int =
        if n == 0 then acc; else right f (n - 1) (f acc); ;
      ;
      fn right(g: Int -> Int, n: Int, acc: Int): Int =
        if n == 0 then acc; else left g (n - 1) (g acc); ;
      ;
      fn example(flag: Bool): Int =
        let selected = if flag then increment; else twice;;
        left selected 3 0;
      ;
    """).void
  }

  test("a forwarding cycle without an incoming target supplies no parameter contract") {
    semNotFailed("""
      fn run(f: Int -> Int, n: Int): Int =
        if n == 0 then 0; else run f (f n); ;
      ;
    """).map { module =>
      val reference = callbackRef(module, "run")
      val flow      = CallableValues.fromModule(module)
      assertEquals(flow.parameters(reference), Nil)
      assertEquals(flow.provenLambdas(reference), None)
    }
  }

  List("" -> "borrowing", "~" -> "consuming").foreach { (marker, contract) =>
    test(s"recursive $contract callback accepts a fresh heap argument") {
      semNotFailed(s"""
        fn read(${marker}text: String): Int = text.length;;
        fn run(f: String -> Int, n: Int): Int =
          if n == 0 then 0;
          else f (int_to_str n) + run f (n - 1);
          ;
        ;
        fn example(): Int = run read 3;;
      """).void
    }
  }

  test("recursive callback alternatives with different consuming contracts are rejected") {
    val source = """
      fn borrow(text: String): Int = text.length;;
      fn consume(~text: String): Int = text.length;;
      fn run(f: String -> Int, n: Int): Int =
        if n == 0 then 0;
        else f (int_to_str n) + run f (n - 1);
        ;
      ;
      fn example(flag: Bool): Int =
        let selected = if flag then borrow; else consume;;
        run selected 3;
      ;
    """
    assertContractFailure(source, "f (int_to_str n)")
  }

  List("0", "example flag unknown").foreach { continuation =>
    test(s"an unknown incoming callback remains unknown with continuation $continuation") {
      val source = s"""
        fn known(text: String): Int = text.length;;
        fn run(f: String -> Int, n: Int): Int =
          if n == 0 then 0;
          else f (int_to_str n) + run f (n - 1);
          ;
        ;
        fn example(flag: Bool, unknown: String -> Int): Int =
          let selected = if flag then known; else unknown;;
          run selected 3 + $continuation;
        ;
      """
      assertContractFailure(source, "f (int_to_str n)")
    }
  }

  test("a recursive returned callable does not establish a forwarding contract") {
    val source = """
      fn known(text: String): Int = text.length;;
      fn choose(flag: Bool): String -> Int =
        if flag then known; else choose flag; ;
      ;
      fn run(): Int = choose false (int_to_str 42);;
    """
    parseNotFailed(source).flatMap(_ => semState(source)).map { result =>
      val errors = result.errors.collect { case error: SemanticError.InvalidExpression => error }
      assertEquals(
        errors.map(_.msg),
        List("Callable alternatives must agree on consuming parameters")
      )
      assertEquals(result.errors.size, 1, result.errors.toString)
      assertEquals(
        errors.head.expr.spanOpt.map(_.start.index),
        Some(source.indexOf("choose false"))
      )
    }
  }

  private def callbackRef(module: Module, owner: String): Ref =
    val id = resolvedBindingId(module, owner, "f")
    val parameter = module.resolvables
      .lookup(id)
      .collect { case param: FnParam => param }
      .getOrElse(fail("Expected callback parameter"))
    Ref(
      parameter.source,
      parameter.name,
      typeSpec   = parameter.typeSpec,
      resolvedId = parameter.id
    )

  private def assertContractFailure(source: String, use: String): IO[Unit] =
    parseNotFailed(source).flatMap(_ => semState(source)).map { result =>
      val errors = result.errors.collect { case error: SemanticError.InvalidExpression => error }
      assertEquals(
        errors.map(_.msg),
        List("Callable alternatives must agree on consuming parameters")
      )
      assertEquals(result.errors.size, 1, result.errors.toString)
      assertEquals(errors.head.expr.spanOpt.map(_.start.index), Some(source.indexOf(use)))
      val refs = TermTraversal.collect(errors.head.expr) { case ref: Ref => ref.resolvedId }.flatten
      assertEquals(refs, List(resolvedBindingId(result.module, "run", "f")))
    }

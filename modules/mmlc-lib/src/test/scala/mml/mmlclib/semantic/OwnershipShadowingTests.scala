package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class OwnershipShadowingTests extends BaseEffFunSuite:

  private def member(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case binding: Bnd if binding.name == name => binding }
      .getOrElse(fail(s"Expected member $name"))

  private def refAt(module: Module, index: Int): Ref =
    assert(index >= 0, "Expected a source-reference location")
    val refs = TermTraversal.collect(member(module, "example").value) {
      case ref: Ref if ref.spanOpt.exists(_.start.index == index) => ref
    }
    assertEquals(refs.size, 1, s"Expected one reference at $index")
    refs.head

  List(
    "consuming statement" -> "consume s;",
    "consuming initializer" -> "let size = take s;",
    "local transfer" -> "let moved = s;"
  ).foreach { (form, move) =>
    test(s"recursive local function shadows an outer binding after a $form") {
      val source = s"""
        fn consume(~value: String): Unit = println value;;
        fn take(~value: String): Int = value.length;;
        fn example(~s: String): Int =
          $move
          fn s(n: Int): Int =
            if n == 0 then 7; else s (n - 1); ;
          ;
          s 3;
        ;
      """
      semNotFailed(source).map { module =>
        val body = member(module, "example").value
        val bindings = TermTraversal
          .collect(body) { case lambda: Lambda =>
            lambda.params.filter(_.name == "s")
          }
          .flatten
        assertEquals(bindings.size, 2)
        assert(bindings.forall(_.id.nonEmpty))
        assertEquals(bindings.flatMap(_.id).distinct.size, 2)

        val outer     = refAt(module, source.indexOf(move) + move.indexOf("s;"))
        val recursive = refAt(module, source.indexOf("s (n - 1)"))
        val called    = refAt(module, source.indexOf("s 3"))
        assertNotEquals(outer.resolvedId, recursive.resolvedId)
        assertEquals(recursive.resolvedId, called.resolvedId)
        assertEquals(bindings.flatMap(_.id).toSet, Set(outer, recursive).flatMap(_.resolvedId))
      }
    }
  }

  List("direct" -> "length s", "captured" -> "let read = { length s; }; read ()").foreach {
    (form, laterUse) =>
      test(s"$form use of the same moved binding retains ownership diagnostics") {
        val source = s"""
          fn length(value: String): Int = value.length;;
          fn consume(~value: String): Unit = println value;;
          fn example(): Int =
            let s = int_to_str 42;
            consume s;
            $laterUse;
          ;
        """
        for
          parsed <- parseNotFailed(source)
          result <- semState(source)
        yield
          val moved     = refAt(parsed, source.indexOf("consume s;") + "consume ".length)
          val used      = refAt(parsed, source.indexOf("length s") + "length ".length)
          val bindingId = resolvedBindingId(result.module, "example", "s")
          val lastUses = result.errors.collect {
            case error: SemanticError.ConsumingParamNotLastUse => error
          }
          val moves = result.errors.collect { case error: SemanticError.UseAfterMove => error }
          assertEquals(lastUses.size, 1, result.errors.toString)
          assertEquals(moves.size, 1, result.errors.toString)
          assertEquals(result.errors.size, 2, result.errors.toString)

          assertEquals(lastUses.head.ref.resolvedId, Some(bindingId))
          assertEquals(lastUses.head.ref.source, moved.source)
          assertEquals(
            lastUses.head.param.id,
            Some(resolvedBindingId(result.module, "consume", "value"))
          )

          assertEquals(moves.head.ref.resolvedId, Some(bindingId))
          assertEquals(moves.head.ref.source, used.source)
          assertEquals(moves.head.movedAt, moved.source)
      }
  }

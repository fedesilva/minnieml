package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class MixedInvocationOwnershipTests extends BaseEffFunSuite:

  private val prelude = """
    fn take(~text: String): Int = text.length;;
    fn inc(n: Int): Int = n + 1;;
  """

  private def selected(body: String): String = prelude + s"""
    fn example(flag: Bool): Int =
      let text = int_to_str 123;
      let f = if flag then ~{ n: Int -> take text + n; }; else inc;;
      $body;
    ;
  """

  List(
    "direct invocation" -> "f 1",
    "alias invocation" -> "let alias = f; alias 1",
    "scoped invocation" -> "let alias = (let inner = f; inner); alias 1",
    "conditional invocation or drop" -> "if flag then f 1; else 0;"
  ).foreach { (name, body) =>
    test(s"mixed resource-free function permits $name") {
      semNotFailed(selected(body)).void
    }
  }

  test("mixed direct invocation destroys its original environment once through null-safe cleanup") {
    semNotFailed(selected("f 1")).map { module =>
      val body = module.members
        .collectFirst { case binding: Bnd if binding.name == "example" => binding.value }
        .getOrElse(fail("expected the example function"))
      val binding = TermTraversal
        .collect(body) { case binding: BindOwnership if binding.valueParam.name == "f" => binding }
        .headOption
        .getOrElse(fail("expected the mixed function binding"))
      val cleanup = TermTraversal
        .collect(binding.body) { case destroy: DestroyClosure => destroy }
        .filter(destroy =>
          TermTraversal
            .collect(destroy.operand) {
              case ref: Ref if ref.resolvedId == binding.valueParam.id => ref
            }
            .nonEmpty
        )
      val target = DestructionTargets
        .named("__free_closure", module.resolvables)
        .getOrElse(fail("expected null-safe closure destruction"))
      assert(binding.valueParam.id.nonEmpty)
      assertEquals(cleanup.map(_.targetId), List(target))
    }
  }

  List(
    "second invocation" -> "let first = f 1; f first",
    "invocation after alias transfer" -> "let alias = f; let first = alias 1; f first",
    "invocation after a branch consumes" ->
      "let first = if flag then f 1; else 0;; f first"
  ).foreach { (name, body) =>
    test(s"mixed resource-free function rejects $name") {
      semState(selected(body)).map { result =>
        assert(
          result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]),
          result.errors
        )
      }
    }
  }

  List(
    "borrowed closure alternative" -> """
      let text = int_to_str 123;
      let other = int_to_str 456;
      let f =
        if flag then ~{ n: Int -> take text + n; };
        else { n: Int -> other.length + n; };
        ;
      f 1
    """,
    "borrowed conditional reference" -> """
      let text = int_to_str 123;
      let owner = ~{ n: Int -> take text + n; };
      let f = if flag then owner; else inc;;
      f 1
    """
  ).foreach { (name, body) =>
    test(s"call-once invocation rejects $name") {
      semState(prelude + s"fn example(flag: Bool): Int = $body;;").map { result =>
        assert(
          result.errors.exists {
            case error: SemanticError.InvalidExpression =>
              error.message ==
                "Calling this function consumes its environment and requires ownership"
            case _ => false
          },
          result.errors
        )
      }
    }
  }

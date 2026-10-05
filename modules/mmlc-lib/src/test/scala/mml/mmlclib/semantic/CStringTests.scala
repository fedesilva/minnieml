package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.*

class CStringTests extends BaseEffFunSuite:

  test("injected CString owns the copy and survives a borrowing local function") {
    val source = """
      fn borrow(s: CString): Unit = @native;;
      fn main(): Unit =
        let original = "hello" ++ " world";
        let text = to_cstr original;
        fn draw(): Unit = borrow text;;
        draw ();
        println original;
      ;
    """

    semNotFailed(source).map { module =>
      val destructor = module.members.collectFirst {
        case b: Bnd if b.name == "free_cstr" => b
      }.get
      val main = module.members.collectFirst { case b: Bnd if b.name == "main" => b }.get
      val text = TermTraversal
        .collect(main.value) { case lambda: Lambda => lambda.params }
        .flatten
        .find(_.name == "text")
        .get
      val releases = TermTraversal.collect(main.value) {
        case TXCall1(fn: Ref, arg: Ref) if fn.resolvedId == destructor.id => arg.resolvedId
      }
      assertEquals(releases, List(text.id))
    }
  }

  test("CString destructor binds to C free") {
    compileAndGenerate("fn main(): Unit = let text = to_cstr \"hello\"; ();;").map { ir =>
      assertEquals(ir.linesIterator.count(_.contains("call void @free(")), 1)
      assert(!ir.contains("@free_cstr("))
    }
  }

  test("explicit CString release rejects later use") {
    semState("""
      fn borrow(s: CString): Unit = @native;;
      fn main(): Unit =
        let text = to_cstr "hello";
        free_cstr text;
        borrow text;
      ;
    """).map { result =>
      assert(result.errors.exists {
        case _: SemanticError.UseAfterMove | _: SemanticError.ConsumingParamNotLastUse => true
        case _ => false
      })
    }
  }

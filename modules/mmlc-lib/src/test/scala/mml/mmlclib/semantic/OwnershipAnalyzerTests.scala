package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class OwnershipAnalyzerTests extends BaseEffFunSuite:

  private def containsFreeString(term: Term): Boolean =
    term match
      case Ref(_, "__free_String", _, _, _, _, _) => true
      case App(_, fn, arg, _, _) => containsFreeString(fn) || containsFreeString(arg)
      case Expr(_, terms, _, _) => terms.exists(containsFreeString)
      case Lambda(_, _, body, _, _, _, _) => containsFreeString(body)
      case TermGroup(_, inner, _) => containsFreeString(inner)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        containsFreeString(cond) || containsFreeString(ifTrue) || containsFreeString(ifFalse)
      case Tuple(_, elements, _, _) => elements.exists(containsFreeString)
      case _ => false

  private def countFreesOf(name: String, term: Term): Int =
    term match
      case App(_, fn: Ref, Expr(_, List(Ref(_, argName, _, _, _, _, _)), _, _), _, _)
          if fn.name == "__free_String" && argName == name =>
        1
      case App(_, fn, arg, _, _) => countFreesOf(name, fn) + countFreesOf(name, arg)
      case Expr(_, terms, _, _) => terms.map(countFreesOf(name, _)).sum
      case Lambda(_, _, body, _, _, _, _) => countFreesOf(name, body)
      case TermGroup(_, inner, _) => countFreesOf(name, inner)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        countFreesOf(name, cond) + countFreesOf(name, ifTrue) + countFreesOf(name, ifFalse)
      case Tuple(_, elements, _, _) => elements.toList.map(countFreesOf(name, _)).sum
      case _ => 0

  test("caller frees value returned by user function that allocates internally") {
    val code =
      """
        fn get_string(n: Int): String =
          to_string n
        ;

        fn main(): Unit =
          let s = get_string 5;
          println s
        ;
      """

    semNotFailed(code).map { module =>
      def member(name: String) = module.members.collectFirst {
        case b: Bnd if b.name == name => b
      }.get

      val mainBody = member("main").value.terms.collectFirst { case l: Lambda => l.body }.get
      val getBody  = member("get_string").value.terms.collectFirst { case l: Lambda => l.body }.get

      assert(
        containsFreeString(mainBody),
        "expected caller to free String returned from get_string"
      )

      assert(
        !containsFreeString(getBody),
        "callee should not free the returned String"
      )
    }
  }

  test("right-assoc ++ chain frees each binding exactly once") {
    val code =
      """
        op ++(a: String, b: String): String 61 right = concat a b;

        fn main(): Unit =
          let s0 = to_string 0;
          let s  = "Zero: " ++ s0 ++ ", " ++ (to_string 1);
          println s
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = module.members.collectFirst {
        case b: Bnd if b.name == "main" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      val freesS0 = countFreesOf("s0", mainBody)
      assertEquals(freesS0, 1, "s0 should be freed exactly once, at scope end")
    }
  }

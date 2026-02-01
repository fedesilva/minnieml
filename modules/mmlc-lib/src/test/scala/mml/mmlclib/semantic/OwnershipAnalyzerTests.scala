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

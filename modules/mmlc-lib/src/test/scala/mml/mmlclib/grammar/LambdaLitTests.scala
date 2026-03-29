package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.TestExtractors.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

class LambdaLitTests extends BaseEffFunSuite:

  test("single param lambda") {
    parseNotFailed(
      """
        let f = { x -> x; };
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(lambda)) =>
          assert(lambda.params.size == 1, s"Expected 1 param: ${prettyPrintAst(m)}")
          assertEquals(lambda.params.head.name, "x")
        case other =>
          fail(s"Expected binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}")
    }
  }

  test("multi param lambda") {
    parseNotFailed(
      """
        let f = { a, b -> a; };
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(lambda)) =>
          assert(lambda.params.size == 2, s"Expected 2 params: ${prettyPrintAst(m)}")
        case other =>
          fail(s"Expected binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}")
    }
  }

  test("typed param") {
    parseNotFailed(
      """
        let f = { x: Int -> x; };
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(lambda)) =>
          assert(lambda.params.size == 1)
          assert(lambda.params.head.typeAsc.isDefined, "Expected typed param")
        case other =>
          fail(s"Expected binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}")
    }
  }

  test("zero param lambda") {
    parseNotFailed(
      """
        let f = { 42; };
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(lambda)) =>
          assert(lambda.params.isEmpty, s"Expected 0 params: ${prettyPrintAst(m)}")
        case other =>
          fail(s"Expected binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}")
    }
  }

  test("multiline body") {
    parseNotFailed(
      """
        let f = { x -> let y = x; y; };
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(lambda)) =>
          assert(lambda.params.size == 1)
          lambda.body match
            case TXExpr1(_: App) => ()
            case other => fail(s"Expected App for let-chain, got ${prettyPrintAst(other)}")
        case other =>
          fail(s"Expected binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}")
    }
  }

  test("nested lambdas") {
    parseNotFailed(
      """
        let f = { a -> { b -> a; }; };
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(outer)) =>
          assertEquals(outer.params.map(_.name), List("a"))
          outer.body match
            case TXExpr1(inner: Lambda) =>
              assertEquals(inner.params.map(_.name), List("b"))
            case other =>
              fail(s"Expected inner Lambda, got ${prettyPrintAst(other)}")
        case other =>
          fail(s"Expected binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}")
    }
  }

  test("lambda in application") {
    parseNotFailed(
      """
        fn apply(f, x) = f x;;
        let r = apply { x -> x; } 1;
      """
    ).map { m =>
      assert(m.members.size == 2, s"Expected 2 members: ${prettyPrintAst(m)}")
    }
  }

  test("consuming param") {
    parseNotFailed(
      """
        let f = { ~s -> s; };
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(lambda)) =>
          assert(lambda.params.size == 1)
          assert(lambda.params.head.consuming, "Expected consuming param")
        case other =>
          fail(s"Expected binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}")
    }
  }

  test("inner fn desugars to the same scoped binding shape as let-bound lambda") {
    parseNotFailed(
      """
        fn main(): Int =
          fn inc(a: Int): Int = a + 1;;
          inc 41;
        ;
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(mainLambda)) =>
          mainLambda.body match
            case TXExpr1(TXScopedBinding(bindingLambda, innerLambda: Lambda)) =>
              assertEquals(bindingLambda.params.map(_.name), List("inc"))
              assert(
                bindingLambda.params.head.typeAsc.isDefined,
                "Expected synthesized binding type"
              )
              assertEquals(innerLambda.params.map(_.name), List("a"))
              assert(innerLambda.params.head.typeAsc.isDefined, "Expected typed param")
              assert(innerLambda.typeAsc.isEmpty, "Expected return type on binding, not lambda")
            case other =>
              fail(
                s"Expected main body to be scoped binding application, got ${prettyPrintAst(other)}"
              )
        case other =>
          fail(
            s"Expected main binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}"
          )
    }
  }

  test("recursive nullary inner fn parses as scoped lambda binding") {
    parseNotFailed(
      """
        fn main(): Unit =
          fn loop(): Unit = loop();;
          loop();
        ;
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(mainLambda)) =>
          mainLambda.body match
            case TXExpr1(TXScopedBinding(bindingLambda, innerLambda: Lambda)) =>
              assertEquals(bindingLambda.params.map(_.name), List("loop"))
              assert(
                bindingLambda.params.head.typeAsc.isDefined,
                "Expected synthesized binding type"
              )
              assert(innerLambda.params.isEmpty, "Expected nullary inner function")
              assert(innerLambda.typeAsc.isEmpty, "Expected return type on binding, not lambda")
            case other =>
              fail(
                s"Expected main body to be scoped binding application, got ${prettyPrintAst(other)}"
              )
        case other =>
          fail(
            s"Expected main binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}"
          )
    }
  }

  test("lambda with operator body") {
    parseNotFailed(
      """
        let f = { a, b -> a + b; };
      """
    ).map { m =>
      m.members.headOption match
        case Some(TXBndLambda(lambda)) =>
          assert(lambda.params.size == 2)
          assert(
            lambda.body.terms.size == 3,
            s"Expected 3 terms in body: ${prettyPrintAst(m)}"
          )
        case other =>
          fail(s"Expected binding lambda, got ${other.map(prettyPrintAst(_)).getOrElse("<none>")}")
    }
  }

  test("reject incomplete - empty body after arrow") {
    parseFailed(
      """
        let f = { x -> };
      """
    )
  }

  test("lambda body terminator is optional before closing brace") {
    parseNotFailed(
      """
        let f = { x -> x };
      """
    )
  }

  test("reject incomplete - arrow with no params") {
    parseFailed(
      """
        let f = { -> x };
      """
    )
  }

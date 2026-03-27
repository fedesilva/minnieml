package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

class LambdaLitTests extends BaseEffFunSuite:

  test("single param lambda") {
    parseNotFailed(
      """
        let f = { x -> x; };
      """
    ).map { m =>
      val bnd = m.members.head.asInstanceOf[Bnd]
      bnd.value.terms.head match
        case lambda: Lambda =>
          assert(lambda.params.size == 1, s"Expected 1 param: ${prettyPrintAst(bnd)}")
          assertEquals(lambda.params.head.name, "x")
        case other => fail(s"Expected Lambda, got ${prettyPrintAst(other)}")
    }
  }

  test("multi param lambda") {
    parseNotFailed(
      """
        let f = { a, b -> a; };
      """
    ).map { m =>
      val bnd = m.members.head.asInstanceOf[Bnd]
      bnd.value.terms.head match
        case lambda: Lambda =>
          assert(lambda.params.size == 2, s"Expected 2 params: ${prettyPrintAst(bnd)}")
        case other => fail(s"Expected Lambda, got ${prettyPrintAst(other)}")
    }
  }

  test("typed param") {
    parseNotFailed(
      """
        let f = { x: Int -> x; };
      """
    ).map { m =>
      val bnd = m.members.head.asInstanceOf[Bnd]
      bnd.value.terms.head match
        case lambda: Lambda =>
          assert(lambda.params.size == 1)
          assert(lambda.params.head.typeAsc.isDefined, "Expected typed param")
        case other => fail(s"Expected Lambda, got ${prettyPrintAst(other)}")
    }
  }

  test("zero param lambda") {
    parseNotFailed(
      """
        let f = { 42; };
      """
    ).map { m =>
      val bnd = m.members.head.asInstanceOf[Bnd]
      bnd.value.terms.head match
        case lambda: Lambda =>
          assert(lambda.params.isEmpty, s"Expected 0 params: ${prettyPrintAst(bnd)}")
        case other => fail(s"Expected Lambda, got ${prettyPrintAst(other)}")
    }
  }

  test("multiline body") {
    parseNotFailed(
      """
        let f = { x -> let y = x; y; };
      """
    ).map { m =>
      val bnd = m.members.head.asInstanceOf[Bnd]
      bnd.value.terms.head match
        case lambda: Lambda =>
          assert(lambda.params.size == 1)
          // body is a statement chain (let y = x; y) so it's an App wrapping another Lambda
          lambda.body.terms.head match
            case _: App => () // let-chain produces App(Lambda(...), ...)
            case other => fail(s"Expected App for let-chain, got ${prettyPrintAst(other)}")
        case other => fail(s"Expected Lambda, got ${prettyPrintAst(other)}")
    }
  }

  test("nested lambdas") {
    parseNotFailed(
      """
        let f = { a -> { b -> a; }; };
      """
    ).map { m =>
      val bnd = m.members.head.asInstanceOf[Bnd]
      bnd.value.terms.head match
        case outer: Lambda =>
          assert(outer.params.size == 1)
          assertEquals(outer.params.head.name, "a")
          outer.body.terms.head match
            case inner: Lambda =>
              assert(inner.params.size == 1)
              assertEquals(inner.params.head.name, "b")
            case other => fail(s"Expected inner Lambda, got ${prettyPrintAst(other)}")
        case other => fail(s"Expected outer Lambda, got ${prettyPrintAst(other)}")
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
      val bnd = m.members.head.asInstanceOf[Bnd]
      bnd.value.terms.head match
        case lambda: Lambda =>
          assert(lambda.params.size == 1)
          assert(lambda.params.head.consuming, "Expected consuming param")
        case other => fail(s"Expected Lambda, got ${prettyPrintAst(other)}")
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
      val mainBnd = m.members.head.asInstanceOf[Bnd]
      mainBnd.value.terms.head match
        case mainLambda: Lambda =>
          mainLambda.body.terms match
            case List(App(_, bindingLambda: Lambda, argExpr, _, _)) =>
              assertEquals(bindingLambda.params.size, 1)
              assertEquals(bindingLambda.params.head.name, "inc")
              assert(
                bindingLambda.params.head.typeAsc.isDefined,
                "Expected synthesized binding type"
              )

              argExpr.terms match
                case List(innerLambda: Lambda) =>
                  assertEquals(innerLambda.params.size, 1)
                  assertEquals(innerLambda.params.head.name, "a")
                  assert(innerLambda.params.head.typeAsc.isDefined, "Expected typed param")
                  assert(innerLambda.typeAsc.isEmpty, "Expected return type on binding, not lambda")
                case other =>
                  fail(
                    s"Expected scoped binding value to be a Lambda, got ${other
                        .map(prettyPrintAst(_))
                        .mkString(", ")}"
                  )
            case other =>
              fail(
                s"Expected main body to be App(Lambda(binding), Lambda(value)), got ${other
                    .map(prettyPrintAst(_))
                    .mkString(", ")}"
              )
        case other =>
          fail(s"Expected main binding Lambda, got ${prettyPrintAst(other)}")
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
      val mainBnd = m.members.head.asInstanceOf[Bnd]
      mainBnd.value.terms.head match
        case mainLambda: Lambda =>
          mainLambda.body.terms match
            case List(App(_, bindingLambda: Lambda, argExpr, _, _)) =>
              assertEquals(bindingLambda.params.size, 1)
              assertEquals(bindingLambda.params.head.name, "loop")
              assert(
                bindingLambda.params.head.typeAsc.isDefined,
                "Expected synthesized binding type"
              )

              argExpr.terms match
                case List(innerLambda: Lambda) =>
                  assert(innerLambda.params.isEmpty, "Expected nullary inner function")
                  assert(innerLambda.typeAsc.isEmpty, "Expected return type on binding, not lambda")
                case other =>
                  fail(
                    s"Expected recursive binding value to be a Lambda, got ${other
                        .map(prettyPrintAst(_))
                        .mkString(", ")}"
                  )
            case other =>
              fail(
                s"Expected main body to be App(Lambda(binding), Lambda(value)), got ${other
                    .map(prettyPrintAst(_))
                    .mkString(", ")}"
              )
        case other =>
          fail(s"Expected main binding Lambda, got ${prettyPrintAst(other)}")
    }
  }

  test("lambda with operator body") {
    parseNotFailed(
      """
        let f = { a, b -> a + b; };
      """
    ).map { m =>
      val bnd = m.members.head.asInstanceOf[Bnd]
      bnd.value.terms.head match
        case lambda: Lambda =>
          assert(lambda.params.size == 2)
          assert(
            lambda.body.terms.size == 3,
            s"Expected 3 terms in body: ${prettyPrintAst(bnd)}"
          )
        case other => fail(s"Expected Lambda, got ${prettyPrintAst(other)}")
    }
  }

  test("reject incomplete - empty body after arrow") {
    parseFailed(
      """
        let f = { x -> };
      """
    )
  }

  test("reject missing lambda body terminator") {
    parseFailed(
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

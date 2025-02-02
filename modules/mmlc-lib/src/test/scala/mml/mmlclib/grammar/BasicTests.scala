package mml.mmlclib.grammar

import mml.mmlclib.ast.{Bnd, FnDef}
import mml.mmlclib.test.BaseEffFunSuite
import munit.*
import org.neo4j.internal.helpers.Strings.prettyPrint
import cats.syntax.all.*

//@munit.IgnoreSuite
class BasicTests extends BaseEffFunSuite:

  test("simple let") {

    val moduleF = modNotFailed("""
      module A = 
        let a = 1;
        let b = 2;
        let c = "tres";
      ;
      """)

    moduleF.map(m => assert(m.members.size == 3))

  }

  test("let with app") {

    modNotFailed(
      """
      module A =
        let c = a sum b;
      ;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref") {

    modNotFailed(
      """
        module A =
          let c = a + b;
        ;
        """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref and NO SPACES") {

    modNotFailed(
      """
          module A =
            let c = a+b;
          ;
          """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with symbolic ref multichar and no spaces") {

    modNotFailed(
      """
            module A =
              let c = a++b;
            ;
            """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref prefix") {

    modNotFailed(
      """
            module A =
              let c = +b;
            ;
            """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 2,
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref postfix") {

    modNotFailed(
      """
              module A =
                let c = b!;
              ;
              """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 2,
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref mixture") {

    modNotFailed(
      """
                module A =
                  let c = 5! + 3;
                ;
                """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 4,
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("simple fn") {
    modNotFailed(
      """
        module A =
          fn sum a b = a sum b;
        ;
      """
    ).map { m =>
      {
        assert(m.members.size == 1)
        m.members.head
      }
    }.map {
      case fn: FnDef => {
        assert(
          fn.params.size == 2,
          s"Expected 2 params but got ${fn.params.size}: ${prettyPrint(fn)} "
        )
        assert(
          fn.body.terms.size == 3,
          s"Expected 3 terms but got ${fn.body.terms.size}: ${prettyPrint(fn)} "
        )
      }
      case _ => fail("Expected a function")
    }
  }

  test("explicit module, name pased") {
    modNotFailed(
      """
      module A =
        let a = 1;
      ;
      """,
      "IgnoreThisName".some
    )
  }

  test("implicit module, name pased") {
    modNotFailed(
      """
        let a = 1;
      """,
      "TestModule".some
    )
  }

  test("fail: implicit module, name NOT  pased") {
    modFailed(
      """
          let a = 1;
        """
    )
  }

  test("fn and let".ignore) {
    modNotFailed(
      """
       module A = 
         let a = 1;
         let b = 2;
         fn sum a b = a + b;
         let x = sum a b;
       ;
       """
    )
  }

  test("app with id and lit".ignore) {
    modNotFailed(
      """
        let a = b + 3
      """
    )
  }

  test("fn and let".ignore) {
    modNotFailed(
      """
        let a = 1
        let b = 2
        fn sum a b = a + b
        let x = sum a b
      """
    )
  }

  test("fn let in where 1".ignore) {
    modNotFailed(
      """
        fn func a b = 
          let 
            doubleA = double a,
            doubleB = double b
          in
            doubleB + doubleA
          where 
            double x = x * 2 
      """
    )

  }

  test("fn let in where 2".ignore) {
    modNotFailed(
      """
        fn func a b = 
          let 
            doubleA = double a,
            tripleB = triple b
          in
            tripleB + doubleA
          where 
            double x = x * 2,
            triple x = x * 3 
        
      """
    )

  }

  test("0-arity fn".ignore) {
    modNotFailed(
      """
        fn a = 1
      """
    )
  }

  test("let with group".ignore) {
    modNotFailed(
      """
        let a = ( 2 + 2 ) / 2
      """
    )
  }

  test("let with multiple expressions and groupings #1".ignore) {
    modNotFailed(
      """
        let a =
          (
                2 +
                (8 / 2)
                + 4
          )
          -  4
        
      """
    )
  }

  test("let expression with multiple bindings #1".ignore) {
    modNotFailed("""
        let a = 1,
            b = 2
      """)
  }

  test("let expression with multiple bindings #2".ignore) {
    modNotFailed("""
        fn algo x =
          let 
            a = 1,
            b = 2 * x
          in
             x + (a * b)
        
      """)
  }

  test("if expressions #1".ignore) {
    modNotFailed(
      """
        let a =
          if a >= 0 then
           a
         else
           0
        
      """
    )
  }

  test("if expressions #2 (else if".ignore) {
    modNotFailed(
      """
        let a =
          if a >= 0 then
            a
          else if x <= 45 then
            let b = 2
             in 4 * b
          else
            0
        
      """
    )

  }

  test("impossible to define unbalanced if exp".ignore) {
    modFailed(
      """
        let a = if x then b
      """
    )
  }

package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class BasicTests extends BaseFunSuite {

  test("simple let") {
  
    modNotFailed("""
        let a = 1;
        let b = 2;
        let c = "tres";
      """.stripMargin
    )

  }

  test("let with app") {
  
    modNotFailed(
      """
        let a = 1;
        let b = 2;
        let c = a +  b;
      """.stripMargin
    )

  }

  test("simple fn") {
    modNotFailed(
      """
        fn sum a b = a + b;
      """.stripMargin
    )
  }
  
  test("app with id and lit") {
    modNotFailed(
      """
        let a = b + 3;
        
      """.stripMargin
    )
  }

  test("fn and let") {
    modNotFailed(
      """
        let a = 1;
        let b = 2;
        fn sum a b = a + b;
        let x = sum a b;
        
      """.stripMargin
    )
  }

  test("fn let in where 1") {
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
        ;
      """.stripMargin
    )
    
  }

  test("fn let in where 2") {
    modNotFailed(
      """
        fn func a b = 
          let 
            doubleA = double a,
            doubleB = triple b
          in
            doubleB + doubleA
          where 
            double x = x * 2,
            triple x = x * 3 
        ;
      """.stripMargin
    )
    
  }

  test("0-arity fn") {
    modNotFailed(
      """
        fn a = 1;
      """.stripMargin
    )
  }

  test("let with group") {
    modNotFailed(
      """
        let a = ( 2 + 2 ) / 2;
      """.stripMargin
    )
  }
  
  test("let with multiple expressions and groupings #1"){
    modNotFailed(
      """
        let a =
          (
                2 +
                (8 / 2)
                + 4
          )
          -  4;
        
      """.stripMargin
    )
  }
  
  test("let expression with multiple bindings #1") {
    modNotFailed(
      """
        let a = 1,
            b = 2;
      """.stripMargin)
  }
  
  test("let expression with multiple bindings #2") {
    modNotFailed(
      """
        fn algo x =
          let 
            a = 1,
            b = 2 * x
          in
             x + (a * b)
        ;
      """.stripMargin)
  }
  
  test("if expressions #1") {
    modNotFailed(
      """
        let a =
          if >= a 0 then
           a
         else
           0
        ;
      """.stripMargin
    )
  }
  
  test("if expressions #2 (else if") {
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
        ;
      """.stripMargin
    )
    
  }
  
  test("impossible to define unbalanced if exp"){
    modFailed(
      """
        let a = if x then b;
      """.stripMargin)
  }

}


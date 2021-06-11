package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class OpTests extends BaseFunSuite {

  test("simple binary op") {
    modNotFailed(
      """
        |op + a b = sum a b;
      """.stripMargin
    )
  }
  
  test("simple prefix unary op") {
    modNotFailed(
      """
        |op +. a = positive a;
      """.stripMargin
    )
  }
  
  test("simple suffix unary op") {
    modNotFailed(
      """
        |op .! a = factorial a;
      """.stripMargin
    )
  }
  
  test("Can NOT define binop with one arg"){
    modFailed(
      """
        |op + a = sum a
      """.stripMargin)
  }
  
  test("Can NOT define binop with No arg"){
    modFailed(
      """
        |op + = 1
      """.stripMargin)
  }
  
  test("Can NOT define prefix without arg"){
    modFailed(
      """
        |op .+ = ???
      """.stripMargin)
  }
  
  test("Cant define prefix with arg in incorrect position "){
    modFailed(
      """
        |op a .+ = sum a
      """.stripMargin)
  }
  
  test("Cant define postfix with NO arg"){
    modFailed(
      """
        |op +. = 1
      """.stripMargin)
  }
  
  test("Can define multichar operator"){
    modNotFailed(
      """
        |op => a b = doSomething a b; # applied as `a => b`
      """.stripMargin
    )
  }

  test("Can provide precedence for infix op"){
    modNotFailed(
      """
         op + '1 a b = ???;
      """
    )
  }

  test("Can provide precedence for prefix op"){
    modNotFailed(
      """
         op +. '1 b = ???;
      """
    )
  }

  test("Can provide precedence for postfix op"){
    modNotFailed(
      """
         op  .! '1  b = ???;
      """
    )
  }
  
}


package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class OpTests extends BaseFunSuite:

  test("simple infix binary op") {
    modNotFailed(
      """
        op + a b = sum a b;
      """.trim
    )
  }
  
  test("simple prefix unary op") {
    modNotFailed(
      """
        op +. a = positive a;
      """.trim
    )
  }
  
  test("simple suffix unary op") {
    modNotFailed(
      """
        op .! a = factorial a;
      """.trim
    )
  }
  
  test("alphanum simple binary op") {
    modNotFailed(
      """
        op plus a b = ???;
      """.trim
    )
  }
  
  test("alphanum simple prefix unary op") {
    modNotFailed(
      """
        op positive. a = ???;
      """.trim
    )
  }
  
  test("alphanum simple suffix unary op") {
    modNotFailed(
      """
        op .fact a = ???;
      """.trim
    )
  }
  
  test("Can NOT define binop with one arg") {
    modFailed(
      """
        op + a = sum a
      """.trim)
  }
  
  test("Can NOT define binop with No arg"){
    modFailed(
      """
        op + = 1;
      """.trim)
  }
  
  test("Can NOT define prefix without arg"){
    modFailed(
      """
        op .+ = ???
      """.trim)
  }
  
  test("Cant define prefix with arg in incorrect position "){
    modFailed(
      """
        op a .+ = sum a
      """.trim)
  }
  
  test("Cant define postfix with NO arg"){
    modFailed(
      """
        op +. = 1
      """.trim)
  }
  
  test("Can define multichar operator"){
    modNotFailed(
      """
        op => a b = doSomething a b; # written  `a => b`
      """.trim
    )
  }

  test("Can provide precedence for infix op"){
    modNotFailed(
      """
       op + [1] a b = ???;
      """
    )
  }

  test("Can provide precedence for prefix op"){
    modNotFailed(
      """
         op +. [1] b = ???;
      """
    )
  }

  test("Can provide precedence for postfix op"){
    modNotFailed(
      """
         op .! [1]  b = ???;
      """
    )
  }
  
  


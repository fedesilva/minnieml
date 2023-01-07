package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class SyntaxErrorTests extends BaseFunSuite:

  test("let keyword is an invalid id. # 1") {
    modFailed("let let = 1;")
  }
  
  test("let keyword is an invalid id. # 2") {
    modFailed("fn let = 1;")
  }
  
  test("fn keyword is an invalid id. # 1") {
    modFailed("fn fn = 1;")
  }

  test("fn keyword is an invalid id. # 2") {
    modFailed("let fn = 1;")
  }

  test("incomplete let eq 1") {
    modFailed("let = 1;")
  }
 
  test("incomplete fn 1") {
    modFailed("fn = 1;")
  }

  test("incomplete fn 2") {
    modFailed("fn 1 1;")
  }

  test("= is not a valid id. ") {
    modFailed("let = = 1;")
  }
  
  test("op keyword is not a valid fn id"){
    modFailed(
      """
        |fn op = 1 + 1;
      """.stripMargin
    )
  }
  
  test("op keyword is not a valid let id"){
    modFailed(
      """
        |let op = 1 + 1;
      """.stripMargin
    )
  }
  
  test("fn with op id is a syntax error"){
    modFailed(
      """
        |fn + a b = sum a b
      """.stripMargin)
  }
  


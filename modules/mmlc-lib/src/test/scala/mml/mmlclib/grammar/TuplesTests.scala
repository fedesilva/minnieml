package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class TuplesTests extends BaseFunSuite {

  test("simple tuple") {
    modNotFailed(
      """
        | let a = (1, 1);
      """.stripMargin
    )
  }
  
  test("hetero tuple") {
    modNotFailed(
      """
        | let a = (1, 1, "b");
      """.stripMargin
    )
  }
  
  test("expression within tuple") {
    modNotFailed(
      """
        | let a = (1, 2 * 2, "b");
      """.stripMargin
    )
  }
  
  test("nested tuple") {
    modNotFailed(
      """
        | let a = (1, (2,  2), "b");
      """.stripMargin
    )
  }
  
  test("tuple 2 let deconstruction"){
    modNotFailed(
      """
        |let (a, b) = (1,2);
      """.stripMargin)
  }
  
  test("tuple 3 let deconstruction"){
    modNotFailed(
      """
        |let (a, b, c) = (1,2,"b");
      """.stripMargin)
  }
  
  test("deconstruction of less than two elements is impossible") {
    modFailed(
      """
        |let (a) = (1,2);
      """.stripMargin)
  }
  
  test("construction of tuple 1 is impossible"){
    modFailed(
      """ 
          # Actually, this will be recognized as a grouped literal int
          # which is not what you want in this example
          # and tuples of one element are not valid - just use a literal
          let (a) = (1);
      """.stripMargin)
  }
  
}


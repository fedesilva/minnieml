package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class MultiLineTests extends BaseFunSuite:

  test("multi let") {
    val src =
      """
        let a =
         2
        let
        b
        =
        3
        let c = 2
        
      """
    modNotFailed(src)
  }

  test("multi let and fn ") {
    val src =
      """
        let a = 1
        let b = 2
        fn sum a b =
        a + b
        let x =
        sum
        a
        b
        
        fn
        times
        a
        b
        =
        a
        *
        b
        
      """.stripMargin
    modNotFailed(src)
  }


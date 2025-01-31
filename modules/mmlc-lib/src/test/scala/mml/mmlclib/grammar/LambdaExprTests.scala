package mml.mmlclib.grammar

import mml.mmlclib.test.BaseEffFunSuite

class LambdaExprTests extends BaseEffFunSuite:

  test("Simple lambda expr") {
    modNotFailed(
      """
        let dup = a -> a * 2
      """
    )
  }

  test("Simple lambda expr in a group") {
    modNotFailed(
      """
        let fun2 = funN (a b -> a + b) # parenthesis disambiguate
      """
    )
  }

  test("fn takes lambda") {
    modNotFailed(
      """
        fn funL l v1 v2 = l v1 v2
        let l1 = a b -> a + b
        let res = funL l1 2 2
      """
    )
  }

  test("lambda with types") {
    modNotFailed(
      """
        let l = a: Int b: Int -> a + b
      """
    )
  }


package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class ParenthesizedExpressionExecutionTests extends BaseEffFunSuite:

  private val source = """
    fn nested(): Int =
      1 + (2 + (3 + (4 + (5 + (6 + (7 + (8 +
        (9 + (10 + (11 + (12 + (13 + (14 + (15 + 16))))))))))))));
    ;

    pub fn main(): Int =
      println (int_to_str (nested ()));
      println (int_to_str (2 * (3 + 4)));
      println (int_to_str (2 * 3 + 4));
      println (int_to_str (20 - (5 - 2)));
      println (int_to_str (20 - 5 - 2));
      0;
    ;
  """

  for optimization <- List(0, 3) do
    test(s"nested groups preserve arithmetic results and precedence at O$optimization"):
      runSanitizedProgram(
        source,
        s"ParenthesizedExpressionsO$optimization",
        optLevel       = optimization,
        expectedOutput = List("136", "14", "10", "17", "13")
      )

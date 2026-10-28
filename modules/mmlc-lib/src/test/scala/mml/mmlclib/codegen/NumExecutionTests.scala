package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class NumExecutionTests extends BaseEffFunSuite:

  test("Num Int and retained integer operators preserve their results at O0"):
    runSanitizedProgram(
      integerOperations,
      "NumIntO0",
      optLevel = 0,
      expectedOutput = List(
        "10",
        "6",
        "16",
        "2",
        "false",
        "true",
        "false",
        "true",
        "false",
        "true",
        "true",
        "false",
        "true",
        "true",
        "-8",
        "-2",
        "-1",
        "12",
        "-4",
        "7",
        "-2147483648",
        "2147483647"
      )
    )

  test("Num Int and retained integer operators preserve their results at O3"):
    runSanitizedProgram(
      integerOperations,
      "NumIntO3",
      optLevel = 3,
      expectedOutput = List(
        "10",
        "6",
        "16",
        "2",
        "false",
        "true",
        "false",
        "true",
        "false",
        "true",
        "true",
        "false",
        "true",
        "true",
        "-8",
        "-2",
        "-1",
        "12",
        "-4",
        "7",
        "-2147483648",
        "2147483647"
      )
    )

  test("all eleven Num Float operations execute with unified spellings at O0"):
    runSanitizedProgram(
      floatOperations,
      "NumFloatO0",
      optLevel = 0,
      expectedOutput = List(
        "10",
        "6",
        "16",
        "4.5",
        "false",
        "true",
        "false",
        "true",
        "false",
        "true",
        "true",
        "false",
        "true",
        "true",
        "-8"
      )
    )

  test("all eleven Num Float operations execute with unified spellings at O3"):
    runSanitizedProgram(
      floatOperations,
      "NumFloatO3",
      optLevel = 3,
      expectedOutput = List(
        "10",
        "6",
        "16",
        "4.5",
        "false",
        "true",
        "false",
        "true",
        "false",
        "true",
        "true",
        "false",
        "true",
        "true",
        "-8"
      )
    )

  test("Float NaN predicates and subtraction-from-zero negation preserve edge behavior at O0"):
    runSanitizedProgram(
      floatEdges,
      "NumFloatEdgesO0",
      optLevel = 0,
      expectedOutput = List(
        "false",
        "true",
        "false",
        "false",
        "false",
        "false",
        "true",
        "true",
        "true",
        "true"
      )
    )

  test("Float NaN predicates and subtraction-from-zero negation preserve edge behavior at O3"):
    runSanitizedProgram(
      floatEdges,
      "NumFloatEdgesO3",
      optLevel = 3,
      expectedOutput = List(
        "false",
        "true",
        "false",
        "false",
        "false",
        "false",
        "true",
        "true",
        "true",
        "true"
      )
    )

  test("numeric callable values and partials evaluate operands once in source order at O0"):
    runSanitizedProgram(
      callableValues,
      "NumCallableValuesO0",
      optLevel = 0,
      expectedOutput = List(
        "1",
        "2",
        "3",
        "3.5",
        "partial created",
        "4.5",
        "8",
        "-1.5"
      )
    )

  test("numeric callable values and partials evaluate operands once in source order at O3"):
    runSanitizedProgram(
      callableValues,
      "NumCallableValuesO3",
      optLevel = 3,
      expectedOutput = List(
        "1",
        "2",
        "3",
        "3.5",
        "partial created",
        "4.5",
        "8",
        "-1.5"
      )
    )

  test("Num and named protocols execute with distinct shadowed parameters at O0"):
    runSanitizedProgram(
      shadowedParameterInference,
      "ProtocolShadowedParametersO0",
      optLevel       = 0,
      expectedOutput = List("2", "2", "2")
    )

  test("Num and named protocols execute with distinct shadowed parameters at O3"):
    runSanitizedProgram(
      shadowedParameterInference,
      "ProtocolShadowedParametersO3",
      optLevel       = 3,
      expectedOutput = List("2", "2", "2")
    )

  private val shadowedParameterInference = """
    protocol Combine 'T = fn combine(left: 'T, right: 'T): 'T; ;
    instance Combine Float = fn combine(left, right) = right;; ;

    let numeric = { value ->
      let result = (let value = 1.0; value + 1.0);
      let gate = not value;
      result;
    };
    let named = { value ->
      let result = (let value = 1.0; combine value 2.0);
      let gate = not value;
      result;
    };
    let aliased = { value ->
      let alias = value;
      let result = (let alias = 1.0; alias + 1.0);
      let gate = not alias;
      result;
    };

    pub fn main(): Unit =
      println (float_to_str (numeric true));
      println (float_to_str (named true));
      println (float_to_str (aliased true));
    ;
  """

  private val integerOperations = """
    fn report(value: Bool): Unit =
      if value then println "true";
      else println "false";
      ;
    ;
    pub fn main(): Int =
      println (int_to_str (8 + 2));
      println (int_to_str (8 - 2));
      println (int_to_str (8 * 2));
      println (int_to_str (8 / 3));
      report (8 == 2);
      report (8 != 2);
      report (8 < 2);
      report (8 > 2);
      report (8 <= 2);
      report (8 >= 2);
      report (8 == 8);
      report (8 != 8);
      report (8 <= 8);
      report (8 >= 8);
      println (int_to_str (-8));
      println (int_to_str (-7 / 3));
      println (int_to_str (-7 % 3));
      println (int_to_str (3 << 2));
      println (int_to_str (-8 >> 1));
      println (int_to_str (+7));
      println (int_to_str (-2147483648));
      println (int_to_str 2147483647);
      0;
    ;
  """

  private val floatOperations = """
    fn report(value: Bool): Unit =
      if value then println "true";
      else println "false";
      ;
    ;
    pub fn main(): Int =
      println (float_to_str (8.0 + 2.0));
      println (float_to_str (8.0 - 2.0));
      println (float_to_str (8.0 * 2.0));
      println (float_to_str (9.0 / 2.0));
      report (8.0 == 2.0);
      report (8.0 != 2.0);
      report (8.0 < 2.0);
      report (8.0 > 2.0);
      report (8.0 <= 2.0);
      report (8.0 >= 2.0);
      report (8.0 == 8.0);
      report (8.0 != 8.0);
      report (8.0 <= 8.0);
      report (8.0 >= 8.0);
      println (float_to_str (-8.0));
      0;
    ;
  """

  private val floatEdges = """
    fn report(value: Bool): Unit =
      if value then println "true";
      else println "false";
      ;
    ;
    pub fn main(): Int =
      let nan = 0.0 / 0.0;
      report (nan == nan);
      report (nan != nan);
      report (nan < 1.0);
      report (nan > 1.0);
      report (nan <= 1.0);
      report (nan >= 1.0);
      let positive_zero = 0.0;
      let negative_zero = 0.0 / (0.0 - 1.0);
      report ((1.0 / positive_zero) > 0.0);
      report ((1.0 / negative_zero) < 0.0);
      report ((1.0 / (-positive_zero)) > 0.0);
      report ((1.0 / (-negative_zero)) > 0.0);
      0;
    ;
  """

  private val callableValues = """
    fn mark_int(value: Int): Int =
      println (int_to_str value);
      value;
    ;
    fn mark_float(value: Float): Float =
      println (float_to_str value);
      value;
    ;
    pub fn main(): Int =
      let add: Int -> Int -> Int = (+);
      println (int_to_str (add (mark_int 1) (mark_int 2)));
      let partial: Float -> Float = (+) (mark_float 3.5);
      println "partial created";
      println (float_to_str (partial (mark_float 4.5)));
      let negate: Float -> Float = (-);
      println (float_to_str (negate 1.5));
      0;
    ;
  """

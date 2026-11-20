package mml.mmlclib.codegen

import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

class ProtocolPrerequisiteExecutionTests extends BaseEffFunSuite:

  private val declarations = """
    protocol Strong 'T where 'T: Intermediate = ;
    protocol Intermediate 'T where 'T: Measure = ;
    protocol Measure 'T = fn measure(~value: 'T, extra: Int): Int; ;
    instance Strong String = ;
    instance Intermediate String = ;
    instance Measure String =
      fn measure(~value, extra) = println "member"; value.length + extra;;
    ;
    fn mark(value: Int): Int = println (to_str value); value;;
    fn make(value: Int): String = int_to_str (mark value);;
    fn direct(~value: 'T): Int where 'T: Strong = measure value (mark 2);;
    fn callable(~value: 'T): Int where 'T: Strong =
      let selected: 'T -> Int -> Int = measure;
      selected value (mark 4);
    ;
    fn partial(~value: 'T): Int -> Int where 'T: Strong =
      let pending = measure value;
      println "partial created";
      pending;
    ;
    fn weak(~value: 'T, extra: Int): Int where 'T: Measure = measure value extra;;
    fn forward(~value: 'T): Int where 'T: Strong =
      let selected: 'T -> Int -> Int = weak;
      selected value (mark 8);
    ;
    fn nested(~value: 'T): Int where 'T: Strong =
      fn local(~other: 'T): Int = measure other (mark 10);;
      local value;
    ;
    protocol Aggregate 'T where 'T: Additive = ;
    protocol Additive 'T where 'T: Combine = ;
    protocol Combine 'T = op <+>(left: 'T, right: 'T): 'T 60 left; ;
    instance Aggregate Int = ;
    instance Additive Int = ;
    instance Combine Int = op <+>(left, right) = println "operator"; left + right;; ;
    fn twice(value: 'T): 'T where 'T: Aggregate = value <+> value;;
  """

  for optimization <- List(0, 3) do
    test(s"implied calls retain evaluation order and ownership under sanitizers at O$optimization"):
      runSanitizedProgram(
        declarations + """
          pub fn main(): Unit =
            println (to_str (direct (make 1)));
            println (to_str (callable (make 3)));
            let pending = partial (make 5);
            println (to_str (pending (mark 6)));
            println (to_str (forward (make 7)));
            println (to_str (nested (make 9)));
            let dropped = partial (make 11);
            println "dropped";
            println (to_str (twice (mark 12)));
          ;
        """,
        s"ImpliedRequirementsO$optimization",
        optimization,
        List(
          "1",
          "2",
          "member",
          "3",
          "3",
          "4",
          "member",
          "5",
          "5",
          "partial created",
          "6",
          "member",
          "7",
          "7",
          "8",
          "member",
          "9",
          "9",
          "10",
          "member",
          "11",
          "11",
          "partial created",
          "dropped",
          "12",
          "operator",
          "24"
        )
      )

  test("an inherited consuming member makes a generic function's parameter unavailable"):
    val source = declarations + """
      fn invalid(~value: 'T): Int where 'T: Strong =
        let consumed = measure value 1;
        consumed + measure value 2;
      ;
      pub fn main(): Int = invalid (int_to_str 42);;
    """
    semVisibleState(source).map { state =>
      val failures = state.errors.collect { case error: SemanticError.UseAfterMove => error }
      assertEquals(failures.size, 1)
      assertEquals(failures.head.ref.spanOpt.map(_.start.index), Some(source.lastIndexOf("value")))
      assertEquals(
        failures.head.movedAt.spanOpt.map(_.start.index),
        Some(source.indexOf("value 1"))
      )
    }

package mml.mmlclib.codegen

import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

class ProtocolExecutionTests extends BaseEffFunSuite:

  test("protocol calls, callable values, and partials evaluate arguments once in order at O0"):
    runSanitizedProgram(
      orderedCalls,
      "ProtocolOrderO0",
      optLevel = 0,
      expectedOutput = List(
        "1",
        "2",
        "member",
        "3",
        "3",
        "partial created",
        "4",
        "member",
        "7",
        "5",
        "6",
        "member",
        "11",
        "7",
        "8",
        "operator",
        "15"
      )
    )

  test("protocol calls, callable values, and partials evaluate arguments once in order at O3"):
    runSanitizedProgram(
      orderedCalls,
      "ProtocolOrderO3",
      optLevel = 3,
      expectedOutput = List(
        "1",
        "2",
        "member",
        "3",
        "3",
        "partial created",
        "4",
        "member",
        "7",
        "5",
        "6",
        "member",
        "11",
        "7",
        "8",
        "operator",
        "15"
      )
    )

  test("a recursive concrete member executes through its selected ordinary binding"):
    val source = """
      protocol CountDown 'T = fn countDown(value: 'T): 'T; ;
      instance CountDown Int =
        fn countDown(value) =
          if value == 0 then 0;
          else countDown (value - 1);
          ;
        ;
      ;
      instance CountDown Bool = fn countDown(value) = value;; ;
      pub fn main(): Int =
        println (int_to_str (countDown 3));
        0;
      ;
    """
    runSanitizedProgram(source, "ProtocolRecursion", optLevel = 3, expectedOutput = List("0"))

  test("a member returning a callable evaluates supplied operands once before both call stages"):
    val source = """
      protocol Make 'T = fn make(seed: Int): ('T -> 'T); ;
      instance Make Int =
        fn make(seed) =
          println "outer";
          ~{ value: Int ->
            println "inner";
            seed + value;
          };
        ;
      ;
      instance Make Bool = fn make(seed) = { value: Bool -> value; };; ;
      fn mark(value: Int): Int =
        println (int_to_str value);
        value;
      ;
      pub fn main(): Int =
        println (int_to_str (make (mark 9) (mark 10)));
        0;
      ;
    """
    runSanitizedProgram(
      source,
      "ProtocolReturnedCallable",
      optLevel       = 3,
      expectedOutput = List("9", "10", "outer", "inner", "19")
    )

  test("a returned-callable annotation preserves operand order and invokes each stage once"):
    val source = """
      protocol Make 'T = fn make(seed: Int): ('T -> 'T); ;
      instance Make Int =
        fn make(seed) =
          println "outer";
          ~{ value: Int ->
            println "inner";
            seed + value;
          };
        ;
      ;
      instance Make Bool = fn make(seed) = { value: Bool -> value; };; ;
      fn mark(value: Int): Int =
        println (int_to_str value);
        value;
      ;
      pub fn main(): Int =
        println (int_to_str (((make (mark 9)): Int -> Int) (mark 10)));
        0;
      ;
    """
    runSanitizedProgram(
      source,
      "AnnotatedProtocolReturnedCallable",
      optLevel       = 3,
      expectedOutput = List("9", "10", "outer", "inner", "19")
    )

  test("annotated direct lambda calls and partials evaluate their operands once in source order"):
    val source = """
      fn mark(value: Int): Int =
        println (int_to_str value);
        value;
      ;
      pub fn main(): Int =
        let full =
          (({ left: Int, right: Int ->
            println "full";
            left + right;
          }): Int -> Int -> Int) (mark 1) (mark 2);
        println (int_to_str full);
        let partial =
          (({ left: Int, right: Int ->
            println "partial";
            left + right;
          }): Int -> Int -> Int) (mark 3);
        println "created";
        println (int_to_str (partial (mark 4)));
        0;
      ;
    """
    runSanitizedProgram(
      source,
      "AnnotatedDirectLambdas",
      optLevel       = 3,
      expectedOutput = List("1", "2", "full", "3", "3", "created", "4", "partial", "7")
    )

  test("an ordinary named function argument executes through its late callable adapter"):
    val source = """
      fn apply(f: Int -> Int): Int = f 41;;
      fn inc(value: Int): Int =
        println "inc";
        value + 1;
      ;
      pub fn main(): Int =
        println (int_to_str (apply inc));
        0;
      ;
    """
    runSanitizedProgram(
      source,
      "OrdinaryCallableArgument",
      optLevel       = 0,
      expectedOutput = List("inc", "42")
    )

  test("a selected String member receives and destroys its consuming heap parameter"):
    val source = """
      protocol Measure 'T = fn measure(~value: 'T): Int; ;
      instance Measure Int = fn measure(~value) = value;; ;
      instance Measure String = fn measure(~value) = value.length;; ;
      pub fn main(): Int =
        let text = int_to_str 12345;
        let length = measure text;
        println (int_to_str length);
        0;
      ;
    """
    runSanitizedProgram(source, "ProtocolConsumesString", optLevel = 0, expectedOutput = List("5"))

  test("a selected String member returns a fresh owned result while its input stays borrowed"):
    val source = """
      protocol Decorate 'T = fn decorate(value: 'T): 'T; ;
      instance Decorate Int = fn decorate(value) = value + 1;; ;
      instance Decorate String = fn decorate(value) = value ++ "!";; ;
      pub fn main(): Int =
        let original = int_to_str 42;
        let decorated = decorate original;
        println original;
        println decorated;
        0;
      ;
    """
    runSanitizedProgram(
      source,
      "ProtocolReturnsString",
      optLevel       = 3,
      expectedOutput = List("42", "42!")
    )

  test("a String protocol partial retains ownership until invocation or drop at O0"):
    runSanitizedProgram(
      ownedStringPartial,
      "ProtocolOwnedStringPartialO0",
      optLevel       = 0,
      expectedOutput = List("created", "retained", "member", "4", "created", "dropped")
    )

  test("a String protocol partial retains ownership until invocation or drop at O3"):
    runSanitizedProgram(
      ownedStringPartial,
      "ProtocolOwnedStringPartialO3",
      optLevel       = 3,
      expectedOutput = List("created", "retained", "member", "4", "created", "dropped")
    )

  test("a consuming String protocol call makes the caller's value unavailable"):
    val source = """
      protocol Measure 'T = fn measure(~value: 'T): Int; ;
      instance Measure String = fn measure(~value) = value.length;; ;
      fn example(): Unit =
        let text = int_to_str 12345;
        let length = measure text;
        println text;
      ;
    """
    semVisibleState(source).map { result =>
      val moves = result.errors.collect { case error: SemanticError.UseAfterMove => error }

      assertEquals(moves.size, 1, result.errors)
      assertEquals(moves.head.ref.name, "text")
      assertEquals(moves.head.ref.spanOpt.get.start.index, source.lastIndexOf("text"))
    }

  test("a String protocol partial cannot supply its owned argument a second time"):
    val source = """
      protocol Measure 'T = fn measure(~value: 'T, extra: Int): Int; ;
      instance Measure String = fn measure(~value, extra) = value.length + extra;; ;
      fn example(): Int =
        let text = int_to_str 123;
        let pending = measure text;
        let first = pending 1;
        let second = pending 2;
        first + second;
      ;
    """
    semVisibleState(source).map { result =>
      val moves = result.errors.collect { case error: SemanticError.UseAfterMove => error }

      assertEquals(moves.size, 1)
      assertEquals(moves.head.ref.name, "pending")
      assertEquals(moves.head.ref.spanOpt.get.start.index, source.lastIndexOf("pending"))
    }

  private val ownedStringPartial = """
    protocol Measure 'T = fn measure(~value: 'T, extra: Int): Int; ;
    instance Measure String =
      fn measure(~value, extra) =
        println "member";
        value.length + extra;
      ;
    ;
    fn make(value: Int): Int -> Int =
      let text = int_to_str value;
      let pending = measure text;
      println "created";
      pending;
    ;
    fn drop_without_calling(): Unit =
      let pending = make 4567;
      println "dropped";
    ;
    pub fn main(): Int =
      let pending = make 123;
      println "retained";
      let result = pending 1;
      println (int_to_str result);
      drop_without_calling ();
      0;
    ;
  """

  private val orderedCalls = """
    protocol Choose 'T = fn choose(seed: Int, value: 'T): 'T; ;
    instance Choose Int =
      fn choose(seed, value) =
        println "member";
        seed + value;
      ;
    ;
    instance Choose Bool = fn choose(seed, value) = value;; ;
    protocol Combine 'T = op <+>(left: 'T, right: 'T): 'T 60 left; ;
    instance Combine Int =
      op <+>(left, right) =
        println "operator";
        left + right;
      ;
    ;
    instance Combine Bool = op <+>(left, right) = left or right;; ;
    fn mark(value: Int): Int =
      println (int_to_str value);
      value;
    ;
    pub fn main(): Int =
      let full = choose (mark 1) (mark 2);
      println (int_to_str full);
      let partial: Int -> Int = choose (mark 3);
      println "partial created";
      println (int_to_str (partial (mark 4)));
      let selected: Int -> Int -> Int = choose;
      println (int_to_str (selected (mark 5) (mark 6)));
      println (int_to_str ((mark 7) <+> (mark 8)));
      0;
    ;
  """

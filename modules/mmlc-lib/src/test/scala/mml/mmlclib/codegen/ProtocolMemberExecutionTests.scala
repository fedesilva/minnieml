package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class ProtocolMemberExecutionTests extends BaseEffFunSuite:

  for optimization <- List(0, 3) do
    test(s"member calls and consuming partials evaluate their operands once at O$optimization"):
      runSanitizedProgram(
        """
          protocol Apply 'T = op ==>(x: 'T, f: 'T -> 'U): 'U 40 left; ;
          instance Apply Int = op ==>(x, f) = f x;; ;
          protocol Label 'K = fn label(~key: 'K, value: 'U): String where 'U: ToString; ;
          instance Label String = fn label(~key, value) = key ++ (to_str value);; ;
          fn key(): String = println "key"; int_to_str 7;;
          fn marked(): Int = println "operand"; 42;;
          fn text(n: Int): String = println "callback"; int_to_str n;;
          fn make_text(): Int -> String = println "construct"; text;;
          fn through(x: 'T, f: 'T -> 'U): 'U where 'T: Apply = x ==> f;;
          fn partial(~key: 'T): Bool -> String where 'T: Label = label key;;
          pub fn main(): Unit =
            println (through (marked ()) (make_text ()));
            println ((marked ()) ==> (make_text ()));
            println ((==>) String (marked ()) (make_text ()));
            println (label (key ()) (marked ()));
            let pending = partial (key ());
            println "invoke";
            println (pending true);
            let dropped = partial (key ());
            println "done";
          ;
        """,
        s"ProtocolMemberO$optimization",
        optimization,
        List(
          "operand",
          "construct",
          "callback",
          "42",
          "operand",
          "construct",
          "callback",
          "42",
          "operand",
          "construct",
          "callback",
          "42",
          "key",
          "operand",
          "742",
          "key",
          "invoke",
          "7true",
          "key",
          "done"
        )
      )

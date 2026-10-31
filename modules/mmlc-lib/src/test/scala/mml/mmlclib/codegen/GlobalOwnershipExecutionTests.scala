package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class GlobalOwnershipExecutionTests extends BaseEffFunSuite:

  List(0, 3).foreach { optimization =>
    test(s"explicit global copies survive owning boundaries at O$optimization") {
      runSanitizedProgram(
        """
          struct Box { text: String };
          let global = "global";
          let aggregate = Box "field";
          fn take(~text: String): Unit = println text;;
          fn staged(~text: String, n: Int): Unit = println text;;
          fn fixed(): String = global;;
          fn static_result(flag: Bool): String =
            if flag then aggregate.text; else "literal";;
          ;
          fn aliased_static_result(flag: Bool): String =
            let result = if flag then aggregate.text; else "literal";;
            result;
          ;
          fn returned(flag: Bool): String =
            if flag then concat "owned" " result"; else ^(fixed ());;
          ;
          fn check(flag: Bool): Unit =
            take (^global);
            let box = Box (^aggregate.text);
            println box.text;
            let captured = ^global;
            let f = ~{ n: Int -> println captured; };
            f 1;
            let payload = ^global;
            let p = staged payload;
            p 1;
            let result = returned flag;
            println result;
            println (static_result flag);
            println (aliased_static_result flag);
            println global;
            println aggregate.text;
          ;
          pub fn main(): Unit = check true; check false;;
        """,
        s"GlobalOwnershipO$optimization",
        optLevel = optimization,
        expectedOutput = List(
          "global",
          "field",
          "global",
          "global",
          "owned result",
          "field",
          "field",
          "global",
          "field",
          "global",
          "field",
          "global",
          "global",
          "global",
          "literal",
          "literal",
          "global",
          "field"
        )
      )
    }
  }

package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class CloneExecutionTests extends BaseEffFunSuite:

  List(0, 3).foreach { optimization =>
    test(s"native Clone borrows its source and supports callable values at O$optimization"):
      runSanitizedProgram(
        """
          fn apply_copy(copy: String -> String, value: String): String = copy value;;
          pub fn main(): Unit =
            let source = concat "hello" " world";
            let copy = ^source;
            let duplicate: String -> String = (^);
            let another = duplicate source;
            println copy;
            println another;
            let deferred_copy = apply_copy (^);
            println (deferred_copy source);
            println source;
          ;
        """,
        s"NativeCloneO$optimization",
        optLevel       = optimization,
        expectedOutput = List("hello world", "hello world", "hello world", "hello world")
      )
  }

  List(0, 3).foreach { optimization =>
    test(
      s"generated nested Clone preserves source and independently owns fields at O$optimization"
    ):
      runSanitizedProgram(
        """
          struct Outer { inner: Inner, count: Int };
          struct Inner { label: String };
          struct Point { x: Int, y: Int };
          pub fn main(): Unit =
            let original = Outer (Inner (concat "nested" " value")) 7;
            let copy = ^original;
            println copy.inner.label;
            println original.inner.label;
            let point = ^(Point 3 4);
            println (to_str point.x);
          ;
        """,
        s"StructCloneO$optimization",
        optLevel       = optimization,
        expectedOutput = List("nested value", "nested value", "3")
      )
  }

  List(0, 3).foreach { optimization =>
    test(s"native mutable clones retain independent storage at O$optimization"):
      runSanitizedProgram(
        """
          fn copied_strings(): StringArray =
            let original = ar_str_new 1;
            ar_str_set original 0 (concat "old" " value");
            ^original;
          ;
          pub fn main(): Unit =
            let ints = ar_int_new 1;
            ar_int_set ints 0 7;
            let ints_copy = ^ints;
            ar_int_set ints_copy 0 9;
            println (to_str (ar_int_get ints 0));
            let floats = ar_float_new 1;
            ar_float_set floats 0 3.5;
            let floats_copy = ^floats;
            println (to_str (ar_float_get floats_copy 0 == 3.5));
            ar_float_set floats_copy 0 5.0;
            println (to_str (float_to_int (ar_float_get floats 0)));
            let strings_copy = copied_strings ();
            println (ar_str_get strings_copy 0);
            let rng = rng_new 17;
            let rng_copy = ^rng;
            let first = rng_next rng;
            let copied_first = rng_next rng_copy;
            println (to_str (first == copied_first));
          ;
        """,
        s"NativeMutableCloneO$optimization",
        optLevel       = optimization,
        expectedOutput = List("7", "true", "3", "old value", "true")
      )
  }

  List(0, 3).foreach { optimization =>
    test(s"Buffer Clone copies contents and keeps writes independent at O$optimization"):
      runSanitizedProgram(
        """
          pub fn main(): Unit =
            let buffer = mkBuffer ();
            buffer_write buffer "buffer";
            let buffer_copy = ^buffer;
            buffer_writeln buffer " source";
            buffer_writeln buffer_copy " copy";
            flush buffer;
            flush buffer_copy;
          ;
        """,
        s"BufferCloneO$optimization",
        optLevel       = optimization,
        expectedOutput = List("buffer source", "buffer copy")
      )
  }

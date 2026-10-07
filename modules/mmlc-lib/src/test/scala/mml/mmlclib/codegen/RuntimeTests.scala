package mml.mmlclib.codegen

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class RuntimeTests extends BaseEffFunSuite:

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-int32-"))
      .bracket(use) { directory =>
        Resource.fromAutoCloseable(IO.blocking(Files.walk(directory))).use { paths =>
          IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
        }
      }

  private def targetAt(directory: Path, config: CompilerConfig): IO[(String, ClangTarget)] =
    for
      triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing target")))
      target <- ClangTarget
        .resolve(directory, LlvmToolchain.clangFlags(config, triple))
        .map(_.fold(error => fail(error.message), identity))
    yield (triple, target)

  List(true, false).foreach { forward =>
    val order = if forward then "before" else "after"
    test(s"global literals referenced $order their declarations execute correctly"):
      val declarations = """
        let inferred = 30;
        let annotated: Int = 30;
        let int32: Int32 = 30;
        let term = 30: Int32;
        let flag = true;
        let message = "hello";
        let fraction = 1.5;
      """
      val main         = """
        pub fn main(): Int =
          let boolean = if flag then 0; else 1;;
          boolean +
          check (inferred == 30) + check (annotated == 30) +
          check (int32 == 30) + check (term == 30) +
          check (str_eq (int_to_str inferred) "30") +
          check (str_eq message "hello") + check (fraction ==. 1.5);
        ;
      """
      val check        = "fn check(ok: Bool): Int = if ok then 0; else 1;;;"
      val source =
        if forward then s"$check\n$main\n$declarations"
        else s"$check\n$declarations\n$main"

      withDirectory { directory =>
        val binary = directory.resolve("program")
        val config = CompilerConfig.default.copy(
          mode       = CompilationMode.Exe,
          outputDir  = directory,
          outputName = binary.toString.some,
          asan       = true
        )
        for
          ir <- compileAndGenerate(source, "GlobalLiterals", config)
          pair <- targetAt(directory, config)
          (triple, target) = pair
          path <- IO.blocking(Files.writeString(directory.resolve("GlobalLiterals.ll"), ir))
          _ <- commandNotFailed(List("llvm-as", path.toString, "-o", "/dev/null"), directory)
          result <- LlvmToolchain.compile(path, config, triple.some, target)
          _ = assertEquals(result._1, Right(0))
          _ <- programExits(List(binary.toString), directory, expectedCode = 0)
        yield ()
      }
  }

  test("IntArray fill initializes every element and accepts empty arrays") {
    val source = """
      fn check(ok: Bool): Int = if ok then 0; else 1;;;
      fn all_equal(arr: IntArray, value: Int, i: Int): Bool =
        if i < ar_int_len arr then
          ar_int_get arr i == value and all_equal arr value (i + 1);
        else
          true;
        ;
      ;
      pub fn main(): Int =
        let empty = ar_int_new 0;
        let negative = ar_int_new (0 - 5);
        let single = ar_int_new 1;
        let many = ar_int_new 17;
        ar_int_fill empty 123456789;
        ar_int_fill negative (-2147483648);
        ar_int_fill single 2147483647;
        ar_int_fill many 123456789;
        let initial = check (ar_int_len empty == 0) + check (ar_int_len negative == 0) +
          check (ar_int_get single 0 == 2147483647) +
          check (all_equal many 123456789 0);
        ar_int_fill many 0;
        let zero = check (all_equal many 0 0);
        ar_int_fill many (-2147483648);
        let minimum = check (all_equal many (-2147483648) 0);
        ar_int_fill many 2147483647;
        initial + zero + minimum + check (all_equal many 2147483647 0) +
          check (ar_int_len many == 17);
      ;
    """
    withDirectory { directory =>
      val binary = directory.resolve("program")
      val config = CompilerConfig.default.copy(
        mode       = CompilationMode.Exe,
        outputDir  = directory,
        outputName = binary.toString.some,
        asan       = true
      )
      for
        ir <- compileAndGenerate(source, "ArrayFill", config)
        pair <- targetAt(directory, config)
        (triple, target) = pair
        path <- IO.blocking(Files.writeString(directory.resolve("ArrayFill.ll"), ir))
        _ <- commandNotFailed(List("llvm-as", path.toString, "-o", "/dev/null"), directory)
        result <- LlvmToolchain.compile(path, config, triple.some, target)
        _ = assertEquals(result._1, Right(0))
        _ <- programExits(List(binary.toString), directory, expectedCode = 0)
      yield ()
    }
  }

  test("Int32 literal boundaries and explicit widening execute correctly") {
    val source = """
      fn check(ok: Bool): Int = if ok then 0; else 1;;;
      fn convert(n: Int): Int = float_to_int (int_to_float n);;
      fn min64(): Int64 = @native[tpl="add i64 0, -9223372036854775808"];;
      fn clone_array(a: IntArray): IntArray = @native[name="__clone_IntArray", mem=alloc];;
      fn at(a: IntArray, i: Int): Int = ar_int_get a i;;
      let greeting = "global";
      pub fn main(args: StringArray): Int =
        let local = "local";
        let min = -2147483648;
        let max = 2147483647;
        let arr = ar_int_new 2;
        ar_int_set arr 0 min;
        ar_int_set arr 1 max;
        let copied = clone_array arr;
        ar_int_set arr 0 0;
        let get = at copied;
        let capture = ~{ x: Int -> x + min };
        check (str_eq (int_to_str min) "-2147483648") +
        check (str_eq (int_to_str max) "2147483647") +
        check (str_to_int "-2147483648" == min) +
        check (str_to_int "+2147483647" == max) +
        check (max + 1 == min) + check (min - 1 == max) +
        check (convert (0 - 12345) == (0 - 12345)) +
        check (get 0 == min and get 1 == max) +
        check (capture 0 == min) + check (arr.length == 2) +
        check (greeting.length == 6) + check (local.length == 5) +
        check (args.length == 1 and str_eq (ar_str_get args 0) "argument") +
        check (str_eq (int64_to_str (int_to_int64 min)) "-2147483648") +
        check (str_eq (int64_to_str (min64 ())) "-9223372036854775808");
      ;
    """
    withDirectory { directory =>
      val config = CompilerConfig.default.copy(
        mode       = CompilationMode.Exe,
        outputDir  = directory,
        outputName = directory.resolve("program").toString.some,
        asan       = true
      )
      for
        ir <- compileAndGenerate(source, "Int32", config)
        pair <- targetAt(directory, config)
        (triple, target) = pair
        path <- IO.blocking {
          val runtimeDirectory = directory.resolve(s"out/$triple")
          Files.createDirectories(runtimeDirectory)
          Files.writeString(runtimeDirectory.resolve("mml_runtime.c"), "#error stale runtime\n")
          Files.writeString(directory.resolve("Int32.ll"), ir)
        }
        result <- LlvmToolchain.compile(
          path,
          config.copy(mode = CompilationMode.Library, outputName = none),
          triple.some,
          target
        )
        _ = assertEquals(result._1, Right(0))
        _ <- commandNotFailed(
          List(
            target.executable.toString,
            "-fuse-ld=lld",
            "-fsanitize=address,undefined",
            directory.resolve("target/int32.o").toString,
            directory.resolve(s"target/mml_runtime-$triple.o").toString,
            "-lm",
            "-o",
            directory.resolve("program").toString
          ),
          directory
        )
        _ <- commandNotFailed(List(directory.resolve("program").toString, "argument"), directory)
      yield ()
    }
  }

  test("runtime Int32 layouts, parsing, formatting, RNG and narrowing pass sanitizers") {
    val driver = """
      #include <assert.h>
      #include <signal.h>
      static String text(char *s) { return (String){checked_length(strlen(s)), s}; }
      static void rejects(int mode) {
        pid_t child = fork();
        assert(child >= 0);
        if (child == 0) {
          if (mode == 0) str_to_int(text("2147483648"));
          if (mode == 1) str_to_int(text("-2147483649"));
          if (mode == 2) str_to_int(text("999999999999999999999999999"));
          if (mode == 3) checked_length((size_t)INT32_MAX + 1);
          if (mode == 4) concat((String){INT32_MAX, "x"}, text("x"));
          if (mode == 5) substring(text("x"), -1, 1);
          _exit(0);
        }
        int status;
        assert(waitpid(child, &status, 0) == child);
        assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT);
      }
      int main(void) {
        _Static_assert(sizeof(((String *)0)->length) == 4, "String length");
        _Static_assert(sizeof(((IntArray *)0)->length) == 4, "Array length");
        _Static_assert(sizeof(*((IntArray *)0)->data) == 4, "Array storage");
        _Static_assert(sizeof(((RngImpl *)0)->state) == 8, "RNG state");
        _Static_assert(sizeof(((BufferImpl *)0)->capacity) == sizeof(size_t), "Allocation size");
        char formatted[32];
        assert(format_int64(formatted, sizeof(formatted), INT64_MIN) == 20);
        assert(memcmp(formatted, "-9223372036854775808", 20) == 0);
        assert(str_to_int(text("-2147483648")) == INT32_MIN);
        assert(str_to_int(text("2147483647")) == INT32_MAX);
        IntArray a = ar_int_new(2);
        ar_int_set(a, 0, INT32_MIN); ar_int_set(a, 1, INT32_MAX);
        IntArray b = __clone_IntArray(a);
        ar_int_set(a, 0, 0);
        assert(ar_int_get(b, 0) == INT32_MIN && ar_int_get(b, 1) == INT32_MAX);
        __free_IntArray(a); __free_IntArray(b);
        Rng rng = rng_new(INT32_MIN);
        for (int i = 0; i < 10000; ++i) {
          assert(rng_next(rng) >= 0);
          int32_t value = rng_between(rng, INT32_MIN, INT32_MAX);
          assert(value >= INT32_MIN && value < INT32_MAX);
          assert(rng_between(rng, -1, 0) == -1);
        }
        __free_Rng(rng);
        String slice = substring(text("abcd"), 1, INT32_MAX);
        assert(str_eq(slice, text("bcd"))); __free_String(slice);
        for (int mode = 0; mode < 6; ++mode) rejects(mode);
        return 0;
      }
    """
    withDirectory { directory =>
      for
        pair <- targetAt(directory, CompilerConfig.default)
        (_, target) = pair
        runtime <- Resource
          .fromAutoCloseable(
            IO.blocking(
              getClass.getClassLoader.getResourceAsStream("mml_runtime.c")
            )
          )
          .use(stream => IO.blocking(new String(stream.readAllBytes(), UTF_8)))
        path <- IO.blocking(
          Files.writeString(directory.resolve("runtime-test.c"), runtime + driver)
        )
        _ <- commandNotFailed(
          List(
            target.executable.toString,
            "-std=c17",
            "-O2",
            "-fsanitize=address,undefined",
            "-fno-sanitize-recover=all",
            path.toString,
            "-o",
            directory.resolve("runtime-test").toString
          ),
          directory
        )
        _ <- programExits(
          List(directory.resolve("runtime-test").toString),
          directory,
          expectedCode = 0
        )
      yield ()
    }
  }

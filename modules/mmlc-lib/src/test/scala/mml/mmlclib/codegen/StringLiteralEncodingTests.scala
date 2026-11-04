package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.Files

class StringLiteralEncodingTests extends BaseEffFunSuite:

  private val unicodeSource =
    """
      |let latin = "é";
      |let greek = "λ";
      |let emoji = "😀";
      |let mixed = "aéλ😀z";
      |let ascii = "plain";
      |let empty = "";
      |let raw = "\n";
      |let multiline = "line
      |break";
      |
      |fn check(output: Buffer, value: String, expected: Int): Int =
      |  buffer_write output value;
      |  buffer_write output "|";
      |  if value.length == expected then 0; else 1;;
      |;
      |
      |pub fn main(): Int =
      |  let fd = open_file_write "literal-bytes";
      |  let output = mkBufferWithFd fd;
      |  let globals =
      |    check output latin 2 + check output greek 2 + check output emoji 4 +
      |    check output mixed 10 + check output ascii 5 + check output empty 0 +
      |    check output raw 2 + check output multiline 10;
      |  let expressions =
      |    check output "é" 2 + check output "λ" 2 + check output "😀" 4 +
      |    check output "aéλ😀z" 10 + check output "plain" 5 + check output "" 0 +
      |    check output "\n" 2 + check output "line
      |break" 10;
      |  let copy = ^mixed;
      |  let joined = latin ++ greek;
      |  let owned = check output copy 10 + check output joined 4;
      |  flush output;
      |  close_file fd;
      |  globals + expressions + owned;
      |;
      |""".stripMargin

  private val literalBytes = List(
    // Each literal is followed by the fixture's | separator.
    0xc3, 0xa9, 0x7c, // é
    0xce, 0xbb, 0x7c, // λ
    0xf0, 0x9f, 0x98, 0x80, 0x7c, // 😀
    0x61, 0xc3, 0xa9, 0xce, 0xbb, 0xf0, 0x9f, 0x98, 0x80, 0x7a, 0x7c, // aéλ😀z
    0x70, 0x6c, 0x61, 0x69, 0x6e, 0x7c, // plain
    0x7c, // empty
    0x5c, 0x6e, 0x7c, // raw backslash and n
    // line, newline, break
    0x6c, 0x69, 0x6e, 0x65, 0x0a, 0x62, 0x72, 0x65, 0x61, 0x6b, 0x7c
  )

  private val ownedBytes = List(
    0x61, 0xc3, 0xa9, 0xce, 0xbb, 0xf0, 0x9f, 0x98, 0x80, 0x7a, 0x7c, // clone of aéλ😀z
    0xc3, 0xa9, 0xce, 0xbb, 0x7c // é ++ λ
  )

  /** Checks native string lengths in the fixture and compares its output without text decoding. */
  private def assertLiteralBytes(
    source:   String,
    name:     String,
    optLevel: Int,
    expected: List[Int]
  ): IO[Unit] =
    withSourceFixture(source, s"$name.mml") { (path, initialConfig) =>
      val directory = path.getParent
      val binary    = directory.resolve("program")
      val config = initialConfig.copy(
        mode       = CompilationMode.Exe,
        outputName = binary.toString.some,
        optLevel   = optLevel,
        asan       = true
      )

      for
        compiled <- CompilerApi.compileNativeQuiet(path, config)
        _ = compiled.fold(error => fail(error), state => assertEquals(state.errors, Vector.empty))
        _ <- programExits(
          List("env", "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1", binary.toString),
          directory,
          expectedCode = 0
        )
        bytes <- IO.blocking(Files.readAllBytes(directory.resolve("literal-bytes")))
        _ = assertEquals(bytes.toList.map(_ & 0xff), expected)
      yield ()
    }

  test("ASCII and empty literals retain their byte lengths and output"):
    val source = """
      let global = "ASCII";
      fn check(output: Buffer, value: String, expected: Int): Int =
        buffer_write output value;
        buffer_write output "|";
        if value.length == expected then 0; else 1;;
      ;
      pub fn main(): Int =
        let fd = open_file_write "literal-bytes";
        let output = mkBufferWithFd fd;
        let errors = check output global 5 + check output "" 0;
        flush output;
        close_file fd;
        errors;
      ;
    """
    assertLiteralBytes(source, "AsciiLiterals", 0, List(0x41, 0x53, 0x43, 0x49, 0x49, 0x7c, 0x7c))

  List(0, 3).foreach { optimization =>
    test(
      s"UTF-8 global and expression literals preserve byte lengths and output at O$optimization"
    ):
      assertLiteralBytes(
        unicodeSource,
        "Utf8Literals",
        optimization,
        literalBytes ++ literalBytes ++ ownedBytes
      )
  }

package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

import MmlWhitespace.*

class TypeSourceSpanTests extends BaseEffFunSuite:

  private def assertSpan(
    source:   String,
    node:     FromSource,
    start:    Int,
    end:      Int,
    expected: String
  ): Unit =
    val location = node.spanOpt.getOrElse(fail("Expected written source span"))
    assertEquals((location.start.index, location.end.index), (start, end))
    assertEquals(source.substring(location.start.index, location.end.index), expected)

  private def alias(module: Module): TypeAlias =
    module.members match
      case (value: TypeAlias) :: Nil => value
      case other => fail(s"Expected one type alias, found $other")

  private def definition(module: Module): TypeDef =
    module.members match
      case (value: TypeDef) :: Nil => value
      case other => fail(s"Expected one native type definition, found $other")

  private def requiredType(value: Option[Type]): Type =
    value.getOrElse(fail("Expected a written type"))

  List("", " \t", " // trailing\n", " // trailing\r\n").zipWithIndex.foreach {
    case (trailing, index) =>
      test(s"type aliases and nested function types exclude appended trivia $index") {
        val declaration = "type Alias = (Int -> String) -> Boolean;"
        val source      = declaration + trailing

        parseNotFailed(source).map { module =>
          val value = alias(module)
          assertSpan(source, value, 0, 40, declaration)
          assertSpan(source, value.nameNode, 5, 10, "Alias")
          assertSpan(source, value.typeRef, 13, 39, "(Int -> String) -> Boolean")

          value.typeRef match
            case function: TypeFn =>
              assertSpan(source, function.returnType, 32, 39, "Boolean")
              function.paramTypes.head match
                case group: TypeGroup =>
                  assertSpan(source, group, 13, 28, "(Int -> String)")
                  assertEquals(group.types.size, 1)
                  assertSpan(source, group.types.head, 14, 27, "Int -> String")
                case other => fail(s"Expected a grouped parameter type, found $other")
            case other => fail(s"Expected a function type, found $other")
        }
      }
  }

  test("struct fields stop at their type while the declaration owns its semicolon") {
    val declaration = "struct Box { value: (Int -> String), other: Boolean };"
    val source      = declaration + " // trailing\r\n"

    parseNotFailed(source).map { module =>
      module.members match
        case (record: TypeStruct) :: Nil =>
          assertSpan(source, record, 0, 54, declaration)
          assertSpan(source, record.nameNode, 7, 10, "Box")
          assertEquals(record.fields.size, 2)
          val first = record.fields.head
          val last  = record.fields.last
          assertSpan(source, first.nameNode, 13, 18, "value")
          assertSpan(source, first, 13, 35, "value: (Int -> String)")
          assertSpan(source, first.typeSpec, 20, 35, "(Int -> String)")
          assertSpan(source, last.nameNode, 37, 42, "other")
          assertSpan(source, last, 37, 51, "other: Boolean")
          assertSpan(source, last.typeSpec, 44, 51, "Boolean")
        case other => fail(s"Expected one struct, found $other")
    }
  }

  test("native integer validation preserves the fragment endpoint before trailing comments") {
    val source = "i64 // trailing\r\n]"
    val info   = SourceInfo(source)
    def primitive(using P[Any]): P[NativePrimitive] =
      P(nativePrimitiveTypeP(info) ~ "]" ~ End)

    parse(source, primitive(using _)) match
      case Parsed.Success(value, _) =>
        assertEquals(value.llvmType, "i64")
        assertSpan(source, value, 0, 3, "i64")
      case failure: Parsed.Failure => fail(failure.trace().longMsg)
  }

  test("native pointer spans include the star and end at the validated pointee") {
    val source = "*i8 // trailing\n]"
    val info   = SourceInfo(source)
    def pointer(using P[Any]): P[NativePointer] =
      P(nativePointerTypeP(info) ~ "]" ~ End)

    parse(source, pointer(using _)) match
      case Parsed.Success(value, _) => assertSpan(source, value, 0, 3, "*i8")
      case failure: Parsed.Failure => fail(failure.trace().longMsg)
  }

  test("native bracket types end at the bracket before validation consumes trivia") {
    val source = "type Native = @metal:repr[t=i64] // repr\r\n; // outer\r\n"

    parseNotFailed(source).map { module =>
      val value = definition(module)
      assertSpan(source, value.nameNode, 5, 11, "Native")
      assertSpan(source, requiredType(value.typeSpec), 14, 32, "@metal:repr[t=i64]")
      assertSpan(source, value, 0, 43, "type Native = @metal:repr[t=i64] // repr\r\n;")
    }
  }

  test("native pointer attributes belong to the representation span") {
    val source = "type Native = @metal:repr[t=*i8, mem=heap] // repr\n; // outer\n"

    parseNotFailed(source).map { module =>
      val value = definition(module)
      assertSpan(
        source,
        requiredType(value.typeSpec),
        14,
        42,
        "@metal:repr[t=*i8, mem=heap]"
      )
      assertSpan(source, value, 0, 52, "type Native = @metal:repr[t=*i8, mem=heap] // repr\n;")
    }
  }

  test("native struct spans end at their brace and field types exclude the following space") {
    val source = "type Native = @metal:repr[mem=heap] { value: Int } // repr\n; // outer\n"

    parseNotFailed(source).map { module =>
      val value = definition(module)
      requiredType(value.typeSpec) match
        case record: NativeStruct =>
          assertSpan(source, record, 14, 50, "@metal:repr[mem=heap] { value: Int }")
          assertEquals(record.fields.size, 1)
          assertSpan(source, record.fields.head._2, 45, 48, "Int")
        case other => fail(s"Expected a native struct, found $other")

      assertSpan(
        source,
        value,
        0,
        60,
        "type Native = @metal:repr[mem=heap] { value: Int } // repr\n;"
      )
    }
  }

  test("closed malformed type groups exclude the comment before the next application term") {
    val source = "let value = item: (Int, String) // annotation\r\n next;"

    parseNotFailed(source).map { module =>
      module.members match
        case (binding: Bnd) :: Nil =>
          assertEquals(binding.value.terms.size, 2)
          requiredType(binding.value.terms.head.typeAsc) match
            case error: ParseTypeError =>
              assertSpan(source, error, 18, 31, "(Int, String)")
              assertEquals(error.failedCode, Some("(Int, String)"))
            case other => fail(s"Expected a malformed group, found $other")
        case other => fail(s"Expected one binding, found $other")
    }
  }

  test("scanner-owned malformed type text keeps whitespace and comments up to its boundary") {
    val source = "type Broken = Int -> wrong  // failed\n;"

    parseNotFailed(source).map { module =>
      alias(module).typeRef match
        case function: TypeFn =>
          function.returnType match
            case error: ParseTypeError =>
              assertSpan(source, error, 21, 38, "wrong  // failed\n")
              assertEquals(error.failedCode, Some("wrong  // failed\n"))
            case other => fail(s"Expected failed return type, found $other")
        case other => fail(s"Expected a function type, found $other")
    }
  }

package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.parser.Parser
import mml.mmlclib.test.BaseEffFunSuite

class NativeTypeTests extends BaseEffFunSuite:

  test("parse native primitive type - i32"):
    val code = """
      type Int = @native[t=i32];
    """

    Parser
      .parseModule(code, "Test")
      .fold(
        e => fail(s"Parser failed: $e"),
        module => {
          val typeDef = module.members.collectFirst { case t: TypeDef => t }.get
          assertEquals(typeDef.name, "Int")
          typeDef.typeSpec match
            case Some(NativePrimitive(_, llvmType, _)) =>
              assertEquals(llvmType, "i32")
            case _ =>
              fail("Expected NativePrimitive")
        }
      )

  test("parse native primitive type - float"):
    val code = """
      type Float32 = @native[t=float];
    """

    Parser
      .parseModule(code, "Test")
      .fold(
        e => fail(s"Parser failed: $e"),
        module => {
          val typeDef = module.members.collectFirst { case t: TypeDef => t }.get
          assertEquals(typeDef.name, "Float32")
          typeDef.typeSpec match
            case Some(NativePrimitive(_, llvmType, _)) =>
              assertEquals(llvmType, "float")
            case _ =>
              fail("Expected NativePrimitive")
        }
      )

  test("parse native pointer type"):
    val code = """
      type CharPtr = @native[t=*i8];
    """

    Parser
      .parseModule(code, "Test")
      .fold(
        e => fail(s"Parser failed: $e"),
        module => {
          val typeDef = module.members.collectFirst { case t: TypeDef => t }.get
          assertEquals(typeDef.name, "CharPtr")
          typeDef.typeSpec match
            case Some(NativePointer(_, llvmType, _)) =>
              assertEquals(llvmType, "i8")
            case _ =>
              fail("Expected NativePointer")
        }
      )

  test("parse native struct type"):
    val code = """
      type MyStruct = @native {
        length: SomeType,
        data: AnotherType
      };
    """

    Parser
      .parseModule(code, "Test")
      .fold(
        e => fail(s"Parser failed: $e"),
        module => {
          // Grammar tests shouldn't test semantic phase injections
          // Just look for the parsed TypeDef
          val typeDefs = module.members.collect { case t: TypeDef => t }
          val myStruct = typeDefs.find(_.name == "MyStruct")

          myStruct match {
            case None =>
              fail(
                s"No MyStruct TypeDef found. Got TypeDefs: ${typeDefs.map(_.name).mkString(", ")}"
              )
            case Some(typeDef) =>
              assertEquals(typeDef.name, "MyStruct")
              typeDef.typeSpec match
                case Some(NativeStruct(_, fieldsList, _)) =>
                  val fields = fieldsList.toMap
                  assertEquals(fields.size, 2)
                  assert(fields.contains("length"), "Expected field 'length'")
                  assert(fields.contains("data"), "Expected field 'data'")
                  fields("length") match
                    case TypeRef(_, "SomeType", _, _) => // good
                    case _ => fail(s"Expected TypeRef to SomeType, got: ${fields("length")}")
                  fields("data") match
                    case TypeRef(_, "AnotherType", _, _) => // good
                    case _ => fail(s"Expected TypeRef to AnotherType, got: ${fields("data")}")
                case Some(other) =>
                  fail(s"Expected NativeStruct, got: $other")
                case None =>
                  fail("Expected typeSpec to be defined")
          }
        }
      )

  test("parse all floating point types"):
    val floatTypes = List("half", "bfloat", "float", "double", "fp128")

    floatTypes.foreach { floatType =>
      val code = s"""
        type Test = @native[t=$floatType];
      """

      Parser
        .parseModule(code, "Test")
        .fold(
          e => fail(s"Parser failed for $floatType: $e"),
          module => {
            val typeDef = module.members.collectFirst { case t: TypeDef => t }.get
            typeDef.typeSpec match
              case Some(NativePrimitive(_, llvmType, _)) =>
                assertEquals(llvmType, floatType)
              case _ =>
                fail(s"Expected NativePrimitive for $floatType")
          }
        )
    }

  test("parse various integer types"):
    val intBits = List(1, 8, 16, 32, 64, 128)

    intBits.foreach { bits =>
      val code = s"""
        type Test = @native[t=i$bits];
      """

      Parser
        .parseModule(code, "Test")
        .fold(
          e => fail(s"Parser failed for i$bits: $e"),
          module => {
            val typeDef = module.members.collectFirst { case t: TypeDef => t }.get
            typeDef.typeSpec match
              case Some(NativePrimitive(_, llvmType, _)) =>
                assertEquals(llvmType, s"i$bits")
              case _ =>
                fail(s"Expected NativePrimitive for i$bits")
          }
        )
    }

  test("reject invalid integer bit widths"):
    val invalidBits = List(2, 7, 24, 48, 256)

    invalidBits.foreach { bits =>
      val code = s"""
        type Test = @native[t=i$bits];
      """

      Parser.parseModule(code, "Test") match
        case Left(err) =>
          // Good - parser completely failed
          assert(err.toString.contains("Failure"), s"Expected parse failure for i$bits")
        case Right(module) =>
          // Check if it parsed as a MemberError (which means the native type parser failed)
          module.members.find(_.isInstanceOf[ParsingMemberError]) match
            case Some(ParsingMemberError(_, message, _)) =>
              // Good - the member failed to parse due to invalid bit width
              assert(message.contains("Failed to parse member"))
            case _ =>
              // Check if a valid TypeDef was created
              module.members.collectFirst { case t: TypeDef if t.name == "Test" => t } match
                case Some(td) =>
                  fail(s"Should not parse i$bits as a valid TypeDef, but got: ${td.typeSpec}")
                case None =>
                  // No Test TypeDef found, which is good
                  ()
    }

  test("parse complex native struct"):
    val code = """
      type ComplexStruct = @native {
        field1: TypeA,
        field2: TypeB,
        ptr: TypeC,
        nested: TypeD
      };
    """

    Parser
      .parseModule(code, "Test")
      .fold(
        e => fail(s"Parser failed: $e"),
        module => {
          val typeDefs      = module.members.collect { case t: TypeDef => t }
          val complexStruct = typeDefs.find(_.name == "ComplexStruct")

          complexStruct match {
            case None =>
              fail(
                s"No ComplexStruct TypeDef found. Got TypeDefs: ${typeDefs.map(_.name).mkString(", ")}"
              )
            case Some(typeDef) =>
              typeDef.typeSpec match
                case Some(NativeStruct(_, fieldsList, _)) =>
                  val fields = fieldsList.toMap
                  assertEquals(fields.size, 4)
                  assertEquals(fields.keySet, Set("field1", "field2", "ptr", "nested"))
                  // Verify the field types are parsed as TypeRefs
                  fields.foreach { case (name, typeSpec) =>
                    typeSpec match
                      case TypeRef(_, _, _, _) => // good
                      case _ => fail(s"Field $name should have TypeRef, got: $typeSpec")
                  }
                case Some(other) =>
                  fail(s"Expected NativeStruct, got: $other")
                case None =>
                  fail("Expected typeSpec to be defined")
          }
        }
      )

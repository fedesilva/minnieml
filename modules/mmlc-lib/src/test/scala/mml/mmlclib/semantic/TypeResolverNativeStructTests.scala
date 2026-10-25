package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class TypeResolverNativeStructTests extends BaseEffFunSuite:
  // Note: Using type names that don't clash with predefined types (String, CharPtr, SizeT, etc.)
  // The semantic package automatically adds these common types to every module

  test("TypeResolver should resolve type references in NativeStruct fields"):
    val code = """
      type MySize = @metal:repr[t=i64];
      type MyCharPtr = @metal:repr[t=*i8];
      type MyString = @metal:repr {
        length: MySize,
        data: MyCharPtr
      };
    """

    semNotFailed(code).map { module =>
      // Find the MyString type definition
      val stringDef = module.members.collectFirst {
        case t: TypeDef if t.name == "MyString" => t
      }.get

      // Check that it's a NativeStruct
      stringDef.typeSpec match
        case Some(NativeStruct(_, fieldsList, _, _)) =>
          val fields = fieldsList.toMap
          assertEquals(fields.size, 2)

          // Check length field
          fields("length") match
            case TypeRef(_, "MySize", resolvedId, _) =>
              assert(resolvedId.isDefined, "Expected MySize TypeRef to be resolved")
              val resolved = resolvedId.flatMap(module.resolvables.lookupType)
              assertEquals(resolved.map(_.name), Some("MySize"))
            case other =>
              fail(s"Expected resolved TypeRef for length field, got: $other")

          // Check data field
          fields("data") match
            case TypeRef(_, "MyCharPtr", resolvedId, _) =>
              assert(resolvedId.isDefined, "Expected MyCharPtr TypeRef to be resolved")
              val resolved = resolvedId.flatMap(module.resolvables.lookupType)
              assertEquals(resolved.map(_.name), Some("MyCharPtr"))
            case other =>
              fail(s"Expected resolved TypeRef for data field, got: $other")

        case other =>
          fail(s"Expected NativeStruct, got: $other")
    }

  test("TypeResolver should handle nested type references in complex structs"):
    val code = """
      type MyInt32 = @metal:repr[t=i32];
      type MyInt64 = @metal:repr[t=i64];
      type MyStrPtr = @metal:repr[t=*i8];

      type ComplexStruct = @metal:repr {
        field1: MyInt32,
        field2: MyInt64,
        name: MyStrPtr
      };
    """

    semNotFailed(code).map { module =>
      val complexStruct = module.members.collectFirst {
        case t: TypeDef if t.name == "ComplexStruct" => t
      }.get

      complexStruct.typeSpec match
        case Some(NativeStruct(_, fields, _, _)) =>
          assertEquals(fields.size, 3)

          // Verify all fields are resolved
          fields.foreach { case (fieldName, fieldType) =>
            fieldType match
              case TypeRef(_, typeName, resolvedId, _) =>
                assert(
                  resolvedId.isDefined,
                  s"Expected $typeName to be resolved for field $fieldName"
                )
              case other =>
                fail(s"Expected TypeRef for field $fieldName, got: $other")
          }
        case other =>
          fail(s"Expected NativeStruct, got: $other")
    }

  test("TypeResolver should report errors for undefined types in NativeStruct fields"):
    val code = """
      type MyStruct = @metal:repr {
        valid: Int64,
        invalid: UndefinedType
      };
    """

    semFailed(code).map { _ =>
      // The test passes if semFailed succeeds (meaning semantic analysis failed as expected)
    }

  test("TypeResolver should handle type aliases in NativeStruct fields"):
    val code = """
      type BaseInt = @metal:repr[t=i32];
      type MyInt = BaseInt;

      type MyStruct = @metal:repr {
        value: MyInt
      };
    """

    semNotFailed(code).map { module =>
      val myStruct = module.members.collectFirst {
        case t: TypeDef if t.name == "MyStruct" => t
      }.get

      myStruct.typeSpec match
        case Some(NativeStruct(_, fieldsList, _, _)) =>
          val fields = fieldsList.toMap
          fields("value") match
            case TypeRef(_, "MyInt", resolvedId, _) =>
              assert(resolvedId.isDefined, "Expected MyInt to be resolved")
              val resolved = resolvedId.flatMap(module.resolvables.lookupType)
              assertEquals(resolved.map(_.name), Some("MyInt"))
            case other =>
              fail(s"Expected resolved TypeRef for value field, got: $other")
        case other =>
          fail(s"Expected NativeStruct, got: $other")
    }

  test("TypeResolver should handle empty NativeStruct"):
    val code = """
      type EmptyStruct = @metal:repr {};
    """

    semNotFailed(code).map { module =>
      val emptyStruct = module.members.collectFirst {
        case t: TypeDef if t.name == "EmptyStruct" => t
      }.get

      emptyStruct.typeSpec match
        case Some(NativeStruct(_, fields, _, _)) =>
          assertEquals(fields.size, 0)
        case other =>
          fail(s"Expected NativeStruct, got: $other")
    }

  test("native type fields accumulate independent errors without losing valid fields"):
    val code = """
      type Record = @metal:repr {
        first: MissingFirst,
        valid: Int64,
        last: MissingLast
      };
    """
    parseNotFailed(code).map { parsed =>
      val initial = IdAssigner.rewriteModule(
        CompilerState.empty(injectBasicTypes(parsed), SourceInfo(code), CompilerConfig.default)
      )
      val original = initial.module.members.collectFirst {
        case definition: TypeDef if definition.source.isFromSource => definition
      }.get
      val originalFields = original.typeSpec match
        case Some(native: NativeStruct) => native.fields.map(_._2)
        case other => fail(s"Expected native struct, got $other")
      val resolved = TypeResolver.rewriteModule(initial)
      val definition = resolved.module.resolvables.lookupType(original.id.get) match
        case Some(definition: TypeDef) => definition
        case other => fail(s"Expected indexed type definition, got $other")
      val fields = definition.typeSpec match
        case Some(native: NativeStruct) => native.fields.map(_._2)
        case other => fail(s"Expected retained native struct, got $other")
      val errors = fields.collect { case error: UndefinedTypeError => error }

      assertEquals(
        resolved.errors.map(_.message),
        Vector(
          "Undefined type: 'MissingFirst'",
          "Undefined type: 'MissingLast'"
        )
      )
      assertEquals(errors.map(_.originalType), List(originalFields.head, originalFields.last))
      assertEquals(
        errors.map(_.source),
        List(originalFields.head.source, originalFields.last.source)
      )
      assertEquals(errors.flatMap(_.causes).toVector, resolved.diagnostics.records.map(_.id))
      fields(1) match
        case valid: TypeRef =>
          assertEquals(valid.source, originalFields(1).source)
          val target = valid.resolvedId.flatMap(resolved.module.resolvables.lookupType)
          assert(target.nonEmpty)
          assertEquals(target.flatMap(_.id), valid.resolvedId)
        case other => fail(s"Expected resolved valid field, got $other")
    }

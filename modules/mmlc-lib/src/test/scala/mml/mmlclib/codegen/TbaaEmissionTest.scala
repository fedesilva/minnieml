package mml.mmlclib.codegen

import cats.data.NonEmptyList
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.tbaa.StructLayout
import mml.mmlclib.test.BaseEffFunSuite

class TbaaEmissionTest extends BaseEffFunSuite:

  test("generates distinct TBAA field tags for struct fields") {
    val source = """
      fn main(): Unit =
        println "hello";
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      // String struct should have TBAA with proper field offsets
      assert(llvmIr.contains("!{!\"String\""), s"Missing String TBAA node")

      // Field access tags should have different offsets
      val lines     = llvmIr.split("\n")
      val tbaaLines = lines.filter(_.contains("!{!"))

      // Find the String struct node - should have int@0 and ptr@8
      val stringNode = tbaaLines.find(_.contains("\"String\""))
      assert(stringNode.isDefined, "Missing String TBAA struct node")
      assert(
        stringNode.get.contains("i64 0") && stringNode.get.contains("i64 8"),
        s"String TBAA node missing field offsets: ${stringNode.get}"
      )
    }
  }

  test("TBAA has proper hierarchy: root, MML types, struct, access tags") {
    val source = """
      fn main(): Unit =
        println "test";
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      // Verify MML type nodes exist (String fields are Int64 and CharPtr)
      assert(llvmIr.contains("!{!\"Int64\""), "Missing Int64 TBAA node")
      assert(llvmIr.contains("!{!\"CharPtr\""), "Missing CharPtr TBAA node")

      // Verify struct node references MML types with correct offsets
      // String has 2 fields: length (Int64 @ 0), data (CharPtr @ 8)
      val stringMatch = """!\{!"String", !(\d+), i64 0, !(\d+), i64 8\}""".r
      assert(
        stringMatch.findFirstIn(llvmIr).isDefined,
        s"String TBAA should reference MML types with offsets. TBAA:\n${llvmIr.split("\n").filter(_.startsWith("!")).mkString("\n")}"
      )
    }
  }

  test("stores to different struct fields use different TBAA tags") {
    val source = """
      fn main(): Unit =
        println "hello";
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      // Find store instructions with tbaa tags
      val storeLines = llvmIr.split("\n").filter(l => l.contains("store") && l.contains("!tbaa"))

      // Should have at least 2 stores (length and data fields)
      assert(
        storeLines.length >= 2,
        s"Expected at least 2 stores with TBAA, found: ${storeLines.length}"
      )

      // Extract tbaa tag references
      val tbaaRefs = storeLines.flatMap { line =>
        """!tbaa (!(\d+))""".r.findFirstMatchIn(line).map(_.group(1))
      }

      // The tags should be different for different fields
      assert(
        tbaaRefs.distinct.length >= 2,
        s"Expected different TBAA tags for different fields, found: ${tbaaRefs.mkString(", ")}"
      )
    }
  }

  test("TBAA field offsets honor alignment (String has ptr at offset 8, not 4)") {
    val source = """
      fn main(): Unit =
        println "test";
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      // String is { i64, ptr } - both fields are 8-byte aligned
      // Field 0 (i64): offset 0
      // Field 1 (ptr): offset 8 (not 4, because ptr needs 8-byte alignment)
      val stringNode = llvmIr.split("\n").find(_.contains("\"String\""))
      assert(stringNode.isDefined, "Missing String TBAA struct node")

      // Verify offsets are 0 and 8, not 0 and some other value
      val offsetPattern = """i64 (\d+)""".r
      val offsets       = offsetPattern.findAllMatchIn(stringNode.get).map(_.group(1).toInt).toList

      assert(
        offsets.contains(0) && offsets.contains(8),
        s"String TBAA should have fields at offsets 0 and 8, found: $offsets in: ${stringNode.get}"
      )
    }

    test("loads and stores include alias scope metadata") {
      val source = """
      fn main(): Unit =
        println "alias";
      ;
    """

      compileAndGenerate(source).map { llvmIr =>
        assert(llvmIr.contains("!alias.scope"), "Alias scope metadata missing")
        assert(llvmIr.contains("!noalias"), "Noalias metadata missing")
        assert(
          llvmIr.contains("alias.scope:Int64") || llvmIr.contains("alias.scope:String"),
          s"Alias scope metadata should mention a known MML type. IR snippet:\n${llvmIr.split("\n").filter(_.contains("alias.scope")).mkString("\n")}"
        )

        val storeLines =
          llvmIr.split("\n").filter(line => line.contains("store") && line.contains("!alias.scope"))
        assert(
          storeLines.nonEmpty,
          s"Expected at least one store with alias.scope metadata, found: ${storeLines.mkString("\n")}"
        )

        val loadLines =
          llvmIr.split("\n").filter(line => line.contains("load") && line.contains("!alias.scope"))
        assert(
          loadLines.nonEmpty,
          s"Expected at least one load with alias.scope metadata, found: ${loadLines.mkString("\n")}"
        )
      }
    }
  }

  private val span = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  private val int64Id   = "int64-id"
  private val int32Id   = "int32-id"
  private val charPtrId = "charptr-id"

  private val int64Def =
    TypeDef(
      source   = SourceOrigin.Loc(span),
      nameNode = Name.synth("Int64"),
      typeSpec = Some(NativePrimitive(span, "i64")),
      id       = Some(int64Id)
    )

  private val int32Def =
    TypeDef(
      source   = SourceOrigin.Loc(span),
      nameNode = Name.synth("Int32"),
      typeSpec = Some(NativePrimitive(span, "i32")),
      id       = Some(int32Id)
    )

  private val charPtrDef =
    TypeDef(
      source   = SourceOrigin.Loc(span),
      nameNode = Name.synth("CharPtr"),
      typeSpec = Some(NativePointer(span, "i8")),
      id       = Some(charPtrId)
    )

  private val int64   = TypeRef(span, "Int64", Some(int64Id), Nil)
  private val int32   = TypeRef(span, "Int32", Some(int32Id), Nil)
  private val charPtr = TypeRef(span, "CharPtr", Some(charPtrId), Nil)

  private val layoutBuiltinTypes =
    ResolvablesIndex()
      .updatedType(int64Def)
      .updatedType(int32Def)
      .updatedType(charPtrDef)

  test("StructLayout computes size and alignment for TypeFn fat pointers") {
    val fnType = TypeFn(
      span,
      NonEmptyList.one(int64),
      int64
    )

    assertEquals(StructLayout.sizeOf(fnType, ResolvablesIndex()), Right(16))
    assertEquals(StructLayout.alignOf(fnType, ResolvablesIndex()), Right(8))
  }

  test("closure env TBAA handles captured function values") {
    val source = """
      fn main(): Int =
        fn inc(x: Int): Int = x + 1;;
        fn applyInc(y: Int): Int = inc y;;
        applyInc 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      // Borrow env: no dtor field, just the captured fat pointer
      val closureEnvTypePattern =
        """%struct\.__closure_env_\d+ = type \{ \{ ptr, ptr \} \}""".r
      assert(
        closureEnvTypePattern.findFirstIn(llvmIr).isDefined,
        s"Missing borrow closure env type with fat-pointer field. IR:\n$llvmIr"
      )

      val functionScalarId = """!(\d+) = !\{!"Function", !\d+, i64 0\}""".r
        .findFirstMatchIn(llvmIr)
        .map(_.group(1))
      assert(functionScalarId.isDefined, s"Missing Function TBAA scalar node. IR:\n$llvmIr")

      val closureEnvTbaaLines =
        llvmIr.split("\n").filter(_.matches("""!\d+ = !\{!"__closure_env_\d+".*"""))
      assert(
        closureEnvTbaaLines.nonEmpty,
        s"Missing closure env TBAA node. IR:\n$llvmIr"
      )
      // Borrow env: captured fn at offset 0 (no dtor field)
      assert(
        closureEnvTbaaLines.exists(_.contains(s"!${functionScalarId.get}, i64 0")),
        s"Expected borrow closure env TBAA field at offset 0 to use Function scalar. Nodes:\n${closureEnvTbaaLines.mkString("\n")}"
      )
    }
  }

  test("TBAA preserves alias identity for aliased struct fields") {
    val source = """
      type MyInt = Int64;
      struct Box {
        value: MyInt
      };

      fn main(): Int64 =
        let b = Box 1;
        0;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val myIntScalarId = """!(\d+) = !\{!"MyInt", !0, i64 0\}""".r
        .findFirstMatchIn(llvmIr)
        .map(_.group(1))
        .getOrElse(fail(s"Expected distinct TBAA node for alias type. IR:\n$llvmIr"))

      val boxNode = llvmIr
        .split("\n")
        .find(_.matches("""!\d+ = !\{!"Box", !\d+, i64 0\}"""))
        .getOrElse(fail(s"Expected Box TBAA node. IR:\n$llvmIr"))

      assert(
        boxNode.contains(s"!$myIntScalarId, i64 0"),
        s"Expected Box field TBAA to use alias identity, got: $boxNode"
      )
    }
  }

  test("StructLayout computes correct size for nested structs") {
    // Create Inner = { i64, i64 } -> 16 bytes
    val innerStruct = NativeStruct(
      span,
      List(
        ("a", int64),
        ("b", int64)
      )
    )
    val innerTypeDef =
      TypeDef(
        source   = SourceOrigin.Loc(span),
        nameNode = Name.synth("Inner"),
        typeSpec = Some(innerStruct),
        id       = Some("inner-id")
      )
    val innerTypeRef = TypeRef(span, "Inner", Some("inner-id"), Nil)

    // Create resolvables index for the lookup
    val resolvables = layoutBuiltinTypes.updatedType(innerTypeDef)

    // Create Outer = { i64, Inner, i64 } -> should be 32 bytes
    // Field 0 (i64): offset 0, size 8
    // Field 1 (Inner): offset 8, size 16
    // Field 2 (i64): offset 24, size 8
    // Total: 32 bytes
    val outerStruct = NativeStruct(
      span,
      List(
        ("x", int64),
        ("inner", innerTypeRef),
        ("y", int64)
      )
    )

    // Test Inner size
    assertEquals(StructLayout.sizeOf(innerStruct, resolvables), Right(16))
    assertEquals(StructLayout.alignOf(innerStruct, resolvables), Right(8))

    // Test Outer size - this would fail without the P1 fix (would return 24 instead of 32)
    assertEquals(StructLayout.sizeOf(outerStruct, resolvables), Right(32))
    assertEquals(StructLayout.alignOf(outerStruct, resolvables), Right(8))
  }

  test("StructLayout computes correct offsets for struct with i32 followed by nested struct") {
    // Create Inner = { i64, ptr } -> 16 bytes, 8-byte aligned
    val innerStruct = NativeStruct(
      span,
      List(
        ("len", int64),
        ("data", charPtr)
      )
    )
    val innerTypeDef =
      TypeDef(
        source   = SourceOrigin.Loc(span),
        nameNode = Name.synth("Inner"),
        typeSpec = Some(innerStruct),
        id       = Some("inner-id")
      )
    val innerTypeRef = TypeRef(span, "Inner", Some("inner-id"), Nil)

    // Create resolvables index for the lookup
    val resolvables = layoutBuiltinTypes.updatedType(innerTypeDef)

    // Create Outer = { i32, Inner }
    // Field 0 (i32): offset 0, size 4
    // Padding: 4 bytes (to align Inner to 8)
    // Field 1 (Inner): offset 8, size 16
    // Total: 24 bytes
    val outerStruct = NativeStruct(
      span,
      List(
        ("tag", int32),
        ("inner", innerTypeRef)
      )
    )

    assertEquals(StructLayout.sizeOf(outerStruct, resolvables), Right(24))
    assertEquals(StructLayout.alignOf(outerStruct, resolvables), Right(8))
  }

package mml.mmlclib.codegen

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.tbaa.StructLayout
import mml.mmlclib.test.BaseEffFunSuite

class TbaaEmissionTest extends BaseEffFunSuite:

  test("generates distinct TBAA field tags for struct fields") {
    val source = """
      fn main(): Unit =
        println "hello"
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
        println "test"
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
        println "hello"
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
        println "test"
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
        println "alias"
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

  test("StructLayout computes correct size for nested structs") {
    // Create Inner = { i64, i64 } -> 16 bytes
    val innerStruct = NativeStruct(
      span,
      List(
        ("a", NativePrimitive(span, "i64")),
        ("b", NativePrimitive(span, "i64"))
      )
    )
    val innerTypeDef =
      TypeDef(
        span     = span,
        nameNode = Name.synth("Inner"),
        typeSpec = Some(innerStruct),
        id       = Some("inner-id")
      )
    val innerTypeRef = TypeRef(span, "Inner", Some("inner-id"), Nil)

    // Create resolvables index for the lookup
    val resolvables = ResolvablesIndex().updatedType(innerTypeDef)

    // Create Outer = { i64, Inner, i64 } -> should be 32 bytes
    // Field 0 (i64): offset 0, size 8
    // Field 1 (Inner): offset 8, size 16
    // Field 2 (i64): offset 24, size 8
    // Total: 32 bytes
    val outerStruct = NativeStruct(
      span,
      List(
        ("x", NativePrimitive(span, "i64")),
        ("inner", innerTypeRef),
        ("y", NativePrimitive(span, "i64"))
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
        ("len", NativePrimitive(span, "i64")),
        ("data", NativePointer(span, "i8"))
      )
    )
    val innerTypeDef =
      TypeDef(
        span     = span,
        nameNode = Name.synth("Inner"),
        typeSpec = Some(innerStruct),
        id       = Some("inner-id")
      )
    val innerTypeRef = TypeRef(span, "Inner", Some("inner-id"), Nil)

    // Create resolvables index for the lookup
    val resolvables = ResolvablesIndex().updatedType(innerTypeDef)

    // Create Outer = { i32, Inner }
    // Field 0 (i32): offset 0, size 4
    // Padding: 4 bytes (to align Inner to 8)
    // Field 1 (Inner): offset 8, size 16
    // Total: 24 bytes
    val outerStruct = NativeStruct(
      span,
      List(
        ("tag", NativePrimitive(span, "i32")),
        ("inner", innerTypeRef)
      )
    )

    assertEquals(StructLayout.sizeOf(outerStruct, resolvables), Right(24))
    assertEquals(StructLayout.alignOf(outerStruct, resolvables), Right(8))
  }

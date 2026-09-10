package mml.mmlclib.test.llvm

import mml.mmlclib.test.BaseEffFunSuite

class LlvmAssertionsTests extends BaseEffFunSuite:
  private val definitions = """define i64 @itemXvalue(i64 %arg) #0 {
entry:
  ret i64 %arg
}
define { ptr, ptr } @item.value(ptr %arg) #1 {
entry:
  ret { ptr, ptr } zeroinitializer
}
"""

  test("literal function lookup escapes symbol names and isolates the selected body") {
    assertEquals(
      functionBody(definitions, "item.value"),
      "entry:\n  ret { ptr, ptr } zeroinitializer"
    )
  }

  test("signature lookup preserves parameter and attribute constraints") {
    assertEquals(
      functionBodyMatching(definitions, "item\\.value\\(ptr %arg\\) #1"),
      functionBody(definitions, "item.value")
    )
    val error = intercept[munit.FailException] {
      functionBodyMatching(definitions, "item\\.value\\(i64 %arg\\) #1")
    }
    assert(error.getMessage.contains("Missing function definition"))
  }

  test("function lookup rejects missing and ambiguous definitions") {
    val missing = intercept[munit.FailException] { functionBody(definitions, "absent") }
    assert(missing.getMessage.contains("Missing function definition"))
    val ambiguous = intercept[munit.FailException] { functionBodyMatching(definitions, "item.*") }
    assert(ambiguous.getMessage.contains("Ambiguous function definition"))
  }

  private val conditional = """entry:
  br i1 %condition, label %merge, label %right
right:
  br label %merge
merge: ; preds = %right, %entry
  %value = phi i64 [ 1, %entry ], [ 2, %right ]
  ret i64 %value
"""

  test("phi checking includes conditional edges and leaves the expected count to its caller") {
    assertEquals(phiCount(conditional), 1)
    assertPhiPredecessors(conditional)
    assertPhiPredecessors(
      conditional.replace("[ 1, %entry ], [ 2, %right ]", "[ 2, %right ], [ 1, %entry ]")
    )
  }

  test("phi checking rejects missing, extra, wrong and duplicated predecessors") {
    List(
      "[ 1, %entry ]",
      "[ 1, %entry ], [ 2, %right ], [ 3, %merge ]",
      "[ 1, %entry ], [ 2, %merge ]",
      "[ 1, %entry ], [ 2, %entry ]"
    ).foreach { incoming =>
      val body  = conditional.replace("[ 1, %entry ], [ 2, %right ]", incoming)
      val error = intercept[munit.ComparisonFailException] { assertPhiPredecessors(body) }
      assert(error.getMessage.contains("Phi predecessor mismatch in merge"))
    }
  }

  test("phi checking supports loop backedges") {
    val body = """entry:
  br label %loop
loop:
  %i = phi i64 [ 0, %entry ], [ %next, %loop ]
  %next = add i64 %i, 1
  %again = icmp slt i64 %next, 10
  br i1 %again, label %loop, label %exit
exit:
  ret i64 %next
"""
    assertEquals(phiCount(body), 1)
    assertPhiPredecessors(body)
  }

  test("phi checking preserves multiple edges from the same predecessor") {
    val body = """entry:
  br i1 %condition, label %merge, label %merge
merge:
  %value = phi i64 [ 1, %entry ], [ 1, %entry ]
  ret i64 %value
"""
    assertPhiPredecessors(body)
    intercept[munit.ComparisonFailException] {
      assertPhiPredecessors(body.replace(", [ 1, %entry ]", ""))
    }
  }

  test("phi checking accepts a function without phis") {
    val body = "entry:\n  ret void\n"
    assertEquals(phiCount(body), 0)
    assertPhiPredecessors(body)
  }

  test("unsupported terminators fail explicitly") {
    val body  = "entry:\n  switch i32 %value, label %exit []\nexit:\n  ret void\n"
    val error = intercept[munit.FailException] { assertPhiPredecessors(body) }
    assert(error.getMessage.contains("Unsupported or missing terminator"))
  }

package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.ast.Bnd
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.lsp.{Diagnostics, Position, Range}
import mml.mmlclib.test.BaseEffFunSuite

class ProtocolDiagnosticTests extends BaseEffFunSuite:

  test("a missing instance reports one diagnostic at the called signature"):
    val source = """protocol Keep 'T = fn keep(value: 'T): 'T; ;
                   |let result = keep 1;
                   |""".stripMargin
    checkedState(source).map { state =>
      assertEquals(state.visibleErrors.size, 1)
      assertEquals(
        Diagnostics.fromCompilerState(state).map(error => error.message -> error.range),
        List(
          "No instance of protocol 'Keep' for Int32 provides 'keep'" ->
            Range(Position(1, 13), Position(1, 17))
        )
      )
    }

  test("incompatible Num operands report one diagnostic at the operator"):
    checkedState("let result = 1 + true;").map { state =>
      assertEquals(state.visibleErrors.size, 1)
      assertEquals(
        Diagnostics.fromCompilerState(state).map(error => error.message -> error.range),
        List(
          "No signature of '+' matches the arguments and expected type" ->
            Range(Position(0, 15), Position(0, 16))
        )
      )
    }

  test("a call without type evidence requests an annotation at the called signature"):
    val source = """protocol Make 'T = fn make(): 'T; ;
                   |instance Make Int = fn make() = 1;; ;
                   |instance Make Bool = fn make() = true;; ;
                   |let result = make ();
                   |""".stripMargin
    checkedState(source).map { state =>
      assertEquals(state.visibleErrors.size, 1)
      assertEquals(
        Diagnostics.fromCompilerState(state).map(error => error.message -> error.range),
        List(
          "Not enough type information to select 'make'; add a type annotation" ->
            Range(Position(3, 13), Position(3, 17))
        )
      )
    }

  test("two applicable signatures report ambiguity at the call"):
    val source = """protocol First 'T = fn keep(value: 'T): 'T; ;
                   |protocol Second 'T = fn keep(value: 'T): 'T; ;
                   |instance First Int = fn keep(value) = value;; ;
                   |instance Second Int = fn keep(value) = value;; ;
                   |let result = keep 1;
                   |""".stripMargin
    checkedState(source).map { state =>
      assertEquals(state.visibleErrors.size, 1)
      assertEquals(
        Diagnostics.fromCompilerState(state).map(error => error.message -> error.range),
        List(
          "More than one signature of 'keep' matches the arguments and expected type" ->
            Range(Position(4, 13), Position(4, 17))
        )
      )
    }

  test("a grouping conflict reports the conflicting declaration even when the operator is used"):
    val source = """protocol First 'T = op <+>(left: 'T, right: 'T): 'T 60 left; ;
                   |protocol Second 'T = op <+>(left: 'T, right: 'T): 'T 60 right; ;
                   |let result = 1 <+> 2 <+> 3;
                   |""".stripMargin
    checkedState(source).map { state =>
      assertEquals(state.visibleErrors.size, 1)
      assertEquals(
        Diagnostics.fromCompilerState(state).map(error => error.message -> error.range),
        List(
          "Operator '<+>' has conflicting precedence or associativity for the same arity" ->
            Range(Position(1, 24), Position(1, 27))
        )
      )
    }

  test("a conflicting prefix operator retains only its declaration diagnostic"):
    val source = """protocol First 'T = op !(value: 'T): 'T 95 right; ;
                   |protocol Second 'T = op !(value: 'T): 'T 80 right; ;
                   |let result = !1;
                   |""".stripMargin
    checkedState(source).map { state =>
      assertEquals(state.visibleErrors.size, 1)
      assertEquals(
        Diagnostics.fromCompilerState(state).map(_.message),
        List("Operator '!' has conflicting precedence or associativity for the same arity")
      )
    }

  test("a binary grouping conflict preserves a valid unary use of the same symbol"):
    val source = """protocol DifferentPlus 'T = op +(left: 'T, right: 'T): 'T 80 left; ;
                   |let result = +1;
                   |""".stripMargin
    checkedState(source).map { state =>
      assertEquals(state.visibleErrors.size, 1)
      assertEquals(
        Diagnostics.fromCompilerState(state).map(_.message),
        List("Operator '+' has conflicting precedence or associativity for the same arity")
      )
      val result = state.module.members
        .collectFirst {
          case binding: Bnd if binding.name == "result" => binding
        }
        .getOrElse(fail("Expected the independently valid result binding"))
      val index = state.module.resolvables
      assertEquals(
        result.typeSpec.map(ProtocolRegistry.typeKey(_, index)),
        Some(ProtocolRegistry.typeKey(typeReference("Int", index), index))
      )
    }

  test("an undefined operand remains visible beside a grouping conflict"):
    val source = """protocol First 'T = op <+>(left: 'T, right: 'T): 'T 60 left; ;
                   |protocol Second 'T = op <+>(left: 'T, right: 'T): 'T 60 right; ;
                   |let result = 1 <+> missing;
                   |""".stripMargin
    checkedState(source).map { state =>
      assertEquals(state.visibleErrors.size, 2)
      assertEquals(
        Diagnostics.fromCompilerState(state).map(_.message).toSet,
        Set(
          "Undefined reference: missing",
          "Operator '<+>' has conflicting precedence or associativity for the same arity"
        )
      )
    }

  test("unrelated dangling terms remain visible beside a grouping conflict"):
    val source = """protocol First 'T = op <+>(left: 'T, right: 'T): 'T 60 left; ;
                   |protocol Second 'T = op <+>(left: 'T, right: 'T): 'T 60 right; ;
                   |let result = 1 <+> 2;
                   |let unrelated = 1 2;
                   |""".stripMargin
    checkedState(source).map { state =>
      assertEquals(state.visibleErrors.size, 2)
      val dangling = state.visibleErrors.collect { case error: SemanticError.DanglingTerms =>
        error
      }
      assertEquals(dangling.size, 1)
      val span = dangling.head.terms.head.source.spanOpt.get
      assertEquals(source.substring(span.start.index, span.end.index), "2")
      assertEquals(span.start.line, 4)
    }

  private def checkedState(source: String): IO[CompilerState] =
    FrontEndApi.compile(source, "Test").value.map {
      case Right(state) => state
      case Left(error) => fail(s"Expected a compiler state with diagnostics: $error")
    }

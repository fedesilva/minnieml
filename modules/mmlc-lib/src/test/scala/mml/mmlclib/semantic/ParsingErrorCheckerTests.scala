package mml.mmlclib.semantic

import cats.syntax.option.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class ParsingErrorCheckerTests extends BaseEffFunSuite:

  private def tokenSpan(code: String, token: String): SrcSpan =
    val start = code.indexOf(token)
    assert(start >= 0, s"Missing fixture token: $token")
    val info = SourceInfo(code)
    SrcSpan(info.pointAt(start), info.pointAt(start + token.length))

  private def recoveredTree(value: Any): Any = value match
    case error:   ParseTermError => error.copy(causes = Vector.empty)
    case product: Product =>
      (product.productPrefix, product.productIterator.map(recoveredTree).toList)
    case other => other

  private def assertTermRoots(state: CompilerState): Unit =
    val recovered = state.module.members.collect { case binding: Bnd =>
      TermTraversal.collect(binding.value) { case error: ParseTermError => error }
    }.flatten
    val records = state.diagnostics.records.collect {
      case record if record.error.isInstanceOf[SemanticError.ParseTermErrorFound] => record
    }
    assertEquals(recovered.size, records.size)
    recovered.zip(records).foreach { (error, record) =>
      assertEquals(error.causes, Vector(record.id))
      assertEquals(record.relation, DiagnosticRelation.Primary)
      record.error match
        case SemanticError.ParseTermErrorFound(original, _) =>
          assertEquals(error.copy(causes = Vector.empty), original)
        case other => fail(s"Expected parser diagnostic, got $other")
    }

  private def recoveredFormTest(
    form:        String,
    expression:  String,
    failedNames: List[String]
  )(select: PartialFunction[Term, Term]): Unit =
    test(s"parser recovery stamps nested errors inside $form") {
      val code = s"fn example(): Int = $expression;;"
      justParse(code).map { module =>
        val containers = module.members
          .collect { case binding: Bnd =>
            TermTraversal.collect(binding.value)(select)
          }
          .flatten
          .filter(_.spanOpt.exists(_.start.index == code.indexOf(expression)))
        assertEquals(containers.size, 1, module)
        assertEquals(
          TermTraversal.collect(containers.head) { case error: ParseTermError => error }.size,
          failedNames.size
        )

        val state  = CompilerState.empty(module, SourceInfo(code), CompilerConfig.default)
        val result = ParsingErrorChecker.checkModule(state)
        val errors = result.errors.collect { case SemanticError.ParseTermErrorFound(error, _) =>
          error
        }
        assertEquals(result.errors.size, failedNames.size, result.errors)
        assertEquals(errors.map(_.failedCode).toList, failedNames.map(_.some))
        errors.zip(failedNames).foreach { (error, name) =>
          assertEquals(error.message, s"Invalid identifier '$name'")
          assertEquals(error.spanOpt, tokenSpan(code, name).some)
        }
        assertEquals(recoveredTree(result.module), recoveredTree(module))
        assertTermRoots(result)
      }
    }

  recoveredFormTest("a group", "(let 123invalid = 1; 2)", List("123invalid")) {
    case group: TermGroup => group
  }

  recoveredFormTest(
    "a tuple",
    "((let 123invalid = 1; 2), (let 456invalid = 3; 4))",
    List("123invalid", "456invalid")
  ) { case tuple: Tuple => tuple }

  recoveredFormTest(
    "a scoped application callee and argument",
    "let value = (let 123invalid = 1; 2); let 456invalid = 3; 4",
    List("456invalid", "123invalid")
  ) { case app: App => app }

  recoveredFormTest(
    "a qualified reference",
    "(let 123invalid = 1; 2).length",
    List("123invalid")
  ) { case ref: Ref if ref.qualifier.nonEmpty => ref }

  test("Full semantic pipeline passes correct modules") {
    semNotFailed(
      """
      fn valid(a: Int, b: Int): Int = a + b;;
      let x = 42;
      """
    ).map { module =>
      // Successfully passes through the semantic pipeline
      assert(module.members.size > 0)
    }
  }

  test("Full semantic pipeline integration with MemberErrorChecker") {
    semFailed(
      """
      fn valid(a: Int, b: Int): Int = a + b;;
      let x  ; // Missing expression after =
      """
    )
  }

  test("MemberErrorChecker should pass a module with no errors") {
    justParse(
      """
      fn valid(a: Int, b: Int): Int = a + b;;
      let x = 42;
      """
    ).map { module =>
      val state  = CompilerState.empty(module, SourceInfo(""), CompilerConfig.default)
      val result = ParsingErrorChecker.checkModule(state)
      assert(result.errors.isEmpty)
      assertNoDiff(result.module.toString, module.toString)
    }
  }

  test("MemberErrorChecker should catch term errors nested inside expressions") {
    val code =
      """
      let x =
        let 123invalid = 5;
        6;
      ;
      """

    justParse(code).map { module =>
      val state  = CompilerState.empty(module, SourceInfo(code), CompilerConfig.default)
      val result = ParsingErrorChecker.checkModule(state)

      val errors = result.errors.collect { case SemanticError.ParseTermErrorFound(error, _) =>
        error
      }
      assertEquals(errors.size, 1)
      val error = errors.head
      assertEquals(error.message, "Invalid identifier '123invalid'")
      assertEquals(error.failedCode, "123invalid".some)

      val span = error.spanOpt.getOrElse(fail("Expected nested error source span"))
      assertEquals(span, tokenSpan(code, "123invalid"))
      val recoveredSource = state.sourceInfo.slice(span.start.index, span.end.index)
      assertEquals(recoveredSource, "123invalid")
      val literals = result.module.members
        .collect { case binding: Bnd =>
          TermTraversal.collect(binding.value) { case literal: LiteralInt => literal.value }
        }
        .flatten
        .sorted
      assertEquals(literals, List(5L, 6L))

      val memberErrors = result.errors.collect { case SemanticError.MemberErrorFound(error, _) =>
        error
      }
      assertEquals(memberErrors.map(_.failedCode).toList, List(";".some))
      assertEquals(result.errors.size, 2)
      assertEquals(recoveredTree(result.module), recoveredTree(module))
      assertTermRoots(result)
    }
  }

  test("ParsingErrorChecker accumulates nested branch errors with existing diagnostics") {
    val code =
      """
      fn broken(flag: Bool): Int =
        if flag then
          let 123invalid = 5;
          6;
        else
          let 456invalid = 7;
          8;
        ;
      ;
      let 789invalid = 9;
      let after = 10;
      """

    justParse(code).map { module =>
      val existing = SemanticError.InvalidEntryPoint("existing diagnostic", SourceOrigin.Synth)
      val state = CompilerState
        .empty(module, SourceInfo(code), CompilerConfig.default)
        .addError(existing)
      val result = ParsingErrorChecker.checkModule(state)
      assertEquals(result.errors.head, existing)

      val nestedErrors = result.errors.collect { case SemanticError.ParseTermErrorFound(error, _) =>
        error
      }
      assertEquals(
        nestedErrors.map(_.failedCode).toList,
        List("123invalid".some, "456invalid".some)
      )

      val idErrors = result.errors.collect { case SemanticError.ParsingIdErrorFound(error, _) =>
        error
      }
      assertEquals(idErrors.map(_.invalidId).toList, List("789invalid"))

      assertEquals(result.errors.size, 4)
      assertEquals(recoveredTree(result.module), recoveredTree(module))
      assertTermRoots(result)
      assert(module.members.exists {
        case binding: Bnd => binding.name == "after"
        case _ => false
      })
    }
  }

  test("Full semantic pipeline rejects invalid local function names at their source") {
    val code =
      """
      fn outer(): Int =
        fn 123invalid(x: Int): Int = x;;
        6;
      ;
      """

    semState(code).map { result =>
      val nestedErrors = result.errors.collect { case SemanticError.ParseTermErrorFound(error, _) =>
        error
      }
      nestedErrors match
        case List(error) =>
          assertEquals(error.message, "Invalid identifier '123invalid'")
          assertEquals(error.failedCode, "123invalid".some)

          val span = error.spanOpt.getOrElse(fail("Expected local function source span"))
          assertEquals(span, tokenSpan(code, "123invalid"))
        case other => fail(s"Expected one nested invalid function name, got $other")
    }
  }

  test("missing member components retain the named binding and both parser diagnostics") {
    val code = """
      fn valid(a: Int, b: Int): Int = a + b;;
      let a  ; // Missing equals and initializer
    """
    semVisibleState(code).map { result =>
      val errors = result.errors.collect { case SemanticError.ParseTermErrorFound(error, _) =>
        error
      }
      assertEquals(errors.map(_.message), List("Expected expression", "Expected '='"))
      assertEquals(result.errors.size, 2, result.errors)
      val point = SourceInfo(code).pointAt(code.indexOf(';', code.indexOf("let a")))
      assertEquals(errors.map(_.spanOpt), List.fill(2)(SrcSpan(point, point).some))
      val retained = result.module.members
        .collectFirst {
          case binding: Bnd if binding.name == "a" => binding
        }
        .getOrElse(fail("Expected retained source binding"))
      assert(retained.id.nonEmpty)
      assert(!ValueAvailability.fromModule(result.module).isAvailable(retained.value))
    }
  }

  test("nested member-component failures coexist with an independent unrecognized member") {
    val code = """
      fn valid(a: Int, b: Int): Int = a + b;;
      let a  ; // Missing equals and initializer
      bnd noLet = 5;
    """
    semVisibleState(code).map { result =>
      val componentErrors = result.errors.collect {
        case SemanticError.ParseTermErrorFound(error, _) => error
      }
      val memberErrors = result.errors.collect { case SemanticError.MemberErrorFound(error, _) =>
        error
      }
      assertEquals(componentErrors.map(_.message), List("Expected expression", "Expected '='"))
      assertEquals(memberErrors.map(_.message), List("Failed to parse member"))
      assertEquals(memberErrors.map(_.failedCode), List("bnd noLet = 5;".some))
      assertEquals(result.errors.size, 3, result.errors)
      assert(result.module.members.exists {
        case binding: Bnd => binding.name == "a" && binding.id.nonEmpty
        case _ => false
      })
    }
  }

  test("equal fresh recovered occurrences receive distinct identities") {
    parseNotFailed("let value = 1;").map { module =>
      val binding = module.members
        .collectFirst { case binding: Bnd => binding }
        .getOrElse(fail("Expected value binding"))
      val error = ParseTermError(binding.value.source, "Malformed term", "1".some)
      val recovered = module.copy(members =
        List(
          binding.copy(value = binding.value.copy(terms = List(error, error)))
        )
      )
      val state = CompilerState.empty(
        recovered,
        SourceInfo("let value = 1;"),
        CompilerConfig.default
      )
      val result = ParsingErrorChecker.checkModule(state)

      assertEquals(result.errors.size, 2)
      assertEquals(result.diagnostics.records.map(_.id).distinct.size, 2)
      assertEquals(recoveredTree(result.module), recoveredTree(recovered))
      assertTermRoots(result)
    }
  }

package mml.mmlclib.parser

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

class GenericFunctionParserTests extends BaseEffFunSuite:

  private def binding(module: Module): Bnd = module.members
    .collectFirst { case value: Bnd =>
      value
    }
    .getOrElse(fail("Expected binding"))

  private def lambda(module: Module): Lambda = binding(module).value.terms match
    case (value: Lambda) :: Nil => value
    case other => fail(s"Expected lambda, got $other")

  private def written(source: String, node: FromSource): String =
    val location = node.spanOpt.getOrElse(fail("Expected written span"))
    source.substring(location.start.index, location.end.index)

  test("generic signatures retain variable spellings, ownership and result annotations") {
    val source = "fn identity(~value: 'T): 'T = value;;"
    parseNotFailed(source).map { module =>
      val function = lambda(module)
      assert(function.params.head.consuming)
      assertEquals(
        function.params.head.typeAsc.collect { case v: TypeVariable => v.name },
        Some("'T")
      )
      assertEquals(function.typeAsc.map(written(source, _)), Some("'T"))
      assertEquals(function.typeParams, Nil)
      assertEquals(function.constraints, Nil)
    }
  }

  test("declared constraints preserve an omitted result annotation") {
    val source = "fn render(value: 'T) where 'T: Show & Clone, 'U: Show = show value;;"
    parseNotFailed(source).map { module =>
      val function = lambda(module)
      assertEquals(function.typeAsc, None)
      assertEquals(function.constraints.map(_.variable.name), List("'T", "'U"))
      assertEquals(
        function.constraints.map(_.constraints.map(_.nameNode.value)),
        List(List("Show", "Clone"), List("Show"))
      )
      assertEquals(
        function.constraints.map(written(source, _)),
        List("'T: Show & Clone", "'U: Show")
      )
      assert(function.constraints.forall(_.variable.resolvedId.isEmpty))
    }
  }

  test("nested signature types and body annotations admit variables") {
    val source = "fn apply(f: ('T -> 'U), value: 'T): 'U = value: 'U;;"
    parseNotFailed(source).map { module =>
      val function = lambda(module)
      val variables = function.params.flatMap(_.typeAsc).flatMap { tpe =>
        TypeTraversal.collect(tpe) { case v: TypeVariable => v.name }
      }
      assertEquals(variables, List("'T", "'U", "'T"))
      assertEquals(function.body.terms.head.typeAsc.map(written(source, _)), Some("'U"))
    }
  }

  test("grouped body annotations retain fixed type-constructor applications") {
    parseNotFailed("fn first(value: Box 'T): 'T = value: (Box 'T);;").map { module =>
      val applications = lambda(module).body.terms.flatMap(_.typeAsc).flatMap { tpe =>
        TypeTraversal.collect(tpe) { case a: TypeApplication => a }
      }
      assertEquals(applications.size, 1)
      assertEquals(applications.head.args.collect { case v: TypeVariable => v.name }, List("'T"))
    }
  }

  test("local functions retain constraints alongside their lambda") {
    val source = "fn outer(value: 'T): 'T = fn inner(x: 'T): 'T where 'T: Clone = x;; inner value;;"
    parseNotFailed(source).map { module =>
      val constrained = TermTraversal.collect(lambda(module).body) {
        case l: Lambda if l.constraints.nonEmpty => l
      }
      assertEquals(constrained.size, 1)
      assertEquals(
        constrained.head.constraints.head.constraints.map(_.nameNode.value),
        List("Clone")
      )
    }
  }

  test("fixed type constructors retain variable arguments in signatures") {
    parseNotFailed("fn first(value: Sequence 'T): 'T = ???;;").map { module =>
      lambda(module).params.head.typeAsc match
        case Some(application: TypeApplication) =>
          assertEquals(application.args.collect { case v: TypeVariable => v.name }, List("'T"))
        case other => fail(s"Expected type application, got $other")
    }
  }

  List("identity Int", "f Person", "f (Person)", "Person 42", "apply (Person: Int -> Person)")
    .foreach { expression =>
      test(s"ambiguous or constructor syntax retains its value interpretation: $expression") {
        parseNotFailed(s"let result = $expression;").map { module =>
          assertEquals(TermTraversal.collect(binding(module).value) { case a: FnTypeApp => a }, Nil)
          assert(TermTraversal.collect(binding(module).value) { case r: Ref => r }.nonEmpty)
        }
      }
    }

  private def typeSyntax(tpe: Type): String = tpe match
    case ref:      TypeRef => ref.name
    case variable: TypeVariable => variable.name
    case group:    TypeGroup => group.types.map(typeSyntax).mkString("(", ", ", ")")
    case function: TypeFn =>
      (function.paramTypes.toList :+ function.returnType).map(typeSyntax).mkString(" -> ")
    case other => fail(s"Unexpected type argument: $other")

  List(
    "identity (Int -> Int)" -> List("(Int -> Int)"),
    "identity 'T" -> List("'T"),
    "choose Int ('T -> Int)" -> List("Int", "('T -> Int)")
  ).foreach { case (expression, expectedArgs) =>
    test(s"certain type argument retains a distinct candidate application: $expression") {
      val source = s"let result = $expression;"
      parseNotFailed(source).map { module =>
        binding(module).value.terms match
          case (application: FnTypeApp) :: Nil =>
            assertEquals(written(source, application), expression)
            assertEquals(application.args.map(typeSyntax), expectedArgs)
            assertEquals(application.args.map(written(source, _)), expectedArgs)
            val expectedStarts = expectedArgs
              .scanLeft(source.indexOf(" = ") + 3) { (start, arg) =>
                source.indexOf(arg, start) + arg.length
              }
              .tail
              .zip(expectedArgs)
              .map { case (end, arg) => (end - arg.length, end) }
            assertEquals(
              application.args.flatMap(_.spanOpt).map(s => (s.start.index, s.end.index)),
              expectedStarts
            )
          case other => fail(s"Expected function type application, got $other")
      }
    }
  }

  List("where", "where 'T Show", "where 'T: Show &", "where 'T: Show,", "where 'T: show").foreach {
    clause =>
      test(s"malformed constraint recovers body and following declaration: $clause") {
        val source = s"fn render(value: 'T): 'T $clause = value;; let after = 1;"
        justParse(source).map { module =>
          assertEquals(module.members.collect { case b: Bnd => b.name }, List("render", "after"))
          val errors = TermTraversal.collect(lambda(module).body) { case e: ParseTermError => e }
          assert(errors.exists(_.reason == SyntaxError.InvalidConstraints))
          val state = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
          assert(ParsingErrorChecker.checkModule(state).hasErrors)
        }
      }
  }

  test("missing equals after constraints retains the constraints and body") {
    val source = "fn render(value: 'T) where 'T: Show show value;;"
    justParse(source).map { module =>
      val function = lambda(module)
      assertEquals(function.constraints.size, 1)
      val references = TermTraversal.collect(function.body) { case r: Ref => r.name }
      assertEquals(references, List("show", "value"))
      val reasons = TermTraversal.collect(function.body) { case e: ParseTermError => e.reason }
      assertEquals(reasons, List(SyntaxError.ExpectedToken("=")))
    }
  }

  test("malformed grouped type argument retains a type diagnostic") {
    val source = "let result = identity (Int ->); let after = 1;"
    justParse(source).map { module =>
      assertEquals(module.members.collect { case b: Bnd => b.name }, List("result", "after"))
      val initial = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val checked = ParsingErrorChecker.checkModule(initial)
      val errors = checked.errors.collect { case SemanticError.ParseTypeErrorFound(error, _) =>
        error
      }
      assertEquals(errors.size, 1)
      assertEquals(errors.head.reason, SyntaxError.ExpectedType)
      val missing = source.indexOf(')')
      assertEquals(
        errors.head.spanOpt.map(s => (s.start.index, s.end.index)),
        Some((missing, missing))
      )

      val arguments = TermTraversal
        .collect(binding(checked.module).value) { case a: FnTypeApp => a }
        .flatMap(_.args)
      val retained =
        arguments.flatMap(tpe => TypeTraversal.collect(tpe) { case e: ParseTypeError => e })
      assertEquals(retained.map(_.source), errors.map(_.source).toList)
      assertEquals(retained.map(_.reason), List(SyntaxError.ExpectedType))
      assert(retained.forall(_.causes.nonEmpty))
    }
  }

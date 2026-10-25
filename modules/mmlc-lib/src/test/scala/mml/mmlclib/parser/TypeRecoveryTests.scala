package mml.mmlclib.parser

import cats.effect.IO
import fastparse.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.test.BaseEffFunSuite

import scala.concurrent.duration.*

import MmlWhitespace.*

class TypeRecoveryTests extends BaseEffFunSuite:

  private def bindings(module: Module): List[Bnd] =
    module.members.collect { case binding: Bnd => binding }

  private def lambda(binding: Bnd): Lambda =
    binding.value.terms match
      case (value: Lambda) :: Nil => value
      case other => fail(s"Expected one lambda, found $other")

  private def requiredType(value: Option[Type]): Type =
    value.getOrElse(fail("Expected an explicit type"))

  private def typeNodes(value: Type): List[Type] =
    val children = value match
      case fn:    TypeFn => fn.paramTypes.toList :+ fn.returnType
      case group: TypeGroup => group.types
      case _ => Nil

    value :: children.flatMap(typeNodes)

  private def typeErrors(value: Type): List[ParseTypeError] =
    typeNodes(value).collect { case error: ParseTypeError => error }

  private def typeRefs(value: Type): List[TypeRef] =
    typeNodes(value).collect { case ref: TypeRef => ref }

  private def assertError(source: String, error: ParseTypeError, start: Int, end: Int): Unit =
    val expected = source.substring(start, end)
    assertEquals(
      error.spanOpt,
      Some(SrcSpan(SourceInfo(source).pointAt(start), SourceInfo(source).pointAt(end)))
    )
    assertEquals(error.failedCode, Option.when(expected.nonEmpty)(expected))
    assertEquals(error.message, if expected.isEmpty then "Expected type" else "Invalid type syntax")

  private def parseClean(source: String): IO[Module] =
    parseNotFailed(source).map { module =>
      val checked = ParsingErrorChecker.checkModule(
        CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      )
      assertEquals(checked.errors, Vector.empty)
      module
    }

  test("absent annotations stay absent while empty and malformed annotations retain exact errors") {
    val source = "let inferred = 1; let empty: = 2; let malformed: wrong = 3; let good: Int = 4;"
    parseNotFailed(source).map { module =>
      val values = bindings(module)
      assertEquals(values.size, 4)
      assertEquals(values.head.typeAsc, None)
      assertError(
        source,
        typeErrors(requiredType(values(1).typeAsc)).head,
        source.indexOf("= 2"),
        source.indexOf("= 2")
      )
      assertError(
        source,
        typeErrors(requiredType(values(2).typeAsc)).head,
        source.indexOf("wrong"),
        source.indexOf("= 3")
      )
      assertEquals(typeRefs(requiredType(values(3).typeAsc)).map(_.name), List("Int"))
      assertEquals(
        values.flatMap(_.value.terms.collect { case value: LiteralInt => value.value }),
        List(1L, 2L, 3L, 4L)
      )
    }
  }

  test("function arrows retain valid siblings around malformed and missing components") {
    val source = "type Broken = Int -> wrong -> String -> ; type Good = Boolean;"
    parseNotFailed(source).map { module =>
      val aliases = module.members.collect { case alias: TypeAlias => alias }
      assertEquals(aliases.size, 2)
      val tpe = aliases.head.typeRef
      assertEquals(typeRefs(tpe).map(_.name), List("Int", "String"))
      val errors = typeErrors(tpe)
      assertEquals(errors.size, 2)
      assertError(source, errors.head, source.indexOf("wrong"), source.indexOf("-> String"))
      assertError(source, errors(1), source.indexOf(';'), source.indexOf(';'))
      assertEquals(typeRefs(aliases(1).typeRef).map(_.name), List("Boolean"))
    }
  }

  test("closed groups retain nested errors and exact group spans") {
    val source = "let value: (Int -> (wrong -> String)) -> Boolean = 1;"
    parseNotFailed(source).map { module =>
      val tpe = requiredType(bindings(module).head.typeAsc)
      assertEquals(typeRefs(tpe).map(_.name), List("Int", "String", "Boolean"))
      val errors = typeErrors(tpe)
      assertEquals(errors.size, 1)
      assertError(source, errors.head, source.indexOf("wrong"), source.indexOf("-> String"))
      tpe match
        case fn: TypeFn =>
          val group = fn.paramTypes.head
          assert(group.isInstanceOf[TypeGroup])
          assertEquals(
            group.spanOpt.map(s => source.substring(s.start.index, s.end.index)),
            Some("(Int -> (wrong -> String))")
          )
        case other => fail(s"Expected function type, found $other")
    }
  }

  test("a missing group closer replaces the bad group and preserves the declaration body") {
    val source = "let value: Int -> (String -> wrong = 42; let next = 7;"
    parseNotFailed(source).map { module =>
      val values = bindings(module)
      assertEquals(values.size, 2)
      val tpe = requiredType(values.head.typeAsc)
      assertEquals(typeRefs(tpe).map(_.name), List("Int"))
      assertEquals(typeErrors(tpe).size, 1)
      assertError(source, typeErrors(tpe).head, source.indexOf('('), source.indexOf("= 42"))
      assertEquals(
        values.head.value.terms.collect { case value: LiteralInt => value.value },
        List(42L)
      )
    }
  }

  List("(Int, String)", "(Int", "@ffi[t=i32]", "@ffi[t").foreach { malformed =>
    test(s"parameter recovery preserves a following sibling after '$malformed'") {
      val source = s"fn bad(a: $malformed, b: Boolean) = b;;"
      parseNotFailed(source).map { module =>
        val params = lambda(bindings(module).head).params
        assertEquals(params.size, 2)
        val errors = typeErrors(requiredType(params.head.typeAsc))
        assertEquals(errors.size, 1)
        assertError(
          source,
          errors.head,
          source.indexOf(malformed),
          source.indexOf(malformed) + malformed.length
        )
        assertEquals(typeRefs(requiredType(params(1).typeAsc)).map(_.name), List("Boolean"))
      }
    }

    test(s"field recovery preserves a following sibling after '$malformed'") {
      val source = s"struct Broken { a: $malformed, b: Boolean };"
      parseNotFailed(source).map { module =>
        val fields = module.members
          .collectFirst { case record: TypeStruct => record.fields }
          .getOrElse(fail("Expected struct"))
        assertEquals(fields.size, 2)
        val errors = typeErrors(fields.head.typeSpec)
        assertEquals(errors.size, 1)
        assertError(
          source,
          errors.head,
          source.indexOf(malformed),
          source.indexOf(malformed) + malformed.length
        )
        assertEquals(typeRefs(fields(1).typeSpec).map(_.name), List("Boolean"))
      }
    }
  }

  List("", " let next = 7;").foreach { following =>
    test(s"unmatched type brackets preserve the body equals before '$following'") {
      val source = s"let value: @ffi[t = 42;$following"
      parseNotFailed(source).map { module =>
        val values = bindings(module)
        assertEquals(values.size, if following.isEmpty then 1 else 2)
        val errors = typeErrors(requiredType(values.head.typeAsc))
        assertEquals(errors.size, 1)
        assertError(source, errors.head, source.indexOf('@'), source.indexOf("= 42"))
        assertEquals(
          values.head.value.terms.collect { case value: LiteralInt => value.value },
          List(42L)
        )
      }
    }
  }

  test("independent parameter errors preserve following parameters and the return type") {
    val source = "fn value(a:, b: wrong, c: Int ->, d: String): Boolean = true;;"
    parseNotFailed(source).map { module =>
      val value = lambda(bindings(module).head)
      assertEquals(value.params.size, 4)
      val annotations = value.params.map(param => requiredType(param.typeAsc))
      assertEquals(annotations.map(typeErrors(_).size), List(1, 1, 1, 0))
      assertEquals(typeRefs(annotations(2)).map(_.name), List("Int"))
      assertEquals(typeRefs(annotations(3)).map(_.name), List("String"))
      assertEquals(typeRefs(requiredType(value.typeAsc)).map(_.name), List("Boolean"))
    }
  }

  test("record and native fields retain independent errors and valid following siblings") {
    val source = "struct Record { a:, b: wrong, c: (Int ->), d: String }; " +
      "type Native = @metal:repr { a: wrong, b: Int };"
    parseNotFailed(source).map { module =>
      val record = module.members
        .collectFirst { case record: TypeStruct => record }
        .getOrElse(fail("Expected record"))
      assertEquals(record.fields.map(field => typeErrors(field.typeSpec).size), Vector(1, 1, 1, 0))
      assertEquals(typeRefs(record.fields.last.typeSpec).map(_.name), List("String"))
      val native = module.members
        .collectFirst { case definition: TypeDef => definition.typeSpec }
        .flatten
        .collect { case native: NativeStruct => native }
        .getOrElse(fail("Expected native"))
      assertEquals(native.fields.map(field => typeErrors(field._2).size), List(1, 0))
      assertEquals(typeRefs(native.fields.last._2).map(_.name), List("Int"))
    }
  }

  test("operator recovery preserves unary and binary alternatives and raw precedence syntax") {
    val source = "op !(a: wrong): broken60 90right = a;; " +
      "op +(a: Int, b:): wrong42 60left = a;;"
    parseNotFailed(source).map { module =>
      val values = bindings(module)
      assertEquals(values.map(value => lambda(value).params.size), List(1, 2))
      assertEquals(values.flatMap(_.meta.map(_.precedence)), List(90, 60))
      assertEquals(
        values.flatMap(_.meta.flatMap(_.associativity)),
        List(Associativity.Right, Associativity.Left)
      )
      assertEquals(
        values.map(value => typeErrors(requiredType(value.typeAsc)).head.failedCode),
        List(Some("broken60 "), Some("wrong42 "))
      )
    }
  }

  test("lambda headers recover parameter annotations and preserve the body separator") {
    val source = "let value = { a:, b: wrong, c: Int -> (a) };"
    parseNotFailed(source).map { module =>
      val value = lambda(bindings(module).head)
      assertEquals(value.params.size, 3)
      assertEquals(
        value.params.map(param => typeErrors(requiredType(param.typeAsc)).size),
        List(1, 1, 0)
      )
      assertEquals(value.body.terms.size, 1)
      assert(value.body.terms.head.isInstanceOf[TermGroup])
    }
  }

  test("valid lambdas preserve nullary bodies and grouped and ungrouped function parameter types") {
    val source = "let nullary = { item: Int }; let simple = { x: Int -> x }; " +
      "let function = { f: Int -> String -> f }; let grouped = { f: (Int -> String) -> f };"
    parseClean(source).map { module =>
      val values = bindings(module).map(lambda)
      assertEquals(values.map(_.params.size), List(0, 1, 1, 1))
      assertEquals(
        typeRefs(requiredType(values.head.body.terms.head.typeAsc)).map(_.name),
        List("Int")
      )
      assert(requiredType(values(2).params.head.typeAsc).isInstanceOf[TypeFn])
      assert(requiredType(values(3).params.head.typeAsc).isInstanceOf[TypeGroup])
    }
  }

  test("native representations and valid operator defaults remain unchanged") {
    val source = "type RawPtr = @metal:repr[t=ptr]; op !(value: Int): Int = value;; " +
      "op +(a: Int, b: Int): Int 60left = a;;"
    parseClean(source).map { module =>
      assertEquals(module.members.collect { case definition: TypeDef => definition }.size, 1)
      assertEquals(bindings(module).flatMap(_.meta.map(_.precedence)), List(50, 60))
    }
  }

  test("expression ascriptions permit following application terms and preserve reference spans") {
    val source = "let value = item: Int next 42;"
    parseClean(source).map { module =>
      val terms = bindings(module).head.value.terms
      assertEquals(terms.size, 3)
      assertEquals(typeRefs(requiredType(terms.head.typeAsc)).map(_.name), List("Int"))
      assertEquals(terms.head.spanOpt.map(_.end.index), Some(source.indexOf(':')))
      assert(terms(1).isInstanceOf[Ref])
      assertEquals(terms.collect { case value: LiteralInt => value.value }, List(42L))
    }
  }

  List("(Int, String)", "(Int; String)", "(Int = String)").foreach { malformed =>
    test(s"closed malformed expression type group '$malformed' preserves application terms") {
      val source = s"let value = item: $malformed missing 42;"
      parseNotFailed(source).map { module =>
        val terms = bindings(module).head.value.terms
        assertEquals(terms.size, 3)
        val errors = typeErrors(requiredType(terms.head.typeAsc))
        assertEquals(errors.size, 1)
        val start = source.indexOf('(')
        assertError(source, errors.head, start, start + malformed.length)
        assertEquals(terms.collect { case ref: Ref => ref.name }, List("item", "missing"))
        assertEquals(terms.collect { case value: LiteralInt => value.value }, List(42L))
      }
    }
  }

  test("recovery masks anchors inside quoted text and nested comments") {
    val source = "let value: wrong \"= ; ->\" (/* = /* ; */ -> */) = 1; let next = 2;"
    parseNotFailed(source).map { module =>
      assertEquals(bindings(module).size, 2)
      val error = typeErrors(requiredType(bindings(module).head.typeAsc)).head
      assertError(source, error, source.indexOf("wrong"), source.indexOf("= 1"))
    }
  }

  test("type recovery leaves declaration and documentation boundaries unconsumed") {
    List("let next = 2;", "/* next declaration */ let next = 2;", "fn next() = 2;;").foreach {
      tail =>
        val source = s"wrong $tail"
        val info   = SourceInfo(source)
        def recovered(using P[Any]): P[(Type, Int)] =
          P(typeSpecP(info, TypeContext.Declaration) ~ Index)

        parse(source, recovered(using _)) match
          case Parsed.Success((tpe, end), _) =>
            assertError(source, typeErrors(tpe).head, 0, source.indexOf(tail))
            assertEquals(end, source.indexOf(tail))
          case failure: Parsed.Failure => fail(failure.trace().longMsg)
    }
  }

  test("empty fragments and EOF retain errors without consuming caller delimiters") {
    List("", ")", ",", ";", "=", "->").foreach { source =>
      val info = SourceInfo(source)
      def recovered(using P[Any]): P[(Type, Int)] =
        P(typeSpecP(info, TypeContext.Parameter) ~ Index)

      parse(source, recovered(using _)) match
        case Parsed.Success((tpe, end), _) =>
          assert(typeErrors(tpe).nonEmpty)
          assertEquals(end, if source == "->" then 2 else 0)
          assert(typeErrors(tpe).forall(_.failedCode.isEmpty))
        case failure: Parsed.Failure => fail(failure.trace().longMsg)
    }
  }

  test("malformed type recovery completes within a bounded child process") {
    val source = "let a: = 1; type Broken = Int -> ->; " +
      "fn bad(a:, b: wrong, c: (Int ->): wrong = 1;;\nlet next = 2;\nlet eof:"
    ParserDeadline.run(List("parse", source), 5.seconds).map { result =>
      assert(!result.timedOut, result.output)
      assert(result.stopped, result.output)
      assert(result.exitCode.exists(code => code == 10 || code == 11), result.output)
    }
  }

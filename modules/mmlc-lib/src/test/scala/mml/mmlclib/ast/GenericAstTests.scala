package mml.mmlclib.ast

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.lsp.AstLookup
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintTypeSpec

class GenericAstTests extends BaseEffFunSuite:

  private val source = SourceOrigin.Synth

  test("scheme formatting uses source spellings while binders retain distinct identities") {
    val outer     = TypeVariable(source, "'T", "outer-type".some)
    val inner     = TypeVariable(source, "'T", "inner-type".some)
    val signature = TypeFn(source, NonEmptyList.one(outer), inner)
    val scheme    = TypeScheme(source, List(inner), signature)
    val rewritten = TypeTraversal.rewrite(scheme) {
      case variable: TypeVariable if variable.resolvedId == outer.resolvedId =>
        TypeRef(source, "Int")
    }

    assertEquals(
      rewritten,
      scheme.copy(bodyType = signature.copy(paramTypes = NonEmptyList.one(TypeRef(source, "Int"))))
    )
    assertEquals(AstLookup.formatType(scheme.some), "∀'T. 'T -> 'T")
    assert(prettyPrintTypeSpec(scheme.some).startsWith("∀'T. "))
  }

  test("ingest stamps explicit type arguments and retains the callee's error evidence") {
    val info        = SourceInfo("f ???")
    val location    = SrcSpan(info.pointAt(2), info.pointAt(5))
    val malformed   = ParseTypeError(location, SyntaxError.InvalidTypeSyntax, "???".some)
    val reference   = Ref(source, "f", typeAsc = malformed.some)
    val application = FnTypeApp(source, reference, List(TypeGroup(source, List(malformed))))
    val binding = Bnd(
      source   = source,
      nameNode = Name.synth("result"),
      value    = Expr(source, List(application))
    )
    val module = Module(source, "Generic", Visibility.Public, List(binding))
    val stamped =
      ParsingErrorChecker.checkModule(CompilerState.empty(module, info, CompilerConfig.default))
    val stampedApplication = stamped.module.members
      .collectFirst { case value: Bnd =>
        value.value.terms.collectFirst { case value: FnTypeApp => value }
      }
      .flatten
      .getOrElse(fail("Expected an explicit type application"))
    val argumentErrors = stampedApplication.args.flatMap { argument =>
      TypeTraversal.collect(argument) { case error: ParseTypeError => error }
    }
    val calleeErrors = TermTraversal
      .collect(stampedApplication) { case ref: Ref => ref }
      .flatMap(_.typeAsc)
      .collect { case error: ParseTypeError => error }

    assertEquals(stamped.errors.count(_.isInstanceOf[SemanticError.ParseTypeErrorFound]), 1)
    assertEquals(argumentErrors.map(_.source), List(malformed.source))
    assertEquals(argumentErrors, calleeErrors)
    assert(argumentErrors.forall(_.causes.nonEmpty))
  }

  test("navigation on an unresolved type argument does not select the callable") {
    val code = "fn identity(value: Int): Int = value;; let result = identity Int;"
    parseNotFailed(code).map { parsed =>
      val function = parsed.members
        .collectFirst {
          case binding: Bnd if binding.name == "identity" => binding.copy(id = "function-id".some)
        }
        .getOrElse(fail("Expected identity declaration"))
      val binding = parsed.members
        .collectFirst {
          case binding: Bnd if binding.name == "result" => binding
        }
        .getOrElse(fail("Expected result binding"))
      val refs = binding.value.terms.collect { case reference: Ref => reference }
      val callee = refs
        .find(_.name == "identity")
        .getOrElse(fail("Expected callee reference"))
        .copy(resolvedId = function.id)
      val argument = refs.find(_.name == "Int").getOrElse(fail("Expected uppercase argument"))
      val application = FnTypeApp(
        binding.value.source,
        callee,
        List(TypeRef(argument.source, argument.name))
      )
      val module = parsed.copy(
        members =
          List(function, binding.copy(value = binding.value.copy(terms = List(application)))),
        resolvables = ResolvablesIndex().updated(function)
      )
      val state          = CompilerState.empty(module, SourceInfo(code), CompilerConfig.default)
      val argumentColumn = code.lastIndexOf("Int") + 1
      val calleeColumn   = code.lastIndexOf("identity") + 1

      assertEquals(AstLookup.findDefinitionAt(state, 1, argumentColumn), Nil)
      assertEquals(AstLookup.findReferencesAt(state, 1, argumentColumn, true), Nil)
      assertEquals(
        AstLookup.findDefinitionAt(state, 1, calleeColumn),
        function.nameNode.spanOpt.toList
      )
    }
  }

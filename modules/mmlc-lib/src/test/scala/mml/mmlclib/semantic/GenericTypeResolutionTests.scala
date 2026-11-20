package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.lsp.{Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.ErrorPrinter

class GenericTypeResolutionTests extends BaseEffFunSuite:

  private def resolve(code: String): IO[CompilerState] =
    parseNotFailed(code).map { parsed =>
      TypeResolver.rewriteModule(
        IdAssigner.rewriteModule(
          CompilerState.empty(injectBasicTypes(parsed), SourceInfo(code), CompilerConfig.default)
        )
      )
    }

  private def binding(state: CompilerState, name: String): Bnd =
    state.module.members
      .collectFirst { case b: Bnd if b.name == name => b }
      .getOrElse(fail(s"Missing binding $name"))

  private def function(state: CompilerState, name: String): Lambda =
    binding(state, name).value.terms match
      case (lambda: Lambda) :: Nil => lambda
      case other => fail(s"Expected function, got $other")

  private def variable(tpe: Type): TypeVariable = tpe match
    case variable: TypeVariable => variable
    case other => fail(s"Expected type variable, got $other")

  private def identity(tpe: Type): String =
    variable(tpe).resolvedId.getOrElse(fail("Missing type-variable identity"))

  private def variables(tpe: Type): List[TypeVariable] =
    TypeTraversal.collect(tpe) { case variable: TypeVariable => variable }

  test("signature, body and cached result occurrences share a binder and keep their sources"):
    val code = "fn identity(~value: 'T): 'T = value: 'T;;"
    resolve(code).map { state =>
      assertEquals(state.errors, Vector.empty)
      val lambda = function(state, "identity")
      val binder = lambda.typeParams.head
      assertEquals(lambda.typeParams.size, 1)
      val occurrences = List(
        lambda.params.head.typeAsc.get,
        lambda.typeAsc.get,
        lambda.body.terms.head.typeAsc.get,
        binding(state, "identity").typeAsc.get
      )
      assertEquals(occurrences.map(identity), List.fill(4)(identity(binder)))
      assertEquals(occurrences.take(3).flatMap(_.spanOpt).distinct.size, 3)
      assertEquals(binder.source, lambda.params.head.typeAsc.get.source)
      assert(lambda.params.head.consuming)
      assertEquals(
        state.module.resolvables.lookup(lambda.params.head.id.get),
        Some(lambda.params.head)
      )
      val again = TypeResolver.rewriteModule(state)
      assertEquals(again.errors, Vector.empty)
      assertEquals(again.module, state.module)
      assertEquals(again.bindingIds, state.bindingIds)
    }

  test("unrelated functions have distinct binders even with identical variable spellings"):
    resolve("fn first(x: 'T) = x;; fn second(x: 'T): 'T = x;;").map { state =>
      assertEquals(state.errors, Vector.empty)
      val first  = function(state, "first")
      val second = function(state, "second")
      assertNotEquals(identity(first.typeParams.head), identity(second.typeParams.head))
      assertEquals(first.typeAsc, None)
      assertEquals(first.typeParams.map(_.name), List("'T"))
    }

  test("binder order follows nested parameter types then the result without duplicates"):
    resolve("fn ordered(f: ('B -> 'A), x: 'B): ('C -> 'A) = ???;;").map { state =>
      assertEquals(state.errors, Vector.empty)
      val lambda = function(state, "ordered")
      assertEquals(lambda.typeParams.map(_.name), List("'B", "'A", "'C"))
      val occurrences = (lambda.params.flatMap(_.typeAsc) ++ lambda.typeAsc).flatMap(variables)
      assertEquals(
        occurrences.map(identity),
        List(
          identity(lambda.typeParams(0)),
          identity(lambda.typeParams(1)),
          identity(lambda.typeParams(0)),
          identity(lambda.typeParams(2)),
          identity(lambda.typeParams(1))
        )
      )
    }

  test("nested functions reuse enclosing variables and keep new variables out of continuations"):
    val code = """
      fn outer(x: 'T): 'T =
        fn inner(y: 'U, z: 'T): 'U = y: 'U;;
        let same: 'T = x;
        same: 'U;
      ;
    """
    resolve(code).map { state =>
      val outer = function(state, "outer")
      val inner = TermTraversal
        .collect(outer.body) {
          case lambda: Lambda if lambda.typeParams.nonEmpty => lambda
        }
        .head
      assertEquals(inner.typeParams.map(_.name), List("'U"))
      assertEquals(identity(inner.params(1).typeAsc.get), identity(outer.typeParams.head))
      assertEquals(identity(inner.body.terms.head.typeAsc.get), identity(inner.typeParams.head))
      val scopes = TermTraversal.collect(outer.body) {
        case lambda: Lambda if lambda.meta.exists(_.isBindingScope) => lambda
      }
      assert(scopes.nonEmpty)
      assert(scopes.forall(_.typeParams.isEmpty))
      val cached = scopes.flatMap(_.params.flatMap(_.typeAsc)).flatMap(variables)
      assert(cached.exists(_.resolvedId == inner.typeParams.head.resolvedId))
      val failures = state.errors.collect { case error: SemanticError.UndefinedTypeVariable =>
        error
      }
      assertEquals(failures.size, 1)
      assertEquals(failures.head.variable.name, "'U")
      assertEquals(
        Diagnostics.fromCompilerState(state).map(_.range),
        failures.head.variable.spanOpt.map(Range.fromSrcSpan).toList
      )
      assertEquals(
        Diagnostics.fromCompilerState(state).map(_.message),
        List("Unbound type variable: 'U")
      )
    }

  test("sibling local functions and literal lambdas have independent generic parameters"):
    val code = """
      fn outer(): Int =
        fn first(x: 'T): 'T = x;;
        fn second(x: 'T): 'T = x;;
        let literal = { x: 'T -> x: 'T };
        1;
      ;
    """
    resolve(code).map { state =>
      assertEquals(state.errors, Vector.empty)
      val parameters = TermTraversal
        .collect(function(state, "outer").body) { case lambda: Lambda =>
          lambda.typeParams
        }
        .flatten
      assertEquals(parameters.size, 3)
      assertEquals(parameters.map(identity).distinct.size, 3)
      val again = TypeResolver.rewriteModule(state)
      assertEquals(again.errors, Vector.empty)
      assertEquals(again.module, state.module)
    }

  test("body and local binding annotations cannot introduce type variables"):
    resolve("fn plain(): Int = let value: 'T = 1; value: 'T;;").map { state =>
      assertEquals(function(state, "plain").typeParams, Nil)
      val failures = state.errors.collect { case error: SemanticError.UndefinedTypeVariable =>
        error
      }
      assertEquals(failures.size, 2)
      assertEquals(failures.flatMap(_.variable.spanOpt).distinct.size, 2)
    }

  test("constraints resolve in lexical scope and find later protocol declarations"):
    val code = """
      fn render(x: 'T): 'T where 'T: Show =
        fn local(y: 'U): 'U where 'T: Show, 'U: Show = y;;
        x;
      ;
      protocol Show 'T = fn show(x: 'T): String; ;
    """
    resolve(code).map { state =>
      assertEquals(state.errors, Vector.empty)
      val outer = function(state, "render")
      val inner = TermTraversal
        .collect(outer.body) {
          case lambda: Lambda if lambda.constraints.nonEmpty => lambda
        }
        .head
      val protocol = state.module.members.collectFirst { case p: Protocol => p }.get
      assertEquals(outer.constraints.head.variable.resolvedId, outer.typeParams.head.resolvedId)
      assertEquals(
        inner.constraints.map(_.variable.resolvedId),
        List(outer.typeParams.head.resolvedId, inner.typeParams.head.resolvedId)
      )
      (outer.constraints ++ inner.constraints).flatMap(_.constraints).foreach { ref =>
        assertEquals(ref.resolvedId, protocol.id)
      }
      assertNotEquals(protocol.typeParameter.resolvedId, outer.typeParams.head.resolvedId)
      assertEquals(
        protocol.signatures.head.params.head.typeAsc.map(identity),
        Some(identity(protocol.typeParameter))
      )
    }

  List(
    "fn duplicate(x: 'T): 'T where 'T: Left, 'T: Right = x;;",
    "fn duplicate(x: 'T, y: 'U): 'T where 'T: Left, 'U: Left, 'T: Right = x;;",
    """fn outer(x: 'T): 'T where 'T: Left =
      fn local(y: 'T): 'T where 'T: Left, 'T: Right = y;;
      x;
    ;"""
  ).zipWithIndex.foreach { (functionSource, index) =>
    test(s"repeated function constraint variables report their second occurrence: $index"):
      val code = functionSource + "\nprotocol Left 'T = ; protocol Right 'T = ;"
      resolve(code).map { state =>
        assertEquals(state.errors.size, 1)
        state.errors.head match
          case SemanticError.DuplicateConstraintVariable(variable, first, _) =>
            assert(variable.resolvedId.nonEmpty)
            assertEquals(variable.resolvedId, first.resolvedId)
            val span = variable.spanOpt.get
            assert(first.spanOpt.get.start.index < span.start.index)
            assertEquals(code.substring(span.start.index, span.end.index), "'T")
            val message     = "Duplicate where entry for 'T; combine its requirements with &"
            val diagnostics = Diagnostics.fromCompilerState(state)
            assertEquals(diagnostics.map(_.range), List(Range.fromSrcSpan(span)))
            assertEquals(diagnostics.map(_.message), List(message))
            val rendered = ErrorPrinter.prettyPrintSemanticErrors(
              state.semanticErrors.toList,
              Some(state.sourceInfo)
            )
            assert(rendered.contains(message), rendered)
            assert(rendered.contains(ErrorPrinter.formatLocation(span)), rendered)
          case other => fail(s"Expected a duplicate variable, got $other")
      }
  }

  test("unknown constraint variables and protocols are independent errors"):
    resolve("fn bad(x: 'T): 'T where 'U: Missing = x;;").map { state =>
      val lambda = function(state, "bad")
      assertEquals(lambda.typeParams.map(_.name), List("'T"))
      assertEquals(lambda.constraints.head.variable.resolvedId, None)
      assertEquals(lambda.constraints.head.constraints.head.resolvedId, None)
      assertEquals(state.errors.size, 2)
      assert(state.errors.exists(_.isInstanceOf[SemanticError.UndefinedTypeVariable]))
      assert(state.errors.exists {
        case SemanticError.ProtocolErrorFound(_: ProtocolError.UnknownProtocol, _) => true
        case _ => false
      })
    }

  test("explicit function type arguments resolve against enclosing binders"):
    resolve("fn outer(x: 'T): 'T = identity ('T -> 'T);;").map { state =>
      assertEquals(state.errors, Vector.empty)
      val outer = function(state, "outer")
      val apps  = TermTraversal.collect(outer.body) { case app: FnTypeApp => app }
      assertEquals(apps.size, 1)
      assertEquals(
        apps.head.args.flatMap(variables).map(identity),
        List.fill(2)(identity(outer.typeParams.head))
      )
    }

  test("result-only variables bind nullary and partially annotated function signatures"):
    resolve("fn make(): 'T = ???;; fn other(x): 'T = x;;").map { state =>
      assertEquals(state.errors, Vector.empty)
      val functions = List(function(state, "make"), function(state, "other"))
      functions.foreach { lambda =>
        assertEquals(lambda.typeParams.map(_.name), List("'T"))
        assertEquals(lambda.typeAsc.map(identity), Some(identity(lambda.typeParams.head)))
      }
      assertEquals(functions.flatMap(_.typeParams).map(identity).distinct.size, 2)
    }

  test("protocol matching uses binder identity rather than the variable spelling"):
    resolve("protocol Echo 'T = fn echo(value: 'T): Int; ;").map { state =>
      assertEquals(state.errors, Vector.empty)
      val protocol     = state.module.members.collectFirst { case p: Protocol => p }.get
      val signature    = protocol.signatures.head
      val parameter    = signature.params.head
      val original     = variable(parameter.typeAsc.get)
      val concrete     = signature.returnType
      val applications = List(ProtocolSelection.ApplicationEvidence(Some(concrete), Nil))

      def evidence(variable: TypeVariable): ProtocolSelection.SignatureEvidence =
        val changed = signature.copy(params = List(parameter.copy(typeAsc = Some(variable))))
        ProtocolSelection.inferInstanceType(
          protocol,
          changed,
          applications,
          None,
          state.module.resolvables
        )

      assert(
        evidence(original.copy(name = "'Other"))
          .isInstanceOf[ProtocolSelection.SignatureEvidence.Concrete]
      )
      assertEquals(
        evidence(original.copy(resolvedId = Some("unrelated-binder"))),
        ProtocolSelection.SignatureEvidence.Incompatible
      )
      assertEquals(
        evidence(original.copy(resolvedId = None)),
        ProtocolSelection.SignatureEvidence.Incompatible
      )
    }

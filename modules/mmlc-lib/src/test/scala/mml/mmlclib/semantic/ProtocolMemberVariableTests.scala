package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.termLambda

class ProtocolMemberVariableTests extends BaseEffFunSuite:

  private val show = "protocol Show 'T = fn show(value: 'T): String; ;"

  test("forward instance members inherit distinct signature scopes and requirements"):
    val source = """
      instance Apply Int =
        fn apply_to(value, f) =
          let result: 'U = f value;
          let description = show result;
          result;
        ;
        op ==>(value: Int, f: Int -> 'U): 'U =
          let result = f value;
          let description = show result;
          result;
        ;
      ;
      protocol Apply 'T =
        fn apply_to(value: 'T, f: 'T -> 'U): 'U where 'U: Show;
        op ==>(value: 'T, f: 'T -> 'U): 'U 40 left where 'U: Show;
      ;
    """ + show
    semStateNotFailed(source).map { state =>
      val members = List("apply_to", "==>").map { name =>
        val signature = protocolSignature(state.module, "Apply", name)
        val binding   = protocolImplementation(state, "Apply", "Int", name)
        val body      = termLambda(binding.value).getOrElse(fail("Expected instance body"))
        val binder    = signature.typeParams.head
        assertEquals(signature.typeParams.size, 1)
        assert(binder.resolvedId.nonEmpty)
        assertEquals(body.typeParams.map(_.resolvedId), List(binder.resolvedId))
        assertEquals(body.constraints.map(_.variable.resolvedId), List(binder.resolvedId))
        val showId = protocolSignature(state.module, "Show", "show").id
        val calls = TermTraversal.collect(body.body) {
          case ref: Ref if ref.resolvedId == showId => ref
        }
        assertEquals(calls.size, 1)
        assertEquals(
          calls.head.protocolType.collect { case v: TypeVariable => v.resolvedId },
          Some(binder.resolvedId)
        )
        binder.resolvedId
      }
      assertEquals(members.distinct.size, 2)
    }

  test("signature variables follow first appearance through nested parameter and result types"):
    val source = """
      protocol Apply 'T =
        fn apply(key: 'T, f: 'V -> 'U, x: 'V): 'U;
        fn make(key: 'T): 'R;
      ;
    """
    semStateNotFailed(source).map { state =>
      val signature = protocolSignature(state.module, "Apply", "apply")
      assertEquals(signature.typeParams.map(_.name), List("'V", "'U"))
      assertEquals(
        protocolSignature(state.module, "Apply", "make").typeParams.map(_.name),
        List("'R")
      )
    }

  test("a renamed variable is diagnosed at the implementation annotation"):
    val source = """
      protocol Keep 'T = fn keep(key: 'T, value: 'U): 'U; ;
      instance Keep Int = fn keep(key, value: 'V) = value;; ;
    """
    semState(source).map { state =>
      val variables = state.errors.collect { case SemanticError.UndefinedTypeVariable(v, _, _) =>
        v
      }
      assertEquals(
        variables
          .flatMap(_.spanOpt)
          .map(span => source.substring(span.start.index, span.end.index)),
        List("'V")
      )
    }

  test("a concrete implementation parameter cannot narrow the member variable"):
    val source = """
      protocol Keep 'T = fn keep(key: 'T, value: 'U): 'U; ;
      instance Keep Int = fn keep(key, value: Bool) = value;; ;
    """
    semState(source).map { state =>
      val mismatches = state.errors.collect {
        case SemanticError.ProtocolErrorFound(
              ProtocolError.SignatureTypeMismatch(_, _, actual),
              _
            ) =>
          actual
      }
      assertEquals(
        mismatches
          .flatMap(_.spanOpt)
          .map(span => source.substring(span.start.index, span.end.index)),
        List("Bool")
      )
    }

  test("a member cannot constrain the protocol parameter"):
    val source = show + """
      protocol Keep 'T = fn keep(key: 'T, value: 'U): 'U where 'T: Show; ;
    """
    semState(source).map { state =>
      val targets = state.errors.collect {
        case SemanticError.ProtocolErrorFound(
              ProtocolError.MemberConstraintOnProtocolParameter(v, _),
              _
            ) =>
          v
      }
      assertEquals(targets.flatMap(_.spanOpt).map(_.start.index), List(source.lastIndexOf("'T")))
    }

  test("an unused generic implementation must declare the requirements its body uses"):
    val source = show + """
      protocol Label 'T = fn label(key: 'T, value: 'U): String; ;
      instance Label Int = fn label(key, value) = show value;; ;
    """
    semState(source).map { state =>
      val missing = state.errors.collect {
        case SemanticError.TypeCheckingError(TypeError.MissingGenericRequirement(ref, _, _, _)) =>
          ref
      }
      assertEquals(missing.flatMap(_.spanOpt).map(_.start.index), List(source.lastIndexOf("show")))
    }

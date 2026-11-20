package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.errors.{DiagnosticId, DiagnosticRelation}
import mml.mmlclib.lsp.{Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.ErrorPrinter

class ProtocolPrerequisiteTests extends BaseEffFunSuite:

  private def check(source: String): IO[CompilerState] =
    parseNotFailed(source).map { parsed =>
      val initial =
        CompilerState.empty(injectBasicTypes(parsed), SourceInfo(source), CompilerConfig.default)
      val assigned = IdAssigner.rewriteModule(DuplicateNameChecker.rewriteModule(initial))
      ProtocolChecker.rewriteModule(TypeResolver.rewriteModule(assigned))
    }

  private def declaration(state: CompilerState, name: String): Protocol =
    state.module.members
      .collectFirst {
        case protocol: Protocol if protocol.name == name => protocol
      }
      .getOrElse(fail(s"Missing protocol $name"))

  private def id(state: CompilerState, name: String): String =
    declaration(state, name).id.getOrElse(fail(s"Missing protocol identity for $name"))

  private def rejection(state: CompilerState, name: String): Vector[DiagnosticId] =
    val identity = id(state, name)
    assert(!state.protocols.implies(identity, identity))
    assert(!state.protocols.prerequisiteOrder.contains(identity))
    state.protocols.protocol(identity, state.module.resolvables) match
      case Left(ProtocolLookupError.RejectedDeclaration(roots)) =>
        assert(roots.nonEmpty)
        roots
      case other => fail(s"Expected $name rejection, got $other")

  private def protocolErrors(state: CompilerState): List[ProtocolError] =
    state.errors.collect { case SemanticError.ProtocolErrorFound(error, _) => error }.toList

  private val diamond = List(
    "protocol Render 'T where 'T: Describe & Display & Text = ;",
    "protocol Describe 'T where 'T: Text = ;",
    "protocol Display 'T where 'T: Text = ;",
    "protocol Text 'T = ;",
    "protocol Other 'T = ;"
  )

  List(false, true).foreach { reversed =>
    test(s"transitive and overlapping prerequisites are accepted with reversed order $reversed"):
      val source = (if reversed then diamond.reverse else diamond).mkString("\n")
      check(source).map { state =>
        assertEquals(state.errors, Vector.empty)
        val expected = Map(
          "Render" -> Set("Render", "Describe", "Display", "Text"),
          "Describe" -> Set("Describe", "Text"),
          "Display" -> Set("Display", "Text"),
          "Text" -> Set("Text"),
          "Other" -> Set("Other")
        )
        expected.foreach { (owner, requirements) =>
          expected.keys.foreach { required =>
            assertEquals(
              state.protocols.implies(id(state, owner), id(state, required)),
              requirements.contains(required),
              s"$owner implies $required"
            )
          }
        }
        val order = state.protocols.prerequisiteOrder
        assertEquals(order.toSet, expected.keys.map(id(state, _)).toSet)
        assertEquals(order.distinct, order)
        List(
          "Describe" -> "Text",
          "Display" -> "Text",
          "Render" -> "Describe",
          "Render" -> "Display"
        )
          .foreach { (dependent, required) =>
            assert(order.indexOf(id(state, required)) < order.indexOf(id(state, dependent)))
          }
        assert(!state.protocols.implies(id(state, "Render"), "absent"))
        assert(!state.protocols.implies("absent", id(state, "Render")))
        assertEquals(declaration(state, "Render").constraints.flatMap(_.constraints).size, 3)

        val repeated = ProtocolChecker.rewriteModule(state)
        assertEquals(repeated.errors, Vector.empty)
        assertEquals(repeated.protocols, state.protocols)
        assertEquals(repeated.module, state.module)
      }
  }

  test("repeated written requirements are rejected within one entry"):
    check("protocol Repeated 'T where 'T: Base & Base = ; protocol Base 'T = ;").map { state =>
      val refs = declaration(state, "Repeated").constraints.flatMap(_.constraints)
      protocolErrors(state) match
        case List(ProtocolError.DuplicatePrerequisite(ref, first)) =>
          assertEquals(ref, refs(1))
          assertEquals(first, refs.head)
          assertNotEquals(ref.source, first.source)
        case other => fail(s"Expected one duplicate requirement, got $other")
      rejection(state, "Repeated")
      assert(state.protocols.implies(id(state, "Base"), id(state, "Base")))
    }

  List("'T: Base, 'T: Other", "'T: Base, 'T: Base").foreach { clause =>
    test(s"a repeated constraint variable rejects the protocol and its dependants: $clause"):
      val source = s"""
        protocol Dependent 'T where 'T: Repeated = ;
        protocol Repeated 'T where $clause = ;
        protocol Base 'T = ;
        protocol Other 'T = ;
      """
      check(source).map { state =>
        val entries = declaration(state, "Repeated").constraints
        val duplicates = state.diagnostics.records.filter(
          _.error.isInstanceOf[SemanticError.DuplicateConstraintVariable]
        )
        assertEquals(duplicates.size, 1)
        duplicates.head.error match
          case SemanticError.DuplicateConstraintVariable(variable, first, _) =>
            assertEquals(variable, entries(1).variable)
            assertEquals(first, entries.head.variable)
            assertEquals(variable.resolvedId, first.resolvedId)
            assertNotEquals(variable.source, first.source)
          case other => fail(s"Expected a duplicate variable, got $other")
        val roots = Vector(duplicates.head.id)
        assertEquals(entries(1).causes, roots)
        assertEquals(rejection(state, "Repeated"), roots)
        assertEquals(rejection(state, "Dependent"), roots)
        assertEquals(
          protocolErrors(state).map(_.source),
          List(declaration(state, "Dependent").constraints.head.constraints.head.source)
        )
        assertEquals(
          state.protocols.prerequisiteOrder.toSet,
          Set(id(state, "Base"), id(state, "Other"))
        )
      }
  }

  List(
    List("A" -> "A"),
    List("A" -> "B", "B" -> "C", "C" -> "A")
  ).foreach { edges =>
    test(s"a prerequisite cycle is rejected with its closed path: $edges"):
      val source = edges
        .map { (owner, required) =>
          s"protocol $owner 'T where 'T: $required = ;"
        }
        .mkString("\n") + "\nprotocol Dependent 'T where 'T: A = ; protocol Good 'T = ;"
      check(source).map { state =>
        val cycles = protocolErrors(state).collect { case cycle: ProtocolError.PrerequisiteCycle =>
          cycle
        }
        assertEquals(cycles.size, 1)
        val cycle = cycles.head
        assertEquals(
          cycle.path.toList.flatMap(_.id),
          (edges.map(_._1) :+ edges.head._1).map(id(state, _))
        )
        val expectedRef = declaration(state, edges.last._1).constraints.head.constraints.head
        assertEquals(cycle.ref, expectedRef)
        val roots = rejection(state, "A")
        edges.map(_._1).foreach(name => assertEquals(rejection(state, name), roots))
        assertEquals(rejection(state, "Dependent"), roots)
        assertEquals(state.protocols.prerequisiteOrder, Vector(id(state, "Good")))
        assert(!protocolErrors(state).exists(_.isInstanceOf[ProtocolError.RegistryFailure]))

        val diagnostic = Diagnostics
          .fromCompilerState(state)
          .find(_.message.startsWith("Protocol prerequisite cycle:"))
          .getOrElse(fail("Missing cycle diagnostic"))
        assertEquals(diagnostic.range, Range.fromSrcSpan(expectedRef.spanOpt.get))
        val rendered = ErrorPrinter.prettyPrintSemanticErrors(
          state.semanticErrors.toList,
          Some(state.sourceInfo)
        )
        assert(rendered.contains(cycle.path.toList.map(_.name).mkString(" -> ")), rendered)
      }
  }

  test("a cycle with a cross-edge to an already visited member rejects every dependent"):
    val source = """
      protocol A 'T where 'T: B & C = ;
      protocol B 'T where 'T: A = ;
      protocol C 'T where 'T: B = ;
      protocol Good 'T = ;
    """
    check(source).map { state =>
      val roots = rejection(state, "A")
      List("B", "C").foreach(name => assertEquals(rejection(state, name), roots))
      assertEquals(state.protocols.prerequisiteOrder, Vector(id(state, "Good")))
    }

  test("dependent protocols retain an unknown prerequisite's original diagnostic root"):
    val source = """
      protocol Root 'T where 'T: Middle = ;
      protocol Middle 'T where 'T: Leaf = ;
      protocol Leaf 'T where 'T: Missing = ;
      protocol Good 'T = ;
    """
    check(source).map { state =>
      val originals = state.diagnostics.records.filter {
        _.error match
          case SemanticError.ProtocolErrorFound(_: ProtocolError.UnknownProtocol, _) => true
          case _ => false
      }
      assertEquals(originals.size, 1)
      val roots = originals.map(_.id)
      assertEquals(originals.head.relation, DiagnosticRelation.Primary)
      List("Root", "Middle", "Leaf").foreach(name => assertEquals(rejection(state, name), roots))
      assertEquals(declaration(state, "Leaf").constraints.head.causes, roots)
      val consequences = state.diagnostics.records.filterNot(record => roots.contains(record.id))
      assertEquals(consequences.size, 2)
      assert(consequences.forall(_.relation == DiagnosticRelation.Consequence(roots)))
      assertEquals(state.protocols.prerequisiteOrder, Vector(id(state, "Good")))
    }

  test("an invalid constraint target rejects its protocol without repeating the resolver error"):
    check("protocol Bad 'T where 'U: Good = ; protocol Good 'T = ;").map { state =>
      assertEquals(state.errors.size, 1)
      assert(state.errors.head.isInstanceOf[SemanticError.UndefinedTypeVariable])
      assertEquals(rejection(state, "Bad"), state.diagnostics.records.map(_.id))
      assertEquals(state.protocols.prerequisiteOrder, Vector(id(state, "Good")))
    }

  test("a same-named variable with another protocol's binder cannot supply a prerequisite"):
    check("protocol Bound 'T where 'T: Base = ; protocol Base 'T = ;").map { state =>
      val bound = declaration(state, "Bound")
      val base  = declaration(state, "Base")
      val wrongBinder = bound.copy(constraints = bound.constraints.map { entry =>
        entry.copy(variable = entry.variable.copy(resolvedId = base.typeParameter.resolvedId))
      })
      val members = state.module.members.map {
        case protocol: Protocol if protocol.id == bound.id => wrongBinder
        case member => member
      }
      val input = state.withModule(ResolvablesIndexer.refresh(state.module.copy(members = members)))
      val checked = ProtocolChecker.rewriteModule(input)

      protocolErrors(checked) match
        case List(ProtocolError.InvalidPrerequisiteTarget(variable, protocol)) =>
          assertEquals(variable.resolvedId, base.typeParameter.resolvedId)
          assertEquals(protocol.id, bound.id)
        case other => fail(s"Expected a rejected constraint binder, got $other")
      rejection(checked, "Bound")
      assertEquals(checked.protocols.prerequisiteOrder, Vector(id(checked, "Base")))
    }

  test("several invalid prerequisite paths retain each original cause once"):
    val source = """
      protocol Root 'T where 'T: Right & Left & Leaf = ;
      protocol Left 'T where 'T: Leaf = ;
      protocol Right 'T where 'T: Leaf & Missing = ;
      protocol Leaf 'T where 'T: Absent = ;
      protocol Good 'T = ;
    """
    check(source).map { state =>
      val originals = state.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
      assertEquals(originals.size, 2)
      assert(originals.forall {
        _.error match
          case SemanticError.ProtocolErrorFound(_: ProtocolError.UnknownProtocol, _) => true
          case _ => false
      })
      val roots = rejection(state, "Root")
      assertEquals(roots.toSet, originals.map(_.id).toSet)
      assertEquals(roots.distinct, roots)
      assertEquals(rejection(state, "Left"), rejection(state, "Leaf"))
      assertEquals(state.protocols.prerequisiteOrder, Vector(id(state, "Good")))
    }

  List(
    "protocol Bad 'T = fn bad(value: 'T): 'T; fn bad(other: 'T): 'T; ;",
    "protocol Bad 'T = fn bad(value: Missing): 'T; ;"
  ).foreach { bad =>
    test(s"invalid prerequisite signatures propagate their declaration failure: $bad"):
      check(s"protocol Dependent 'T where 'T: Bad = ; $bad protocol Good 'T = ;").map { state =>
        val roots = rejection(state, "Bad")
        assertEquals(rejection(state, "Dependent"), roots)
        assertEquals(state.protocols.prerequisiteOrder, Vector(id(state, "Good")))
        assert(!protocolErrors(state).exists(_.isInstanceOf[ProtocolError.RegistryFailure]))
        assertEquals(
          protocolErrors(state).count(_.isInstanceOf[ProtocolError.InvalidPrerequisite]),
          1
        )
      }
  }

  test("independent invalid graphs accumulate failures while a valid shared member remains usable"):
    val source = """
      protocol Cycle 'T where 'T: Cycle = fn identity(value: 'T): 'T; ;
      protocol Broken 'T where 'T: Unknown = ;
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      let result = identity 42;
    """
    semVisibleState(source).map { state =>
      assertEquals(state.errors.size, 2)
      assert(state.errors.exists {
        case SemanticError.ProtocolErrorFound(_: ProtocolError.PrerequisiteCycle, _) => true
        case _ => false
      })
      assert(state.errors.exists {
        case SemanticError.ProtocolErrorFound(_: ProtocolError.UnknownProtocol, _) => true
        case _ => false
      })
      val result = state.module.members
        .collectFirst { case binding: Bnd if binding.name == "result" => binding }
        .getOrElse(fail("Missing valid result binding"))
      val integer = TypeRef(SourceOrigin.Synth, "Int", stdlibTypeId("Int"))
      assertEquals(
        result.typeSpec.map(ProtocolRegistry.typeKey(_, state.module.resolvables)),
        Some(ProtocolRegistry.typeKey(integer, state.module.resolvables))
      )
    }

package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.errors.{DiagnosticId, DiagnosticRelation}
import mml.mmlclib.lsp.{Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.ErrorPrinter

class ProtocolInstancePrerequisiteTests extends BaseEffFunSuite:

  private def resolve(source: String): IO[CompilerState] =
    parseNotFailed(source).map { parsed =>
      val initial =
        CompilerState.empty(injectBasicTypes(parsed), SourceInfo(source), CompilerConfig.default)
      TypeResolver.rewriteModule(
        IdAssigner.rewriteModule(DuplicateNameChecker.rewriteModule(initial))
      )
    }

  private def check(source: String): IO[CompilerState] =
    resolve(source).map(ProtocolChecker.rewriteModule)

  private def protocol(state: CompilerState, name: String): Protocol =
    state.module.members
      .collectFirst {
        case declaration: Protocol if declaration.name == name => declaration
      }
      .getOrElse(fail(s"Missing fixture protocol $name"))

  private def instances(state: CompilerState): List[ProtocolInstance] =
    state.module.members.collect { case instance: ProtocolInstance => instance }

  private def instance(state: CompilerState, name: String): ProtocolInstance =
    val owner = protocol(state, name).id
    instances(state)
      .find(_.protocol.resolvedId == owner)
      .getOrElse(fail(s"Missing fixture instance $name"))

  private def errors(state: CompilerState): List[ProtocolError] =
    state.errors.collect { case SemanticError.ProtocolErrorFound(error, _) => error }.toList

  private def rejection(state: CompilerState, entry: ProtocolInstance): Vector[DiagnosticId] =
    val owner = entry.protocol.resolvedId.get
    val index = state.module.resolvables
    assertEquals(state.protocols.instance(owner, entry.concreteType, index), Right(entry.id.get))
    assert(!state.protocols.instanceIds(owner).contains(entry.id.get))
    state.protocols.usableInstance(owner, entry.concreteType, index) match
      case Left(ProtocolLookupError.RejectedDeclaration(causes)) =>
        assert(causes.nonEmpty)
        causes
      case other => fail(s"Expected rejected prerequisite instance, got $other")

  private def lambda(binding: Bnd): Lambda = binding.value.terms match
    case (body: Lambda) :: Nil => body
    case other => fail(s"Expected one executable lambda, got $other")

  private val declarations = List(
    "protocol Render 'T where 'T: Describe & Display & Text = fn render(value: 'T): 'T; ;",
    "protocol Describe 'T where 'T: Text = fn describe(value: 'T): 'T; ;",
    "protocol Display 'T where 'T: Text = fn display(value: 'T): 'T; ;",
    "protocol Text 'T = fn text(value: 'T): 'T; ;"
  )

  private val implementations = List(
    "instance Render Int = fn render(value) = value;; ;",
    "instance Describe Int = fn describe(value) = value;; ;",
    "instance Display Int = fn display(value) = value;; ;",
    "instance Text Int = fn text(value) = value;; ;"
  )

  List(false, true).foreach { reverseProtocols =>
    List(false, true).foreach { reverseInstances =>
      test(
        s"diamond instances, reversed protocols $reverseProtocols, instances $reverseInstances"
      ):
        val headers = if reverseProtocols then declarations.reverse else declarations
        val bodies  = if reverseInstances then implementations.reverse else implementations
        val source  = (bodies ++ headers).mkString("\n")
        resolve(source).map { initial =>
          val original = instances(initial)
          val result   = ProtocolChecker.rewriteModule(initial)
          assertEquals(result.errors, Vector.empty)
          assertEquals(instances(result).map(_.id), original.map(_.id))
          assertEquals(
            result.module.members.filterNot(_.isInstanceOf[Bnd]).collect { case node: FromSource =>
              node.source
            },
            initial.module.members.collect { case node: FromSource => node.source }
          )
          original.zip(instances(result)).foreach { (written, lowered) =>
            assertEquals(lowered.source, written.source)
            assertEquals(lowered.members, Nil)
            assertEquals(lowered.memberIds, written.members.flatMap(_.binding.id))
            assertEquals(
              result.protocols.usableInstance(
                lowered.protocol.resolvedId.get,
                lowered.concreteType,
                result.module.resolvables
              ),
              Right(lowered.id.get)
            )
            written.members.foreach { member =>
              val binding = result.module.resolvables.lookup(member.binding.id.get) match
                case Some(binding: Bnd) => binding
                case other => fail(s"Expected an indexed implementation, got $other")
              assertEquals(binding.source, member.binding.source)
              assertEquals(
                lambda(binding).params.map(_.id),
                lambda(member.binding).params.map(_.id)
              )
              assertEquals(lambda(binding).body, lambda(member.binding).body)
              assertEquals(
                ResolvablesIndexer.definitions(result.module).count(_.id == binding.id),
                1
              )
            }
          }
          val repeated = ProtocolChecker.rewriteModule(result)
          assertEquals(repeated.module, result.module)
          assertEquals(repeated.protocols, result.protocols)
          assertEquals(repeated.bindingIds, result.bindingIds)
          assertEquals(repeated.errors, Vector.empty)
        }
    }
  }

  test("an unused instance missing a prerequisite is rejected at its header and keeps its body"):
    val source = """
      protocol Render 'T where 'T: Describe = fn render(value: 'T): 'T; ;
      protocol Describe 'T = fn describe(value: 'T): 'T; ;
      instance Render Int = fn render(value) = value;; ;
    """
    check(source).map { state =>
      val entry    = instance(state, "Render")
      val required = protocol(state, "Render").constraints.head.constraints.head
      assertEquals(errors(state), List(ProtocolError.MissingPrerequisiteInstance(entry, required)))
      val roots = rejection(state, entry)
      assertEquals(roots, Vector(state.diagnostics.records.head.id))
      val signature = protocol(state, "Render").signatures.head
      assertEquals(
        state.protocols
          .signatureCandidates(signature.id.get, state.module.resolvables)
          .map(_.implementations),
        Right(Nil)
      )
      assertEquals(
        state.protocols.memberBinding(entry.id.get, signature.id.get, state.module.resolvables),
        Left(ProtocolLookupError.RejectedDeclaration(roots))
      )
      assertEquals(entry.memberIds.size, 1)
      assertEquals(
        state.module.members.collect { case binding: Bnd => binding.id },
        entry.memberIds.map(Some(_))
      )

      val span = entry.protocol.spanOpt.get
      assertEquals(Diagnostics.fromCompilerState(state).map(_.range), List(Range.fromSrcSpan(span)))
      val message = "Instance 'Render Int' requires an instance of 'Describe Int'"
      assertEquals(Diagnostics.fromCompilerState(state).map(_.message), List(message))
      val printed =
        ErrorPrinter.prettyPrintSemanticErrors(state.semanticErrors.toList, Some(state.sourceInfo))
      assert(printed.contains(message), printed)
      assert(printed.contains(ErrorPrinter.formatLocation(span)), printed)

      val repeated = ProtocolChecker.rewriteModule(state)
      assertEquals(repeated.module, state.module)
      assertEquals(repeated.bindingIds, state.bindingIds)
      assertEquals(
        ResolvablesIndexer.definitions(repeated.module).count(_.id.contains(entry.memberIds.head)),
        1
      )
    }

  List(false, true).foreach { prerequisiteFirst =>
    test(
      s"rejected prerequisite retains its cause, prerequisite first $prerequisiteFirst"
    ):
      val bodies =
        List("instance Render Int = fn render(value) = value;; ;", "instance Describe Int = ;")
      val source = """
        protocol Render 'T where 'T: Describe = fn render(value: 'T): 'T; ;
        protocol Describe 'T = fn describe(value: 'T): 'T; ;
      """ + (if prerequisiteFirst then bodies.reverse else bodies).mkString("\n")
      check(source).map { state =>
        val dependent    = instance(state, "Render")
        val prerequisite = instance(state, "Describe")
        val roots        = rejection(state, prerequisite)
        assertEquals(rejection(state, dependent), roots)
        assertEquals(state.diagnostics.records.size, 2)
        val consequence = state.diagnostics.records.last
        assertEquals(consequence.relation, DiagnosticRelation.Consequence(roots))
        consequence.error match
          case SemanticError.ProtocolErrorFound(
                error: ProtocolError.InvalidPrerequisiteInstance,
                _
              ) =>
            assertEquals(error.instance.id, dependent.id)
            assertEquals(error.required.resolvedId, prerequisite.protocol.resolvedId)
            assertEquals(error.source, dependent.protocol.source)
          case other => fail(s"Expected the rejected prerequisite diagnostic, got $other")
        assert(errors(state).head.isInstanceOf[ProtocolError.MissingMember])
        assert(!errors(state).exists(_.isInstanceOf[ProtocolError.RegistryFailure]))
      }
  }

  test(
    "transitive failures follow prerequisite order and keep the original missing-instance cause"
  ):
    val source = """
      instance Top Int = ;
      instance Middle Int = ;
      protocol Top 'T where 'T: Middle = ;
      protocol Middle 'T where 'T: Base = ;
      protocol Base 'T = ;
    """
    check(source).map { state =>
      val middle = instance(state, "Middle")
      val top    = instance(state, "Top")
      assertEquals(errors(state).map(_.source), List(middle.protocol.source, top.protocol.source))
      assert(errors(state).head.isInstanceOf[ProtocolError.MissingPrerequisiteInstance])
      assert(errors(state)(1).isInstanceOf[ProtocolError.InvalidPrerequisiteInstance])
      val roots = rejection(state, middle)
      assertEquals(rejection(state, top), roots)
      assertEquals(state.diagnostics.records.last.relation, DiagnosticRelation.Consequence(roots))
    }

  test("member-contract and missing-prerequisite failures both survive on a rejected instance"):
    val source = """
      protocol Top 'T where 'T: Middle = ;
      protocol Middle 'T where 'T: Base = fn required(value: 'T): 'T; ;
      protocol Base 'T = ;
      instance Top Int = ;
      instance Middle Int = ;
    """
    check(source).map { state =>
      val roots     = rejection(state, instance(state, "Middle"))
      val primaries = state.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
      assertEquals(primaries.size, 2)
      assertEquals(roots, primaries.map(_.id))
      assertEquals(rejection(state, instance(state, "Top")), roots)
      assertEquals(state.diagnostics.records.last.relation, DiagnosticRelation.Consequence(roots))
    }

  List("fn describe(value: Missing) = value;;", "fn describe(value): Missing = value;;").foreach {
    member =>
      test(
        s"a prerequisite with an unresolved written member type cannot provide evidence: $member"
      ):
        val source = s"""
        protocol Render 'T where 'T: Describe = ;
        protocol Describe 'T = fn describe(value: 'T): 'T; ;
        instance Render Int = ;
        instance Describe Int = $member ;
      """
        check(source).map { state =>
          val primary = state.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
          assertEquals(primary.size, 1)
          assertEquals(undefinedTypeName(primary.head.error), "Missing")
          val roots = Vector(primary.head.id)
          assertEquals(rejection(state, instance(state, "Describe")), roots)
          assertEquals(rejection(state, instance(state, "Render")), roots)
          assertEquals(errors(state).size, 1)
          assert(errors(state).head.isInstanceOf[ProtocolError.InvalidPrerequisiteInstance])
        }
  }

  test("prerequisites use canonical alias identity"):
    val source = """
      type Count = Int;
      type Total = Count;
      protocol Render 'T where 'T: Describe = ;
      protocol Describe 'T = ;
      instance Render Total = ;
      instance Describe Count = ;
    """
    check(source).map { state =>
      assertEquals(state.errors, Vector.empty)
      val index = state.module.resolvables
      List("Render", "Describe").foreach { name =>
        assertEquals(
          state.protocols
            .usableInstance(protocol(state, name).id.get, typeReference("Int", index), index),
          Right(instance(state, name).id.get)
        )
      }
    }

  test("a prerequisite instance for a distinct nominal type does not satisfy the requirement"):
    val source = """
      type Metres = @metal:repr[t=i64];
      type Seconds = @metal:repr[t=i64];
      protocol Render 'T where 'T: Describe = ;
      protocol Describe 'T = ;
      instance Render Seconds = ;
      instance Describe Metres = ;
    """
    check(source).map { state =>
      assertEquals(errors(state).size, 1)
      assert(errors(state).head.isInstanceOf[ProtocolError.MissingPrerequisiteInstance])
      rejection(state, instance(state, "Render"))
      val prerequisite = instance(state, "Describe")
      assertEquals(
        state.protocols.usableInstance(
          prerequisite.protocol.resolvedId.get,
          prerequisite.concreteType,
          state.module.resolvables
        ),
        Right(prerequisite.id.get)
      )
    }

  test("a failed prerequisite requirement leaves the instance identity reserved against aliases"):
    val source = """
      type Count = Int;
      protocol Render 'T where 'T: Describe = ;
      protocol Describe 'T = ;
      instance Render Int = ;
      instance Render Count = ;
    """
    check(source).map { state =>
      val entries   = instances(state)
      val duplicate = errors(state).collect { case error: ProtocolError.DuplicateInstance => error }
      assertEquals(duplicate.size, 1)
      assertEquals(duplicate.head.instance.id, entries(1).id)
      assertEquals(Some(duplicate.head.firstId), entries.head.id)
      val missing = errors(state).collect { case error: ProtocolError.MissingPrerequisiteInstance =>
        error
      }
      assertEquals(missing.map(_.instance.id), List(entries.head.id))
      rejection(state, entries.head)
      val repeated = ProtocolChecker.rewriteModule(state)
      assertEquals(repeated.module, state.module)
      assert(!errors(repeated).exists(_.isInstanceOf[ProtocolError.RegistryFailure]))
    }

  test(
    "a rejected concrete instance does not hide a sibling implementation and uses retain causes"
  ):
    val source = """
      protocol Render 'T where 'T: Describe = fn render(value: 'T): 'T; ;
      protocol Describe 'T = ;
      instance Render Bool = fn render(value) = value;; ;
      instance Render Int = fn render(value) = value;; ;
      instance Describe Int = ;
      let good = render 42;
      let bad = render true;
    """
    parseNotFailed(source).map { parsed =>
      val initial  = prepareForTypeChecking(source, parsed)
      val failed   = instance(initial, "Render")
      val roots    = rejection(initial, failed)
      val result   = TypeChecker.rewriteModule(initial)
      val concrete = protocolImplementation(result, "Render", "Int", "render")
      val good = result.module.members.collectFirst {
        case binding: Bnd if binding.name == "good" => binding
      }.get
      val refs = TermTraversal.collect(good.value) { case ref: Ref => ref }
      assert(refs.exists(_.resolvedId == concrete.id))
      val failures = result.diagnostics.records.filter(_.error match
        case SemanticError.ProtocolErrorFound(_: ProtocolError.MissingInstance, _) => true
        case _ => false)
      assertEquals(failures.size, 1)
      assertEquals(failures.head.relation, DiagnosticRelation.Consequence(roots))
      assert(!errors(result).exists(_.isInstanceOf[ProtocolError.RegistryFailure]))
    }

  List(false, true).foreach { hasPrerequisite =>
    test(s"unused member bodies remain type checked with prerequisite present $hasPrerequisite"):
      val source = """
        protocol Render 'T where 'T: Describe = fn render(value: 'T): Int; ;
        protocol Describe 'T = ;
        instance Render Int = fn render(value) = true;; ;
      """ + (if hasPrerequisite then "instance Describe Int = ;" else "")
      parseNotFailed(source).map { parsed =>
        val initial  = prepareForTypeChecking(source, parsed)
        val memberId = instance(initial, "Render").memberIds.head
        val result   = TypeChecker.rewriteModule(initial)
        val mismatches = result.errors.collect {
          case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
        }
        assert(mismatches.nonEmpty)
        val index = result.module.resolvables
        val intKey = ProtocolRegistry
          .typeKey(typeReference("Int", index), index)
          .fold(error => fail(s"Expected concrete Int: $error"), identity)
        val boolKey = ProtocolRegistry
          .typeKey(typeReference("Bool", index), index)
          .fold(error => fail(s"Expected concrete Bool: $error"), identity)
        assert(
          mismatches.exists(error =>
            ProtocolRegistry.typeKey(error.expected, index).contains(intKey) &&
              ProtocolRegistry.typeKey(error.actual, index).contains(boolKey)
          )
        )
        assertEquals(
          ResolvablesIndexer.definitions(result.module).count(_.id.contains(memberId)),
          1
        )
        assertEquals(
          errors(result).count(_.isInstanceOf[ProtocolError.MissingPrerequisiteInstance]),
          if hasPrerequisite then 0 else 1
        )
      }
  }

package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{CallableTargetAnalysis, ClosureEnvironmentLayout}
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.{collectModuleTerms, existsExpr}

class ClosureEnvironmentTests extends BaseEffFunSuite:

  private val source = """
    fn take(~text: String, n: Int): Int = text.length + n;;
    fn borrow(text: String, n: Int): Int = text.length + n;;
    fn pair(~a: String, b: String, n: Int): Int = a.length + b.length + n;;
    fn inspect(text: String): Int =
      let p = borrow text;
      let mixed = pair (int_to_str 1) text;
      p 1 + mixed 1;
    ;
    fn make(~text: String): Int -> Int =
      let p = take text;
      ~{ extra: Int ->
        let inner = ~{ n: Int -> p n; };
        inner extra;
      };
    ;
    fn captureLiteral(): Int -> Int =
      let value = "abc";
      ~{ n: Int -> value.length + n; };
    ;
    fn captureConditional(flag: Bool): Int -> Int =
      let value = if flag then int_to_str 123; else "abc";;
      ~{ n: Int -> value.length + n; };
    ;
    fn main(): Int =
      let f = make (int_to_str 123);
      f 1 + inspect "abc";
    ;
  """

  private def lambdas(module: Module): List[Lambda] =
    collectModuleTerms(module) {
      case lambda: Lambda if lambda.meta.exists(_.envLayoutId.isDefined) => lambda
    }

  private def layoutId(lambda: Lambda): String =
    lambda.meta.flatMap(_.envLayoutId).getOrElse(fail("Missing environment identity"))

  private def destructors(module: Module): Map[String, DestroyClosureEnvironment] =
    collectModuleTerms(module) { case node: DestroyClosureEnvironment =>
      node.layoutId -> node
    }.toMap

  private def rewrite(module: Module): CompilerState =
    ClosureDestructorBodyGenerator.rewriteModule(
      CompilerState.empty(
        ResolvablesIndexer.refresh(module),
        SourceInfo(source),
        CompilerConfig.default
      )
    )

  private def mapDestructors(module: Module)(
    change: DestroyClosureEnvironment => DestroyClosureEnvironment
  ): Module =
    module.copy(members = module.members.map {
      case binding: Bnd =>
        binding.value.terms match
          case List(lambda: Lambda) =>
            lambda.body.terms match
              case List(node: DestroyClosureEnvironment) =>
                ClosureDestructorAst.withBody(
                  binding,
                  lambda,
                  change(node)
                )
              case _ => binding
          case _ => binding
      case member => member
    })

  private def clearCleanup(module: Module): Module =
    mapDestructors(module)(_.copy(fields = Nil, borrowedFields = Set.empty))

  private def appendOccurrence(module: Module, lambda: Lambda): Module =
    val binding = Bnd(
      source   = lambda.source,
      nameNode = Name.synth("occurrence"),
      value    = Expr(lambda.source, List(lambda), typeSpec = lambda.typeSpec),
      typeSpec = lambda.typeSpec,
      id       = "Test::bnd::occurrence".some
    )
    module.copy(members = module.members :+ binding)

  private def messages(state: CompilerState): List[String] =
    state.semanticErrors.toList.map(_.message)

  test("nested environments preserve capture ownership by layout identity despite equal names") {
    semNotFailed(source).map { module =>
      val original = lambdas(module)
      assert(original.exists(_.captures.exists(_.isInstanceOf[Capture.BorrowedRef])))
      assert(original.exists(_.captures.exists(_.isInstanceOf[Capture.OwnedClosure])))
      assert(
        original.exists(lambda =>
          TermTraversal
            .collect(lambda.body) {
              case inner: Lambda if inner.isMove && inner.captures.nonEmpty => inner
            }
            .nonEmpty
        )
      )
      assertEquals(original.map(layoutId).distinct.size, original.size)

      val ids = original.map(layoutId).toSet
      val renamed = module.copy(members = module.members.map {
        case layout: TypeStruct if layout.id.exists(ids.contains) =>
          layout.copy(nameNode = Name.synth("same_display_name"))
        case member => member
      })
      val result = rewrite(clearCleanup(renamed))
      assertEquals(messages(result), Nil)
      val expected = destructors(module)
      val actual   = destructors(result.module)
      assertEquals(actual.keySet, expected.keySet)
      assert(expected.values.exists(_.borrowedFields.nonEmpty))
      expected.foreach { (id, node) =>
        assertEquals(actual(id).fields, node.fields)
        assertEquals(actual(id).borrowedFields, node.borrowedFields)
        assert(actual(id).borrowedFields.intersect(actual(id).fields.map(_.fieldId).toSet).isEmpty)
        assertEquals(DestructionValidator.validate(actual(id), result.module.resolvables), Nil)
      }

      val plans = ClosureEnvironmentLayout
        .analyze(result.module, CallableTargetAnalysis.analyze(result.module))
        .getOrElse(fail("Expected independent physical plans for distinct layout identities"))
      original.foreach { lambda =>
        val plan = plans(layoutId(lambda))
        assertEquals(
          plan.captures.map(_.capture.ref.resolvedId),
          lambda.captures.map(_.ref.resolvedId)
        )
        val layout = module.resolvables
          .lookupType(layoutId(lambda))
          .collect { case value: TypeStruct =>
            value
          }
          .getOrElse(fail("Missing generated layout"))
        assertEquals(
          plan.captures.map(_.semanticField.id),
          layout.fields.toList.drop(if lambda.isMove then 1 else 0).map(_.id)
        )
      }
    }
  }

  test("conflicting capture ownership is rejected before cleanup or physical planning") {
    semNotFailed(source).map { module =>
      val original = lambdas(module)
        .find(_.captures.exists(_.isInstanceOf[Capture.OwnedClosure]))
        .getOrElse(fail("Expected an owned callable capture"))
      val conflicting = original.copy(captures = original.captures.map {
        case owned: Capture.OwnedClosure => Capture.BorrowedRef(owned.ref)
        case capture => capture
      })
      val input  = appendOccurrence(clearCleanup(module), conflicting)
      val result = rewrite(input)
      assertEquals(
        messages(result),
        List(s"Conflicting closure environment metadata: ${layoutId(original)}")
      )
      assertEquals(destructors(result.module)(layoutId(original)).fields, Nil)
      assert(destructors(result.module).exists { (id, node) =>
        id != layoutId(original) && node.fields.nonEmpty
      })
      val plan = ClosureEnvironmentLayout.analyze(
        result.module,
        CallableTargetAnalysis.analyze(result.module)
      )
      assertEquals(plan.left.toOption.map(_.message), messages(result).headOption)
      assertEquals(messages(rewrite(input.copy(members = input.members.reverse))), messages(result))
    }
  }

  test("capture identity conflicts are detected even when names and ownership agree") {
    semNotFailed(source).map { module =>
      val original = lambdas(module)
        .find(_.captures.exists(_.isInstanceOf[Capture.OwnedClosure]))
        .getOrElse(fail("Expected an owned callable capture"))
      val conflicting = original.copy(captures = original.captures.map {
        case Capture.OwnedClosure(ref, target) =>
          Capture.OwnedClosure(ref.copy(resolvedId = "different::binding".some), target)
        case capture => capture
      })
      val result = rewrite(appendOccurrence(clearCleanup(module), conflicting))
      assertEquals(
        messages(result),
        List(s"Conflicting closure environment metadata: ${layoutId(original)}")
      )
      assertEquals(destructors(result.module)(layoutId(original)).fields, Nil)
    }
  }

  test("ownership flags, counts, and cleanup witnesses reject conflicting occurrences") {
    semNotFailed(source).map { module =>
      val template = lambdas(module)
        .find(lambda =>
          lambda.isMove && lambda.captures.size == 1 &&
            lambda.captures.head.isInstanceOf[Capture.CapturedRef]
        )
        .getOrElse(fail("Expected one owned value capture"))
      val owned = lambdas(module)
        .find(_.captures.exists(_.isInstanceOf[Capture.OwnedClosure]))
        .getOrElse(fail("Expected an owned closure capture"))
      val literal = lambdas(module)
        .find(_.captures.exists(_.isInstanceOf[Capture.CapturedLiteral]))
        .getOrElse(fail("Expected a literal capture"))
      val conditional = lambdas(module)
        .find(_.captures.exists(_.isInstanceOf[Capture.CapturedConditional]))
        .getOrElse(fail("Expected a conditional capture"))

      case class Conflict(name: String, original: Lambda, conflicting: Lambda)

      def captureConflict(name: String, original: Lambda)(
        change: PartialFunction[Capture, Capture]
      ): Conflict =
        Conflict(
          name,
          original,
          original.copy(captures =
            original.captures.map(capture => change.applyOrElse(capture, identity[Capture]))
          )
        )

      val cases = List(
        Conflict("move ownership", template, template.copy(isMove = false)),
        Conflict(
          "partial application",
          template,
          template.copy(meta =
            template.meta.map(meta => meta.copy(isPartialApplication = !meta.isPartialApplication))
          )
        ),
        Conflict(
          "capture count",
          template,
          template.copy(captures = template.captures ++ template.captures)
        ),
        captureConflict("closure destructor", owned) { case Capture.OwnedClosure(ref, target) =>
          Capture.OwnedClosure(ref, s"other::$target")
        },
        captureConflict("literal clone", literal) { case Capture.CapturedLiteral(ref, clone) =>
          Capture.CapturedLiteral(ref, s"other::$clone")
        },
        captureConflict("conditional witness", conditional) {
          case Capture.CapturedConditional(ref, witness, clone) =>
            Capture
              .CapturedConditional(ref, witness.copy(resolvedId = "Other::witness".some), clone)
        },
        captureConflict("conditional clone", conditional) {
          case Capture.CapturedConditional(ref, witness, clone) =>
            Capture.CapturedConditional(ref, witness, s"other::$clone")
        }
      )

      cases.foreach { scenario =>
        val id = layoutId(scenario.original)
        val compatible = ClosureEnvironments.analyze(
          List(scenario.original, scenario.original.copy()),
          module.resolvables
        )
        assertEquals(compatible.errors.map(_.message), Nil, scenario.name)
        assertEquals(compatible.byId.keySet, Set(id), scenario.name)

        val conflicting = ClosureEnvironments.analyze(
          List(scenario.original, scenario.conflicting),
          module.resolvables
        )
        assertEquals(
          conflicting.errors.map(_.message),
          List(s"Conflicting closure environment metadata: $id"),
          scenario.name
        )
        assertEquals(conflicting.byId.keySet, Set.empty[String], scenario.name)
      }
    }
  }

  test("equivalent occurrences preserve the same destruction contract") {
    semNotFailed(source).map { module =>
      val original = lambdas(module)
        .find(lambda =>
          lambda.isMove && lambda.captures.exists(_.isInstanceOf[Capture.BorrowedRef])
        )
        .getOrElse(fail("Expected a move closure with a borrowed payload"))
      val expected = destructors(module)(layoutId(original))
      assert(expected.fields.nonEmpty)
      assert(expected.borrowedFields.nonEmpty)
      val result = rewrite(appendOccurrence(clearCleanup(module), original.copy()))
      assertEquals(messages(result), Nil)
      destructors(module).foreach { (id, expected) =>
        assertEquals(destructors(result.module)(id).fields, expected.fields)
        assertEquals(destructors(result.module)(id).borrowedFields, expected.borrowedFields)
      }
    }
  }

  test("missing layouts and capture-count mismatches accumulate without truncating cleanup") {
    semNotFailed(source).map { module =>
      val ids = lambdas(module).filter(_.isMove).map(layoutId)
      assert(ids.size >= 2)
      val absent    = ids.head
      val shortened = ids(1)
      val cleared   = clearCleanup(module)
      val input = cleared.copy(members = cleared.members.flatMap {
        case layout: TypeStruct if layout.id.contains(absent) => Nil
        case layout: TypeStruct if layout.id.contains(shortened) =>
          List(layout.copy(fields = layout.fields.dropRight(1)))
        case member => List(member)
      })
      val result = rewrite(input)
      assertEquals(
        messages(result).toSet,
        Set(
          s"Missing closure environment layout: $absent",
          s"Closure environment capture count mismatch: $shortened"
        )
      )
      assertEquals(messages(result).size, 2)
      List(absent, shortened).foreach { id =>
        assertEquals(destructors(result.module)(id).fields, Nil)
      }
    }
  }

  test("a usable capturing lambda without a layout identity accumulates a diagnostic") {
    semNotFailed(source).map { module =>
      val original = lambdas(module).head
      val missing  = original.copy(meta = original.meta.map(_.copy(envLayoutId = None)))
      val result   = rewrite(appendOccurrence(module, missing))
      assertEquals(messages(result), List("Capturing lambda missing environment layout identity"))
    }
  }

  test("move layouts without their owner report missing metadata and retain empty cleanup") {
    semNotFailed(source).map { module =>
      val owner = module.members
        .collectFirst { case binding: Bnd if binding.name == "make" => binding }
        .getOrElse(fail("Expected the function creating owned closures"))
      val orphaned = TermTraversal
        .collect(owner.value) {
          case lambda: Lambda if lambda.meta.exists(_.envLayoutId.isDefined) => layoutId(lambda)
        }
        .toSet
      assert(orphaned.nonEmpty)

      val input = clearCleanup(module.copy(members = module.members.filterNot {
        case binding: Bnd => binding.id == owner.id
        case _ => false
      }))
      val result = rewrite(input)
      assertEquals(
        messages(result).toSet,
        orphaned.map(id => s"Missing move-lambda metadata for layout: $id")
      )
      assertEquals(messages(result).size, orphaned.size)
      val actual = destructors(result.module)
      orphaned.foreach(id => assertEquals(actual(id).fields, Nil))
      assert(actual.exists { (id, node) => !orphaned.contains(id) && node.fields.nonEmpty })
    }
  }

  test("a destructor referring to a borrowed environment reports the ownership mismatch") {
    semNotFailed(source).map { module =>
      val borrowed = lambdas(module)
        .find(!_.isMove)
        .getOrElse(fail("Expected a borrowed environment"))
      val borrowedId = layoutId(borrowed)
      val moveId     = destructors(module).keys.head
      val input = mapDestructors(clearCleanup(module)) { node =>
        if node.layoutId == moveId then node.copy(layoutId = borrowedId) else node
      }
      val result = rewrite(input)
      assertEquals(
        messages(result),
        List(s"Destructor refers to a borrowed closure environment: $borrowedId")
      )
      assertEquals(destructors(result.module)(borrowedId).fields, Nil)
    }
  }

  test("environment destructor lookup uses its typed layout reference despite a renamed helper") {
    semNotFailed(source).map { module =>
      val helpers = module.members.collect {
        case binding: Bnd if existsExpr(binding.value) { case _: DestroyClosureEnvironment =>
              true
            } =>
          binding
      }
      assert(helpers.size >= 2)
      assertEquals(DestructionTargets.forEnvironment("unknown::layout", module.resolvables), None)
      val staleId = destructors(module).keys.head
      val mismatched = ResolvablesIndexer.refresh(mapDestructors(module) { node =>
        if node.layoutId == staleId then node.copy(layoutId = "unknown::layout") else node
      })
      assertEquals(DestructionTargets.forEnvironment(staleId, mismatched.resolvables), None)

      val ids = helpers.flatMap(_.id).toSet
      val renamed = ResolvablesIndexer.refresh(module.copy(members = module.members.map {
        case binding: Bnd if binding.id.exists(ids.contains) =>
          binding.copy(nameNode = Name.synth(s"renamed_${binding.name}"))
        case member => member
      }))
      helpers.foreach { helper =>
        TermTraversal
          .collect(helper.value) { case node: DestroyClosureEnvironment => node }
          .foreach { node =>
            assertEquals(
              DestructionTargets.forEnvironment(node.layoutId, renamed.resolvables),
              helper.id
            )
          }
      }
    }
  }

package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class DestructionTests extends BaseEffFunSuite:
  private val source = """
    struct Pair { a: String, b: String };
    fn make(~s: String): Unit -> Unit = ~{ println s; };;
    fn consume(~f: Unit -> Unit): Unit = f ();;
    fn main(seed: Int): Unit =
      let pair = Pair (int_to_str seed) (int_to_str 2);
      let inner = make (int_to_str 3);
      let outer = ~{ println pair.a; inner (); println (int_to_str seed); };
      let alias = outer;
      alias ();
    ;
  """

  private def nodes[A](module: Module)(query: PartialFunction[Term, A]): List[A] =
    module.members.collect { case b: Bnd => b }.flatMap(b => TermTraversal.collect(b.value)(query))

  private def body(module: Module, name: String): Expr =
    module.members
      .collectFirst { case b: Bnd if b.name == name => b.value }
      .getOrElse(fail(s"Missing $name"))

  private def destroys(term: Term): List[DestroyClosure] =
    TermTraversal.collect(term) { case d: DestroyClosure => d }

  test("helper bodies carry resolved layouts, ordered fields and real pointer signatures") {
    semNotFailed(source).map { module =>
      val environments = nodes(module) { case d: DestroyClosureEnvironment => d }
      assertEquals(environments.size, 2)
      assertEquals(nodes(module) { case d: DispatchClosureDestructor => d }.size, 1)
      environments.foreach { d =>
        assertEquals(DestructionValidator.validate(d, module.resolvables), Nil)
        val layout = module.resolvables
          .lookupType(d.layoutId)
          .collect { case s: TypeStruct => s }
          .getOrElse(fail("Missing environment"))
        assert(d.fields.forall(c => layout.fields.exists(_.id.contains(c.fieldId))))
      }
      val outer = environments
        .find(_.fields.exists(_.isInstanceOf[FieldCleanup.Closure]))
        .getOrElse(fail("Expected owning closure capture"))
      assertEquals(outer.fields.size, 2)
      val main = body(module, "main")
      val owner = TermTraversal
        .collect(main) { case l: Lambda => l.params }
        .flatten
        .find(_.name == "outer")
        .getOrElse(fail("Missing owner"))
      val cleanup = destroys(main)
      assertEquals(cleanup.size, 1)
      assertEquals(
        cleanup.head.operand.terms.collect { case r: Ref => r.resolvedId },
        List(owner.id)
      )
      val target = module.resolvables
        .lookup(cleanup.head.targetId)
        .collect { case b: Bnd => b }
        .getOrElse(fail("Missing helper"))
      assert(
        TermTraversal
          .collect(target.value) { case d: DestroyClosureEnvironment => d }
          .exists(_.layoutId == outer.layoutId)
      )
      assert(!nodes(module) { case a: App if a.fn.isInstanceOf[Ref] => a }.exists { app =>
        val ref = app.fn match
          case r: Ref => r.some
          case _ => none
        ref
          .flatMap(_.typeSpec)
          .collect { case f: TypeFn => f.paramTypes.head }
          .exists {
            case t: TypeRef if t.resolvedId.contains("stdlib::typedef::RawPtr") =>
              app.arg.typeSpec.exists(_.isInstanceOf[TypeFn])
            case _ => false
          }
      })
    }
  }

  test(
    "universal dispatch exists without a local move closure and consumes only owned parameters"
  ) {
    semNotFailed("""
      fn consume(~f: Unit -> Unit): Unit = f ();;
      fn borrow(f: Unit -> Unit): Unit = f ();;
      fn main(): Unit = consume { (); };;
    """).map { module =>
      val consuming = destroys(body(module, "consume"))
      assertEquals(consuming.size, 1)
      val target = module.resolvables
        .lookup(consuming.head.targetId)
        .collect { case b: Bnd => b }
        .getOrElse(fail("Missing universal helper"))
      assertEquals(
        TermTraversal
          .collect(target.value) { case d: DispatchClosureDestructor =>
            d
          }
          .size,
        1
      )
      assertEquals(destroys(body(module, "borrow")), Nil)
      assertEquals(destroys(body(module, "main")), Nil)
    }
  }

  test("borrowed closure captures do not create field cleanup") {
    semNotFailed("""
      fn main(seed: Int): Unit =
        let s = int_to_str seed;
        let borrowed = { println s; };
        borrowed ();
      ;
    """).map { module =>
      assertEquals(nodes(module) { case d: DestroyClosureEnvironment => d }, Nil)
      assertEquals(destroys(body(module, "main")), Nil)
    }
  }

  test("conditional consumption cleans the retained branch by operand identity") {
    semNotFailed("""
      fn consume(~f: Unit -> Unit): Unit = f ();;
      fn main(seed: Int, flag: Bool): Unit =
        let f = ~{ println (int_to_str seed); };
        if flag then consume f; else f (); ;
      ;
    """).map { module =>
      val main = body(module, "main")
      val binding = TermTraversal
        .collect(main) { case l: Lambda => l.params }
        .flatten
        .find(_.name == "f")
        .getOrElse(fail("Missing closure binding"))
      assertEquals(
        destroys(main).flatMap(_.operand.terms.collect { case r: Ref =>
          r.resolvedId
        }),
        List(binding.id)
      )
    }
  }

  private def nativeCaptures(freeAnnotation: String, declaration: String): String = s"""
    type Handle = @native[t=*i8, mem=heap$freeAnnotation];
    fn read_handle(h: Handle): Int = @native[tpl="ptrtoint ptr %operand to i64"];;
    fn make(~first: Handle, ~second: Handle): Unit -> Int =
      ~{ read_handle first + read_handle second };
    ;
    $declaration
  """

  List("explicit" -> "close_handle", "default" -> "__free_Handle").foreach {
    (kind, destructorName) =>
      val annotation = if kind == "explicit" then s", free=$destructorName" else ""

      test(s"missing $kind destructor accumulates errors for every owned native capture") {
        semState(nativeCaptures(annotation, "")).map { result =>
          val errors = result.errors.filter(_.message.contains("Missing registered destructor"))
          assertEquals(errors.size, 2, result.errors.toString)
          assert(errors.forall(_.message.contains(destructorName)))
        }
      }

  }

  test("registered destructor preserves each owned native capture cleanup") {
    val declaration = "fn close_handle(~h: Handle): Unit = @native;;"
    semNotFailed(nativeCaptures(", free=close_handle", declaration)).map { module =>
      val destructor = module.members
        .collectFirst { case b: Bnd if b.name == "close_handle" => b }
        .getOrElse(fail("Missing destructor declaration"))
      val env = nodes(module) { case d: DestroyClosureEnvironment => d }.head
      val layout = module.resolvables
        .lookupType(env.layoutId)
        .collect { case s: TypeStruct => s }
        .getOrElse(fail("Missing environment layout"))
      assertEquals(env.fields.map(_.fieldId), layout.fields.toList.drop(1).flatMap(_.id))
      assertEquals(env.fields.map(c => c.targetId.some), List.fill(2)(destructor.id))
      assert(env.fields.forall(_.isInstanceOf[FieldCleanup.Value]))
    }
  }

  List("Int64", "Count").foreach { annotation =>
    test(s"closure destruction accepts $annotation aliases in function types") {
      semNotFailed(s"""
        type Number = Int;
        type Count = Number;
        fn main(n: Int): Unit =
          let f: $annotation -> $annotation = ~{ x: Int -> x + n };
          println (int_to_str (f 2));
        ;
      """).map { module =>
        val cleanup = destroys(body(module, "main"))
        assertEquals(cleanup.size, 1)
        assertEquals(DestructionValidator.validate(cleanup.head, module.resolvables), Nil)
      }
    }
  }

  test("registered destructor accepts aliases in parameter and Unit return types") {
    val declaration = """
      type Resource = Handle;
      type Done = Unit;
      type Finished = Done;
      fn close_handle(~h: Resource): Finished = @native;;
    """
    semNotFailed(nativeCaptures(", free=close_handle", declaration)).map { module =>
      val destructor = module.members
        .collectFirst { case b: Bnd if b.name == "close_handle" => b }
        .getOrElse(fail("Missing destructor declaration"))
      val env = nodes(module) { case d: DestroyClosureEnvironment => d }.head
      assertEquals(env.fields.map(c => c.targetId.some), List.fill(2)(destructor.id))
      assertEquals(DestructionValidator.validate(env, module.resolvables), Nil)
    }
  }

  List("OtherHandle" -> "Unit", "Handle" -> "OtherUnit").foreach { (param, result) =>
    test(s"destructor rejects distinct native types in $param -> $result") {
      val declaration = s"""
        type OtherHandle = @native[t=*i8];
        type OtherUnit = @native[t=void];
        fn close_handle(h: $param): $result = @native;;
      """
      semState(nativeCaptures(", free=close_handle", declaration)).map { state =>
        val errors = state.errors.filter(_.message.contains("Incompatible destructor signature"))
        assertEquals(errors.size, 2, state.errors.toString)
      }
    }
  }

  test("malformed destruction nodes accumulate independent contract errors") {
    semNotFailed(source).map { module =>
      val index = module.resolvables
      val env   = nodes(module) { case d: DestroyClosureEnvironment if d.fields.nonEmpty => d }.head
      val closure = nodes(module) { case d: DestroyClosure => d }.head
      val wrongOperand = Expr(
        SourceOrigin.Synth,
        List(LiteralUnit(SourceOrigin.Synth, closure.typeSpec)),
        typeSpec = closure.typeSpec
      )
      val cases = List(
        closure.copy(operand = wrongOperand) -> "requires a function",
        closure.copy(targetId = "missing") -> "Missing destructor target",
        closure.copy(targetId = "stdlib::bnd::println") -> "Incompatible destructor signature",
        closure.copy(typeSpec = closure.operand.typeSpec) -> "result must be Unit",
        env.copy(layoutId = "missing") -> "Missing closure environment layout",
        env.copy(fields = env.fields :+ env.fields.head) -> "Duplicate field cleanup",
        env.copy(fields = Nil) -> "Missing required field cleanup",
        env.copy(fields =
          List(FieldCleanup.Value("missing", "missing"))
        ) -> "Missing destruction field",
        env.copy(operand = closure.operand) -> "requires RawPtr",
        DispatchClosureDestructor(
          env.source,
          wrongOperand,
          env.typeSpec
        ) -> "Dispatch requires RawPtr",
        env.copy(fields = List(FieldCleanup.Value(env.fields.head.fieldId, "missing"))) ->
          "Missing destructor target"
      )
      cases.foreach { (node, message) =>
        assert(
          DestructionValidator.validate(node, index).exists(_.message.contains(message)),
          message
        )
      }
      val invalid = closure.copy(operand = wrongOperand, targetId = "missing", typeSpec = None)
      assert(DestructionValidator.validate(invalid, index).size >= 3)
    }
  }

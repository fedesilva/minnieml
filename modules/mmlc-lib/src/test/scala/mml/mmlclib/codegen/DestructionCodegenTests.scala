package mml.mmlclib.codegen

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.semantic.DestructionValidator
import mml.mmlclib.test.BaseEffFunSuite

class DestructionCodegenTests extends BaseEffFunSuite:
  private def expression(term: Term): Expr =
    Expr(term.source, List(term), typeSpec = term.typeSpec)

  private def sequence(effect: Expr, result: Expr): Expr =
    val source = SourceOrigin.Synth
    val param  = FnParam(source, Name.synth("ignored"), typeSpec = effect.typeSpec)
    val fnType = TypeFn(
      source,
      NonEmptyList.one(effect.typeSpec.getOrElse(fail("Missing effect type"))),
      result.typeSpec.getOrElse(fail("Missing result type"))
    )
    val lambda = Lambda(source, List(param), result, Nil, typeSpec = fnType.some)
    expression(App(source, lambda, effect, typeSpec = result.typeSpec))

  List("closure", "environment", "dispatch", "disarm").foreach { kind =>
    test(s"$kind destruction with a conditional operand preserves phi predecessors") {
      semNotFailed("""
        fn consume(~f: Unit -> Unit): Unit = f ();;
        fn make(n: Int): Unit -> Unit = ~{ println (int_to_str n); };;
      """).map { module =>
        val candidates = module.members.collect { case b: Bnd => b }.flatMap { binding =>
          binding.value.terms.collect { case l: Lambda => l }.flatMap { lambda =>
            TermTraversal
              .collect(lambda.body) { case d: Destruction => d }
              .map(d => (binding, lambda, d))
          }
        }
        val (binding, lambda, destruction) = candidates
          .find { (_, _, d) =>
            (kind, d) match
              case ("closure", _: DestroyClosure) => true
              case ("environment", _: DestroyClosureEnvironment) => true
              case ("dispatch", _: DispatchClosureDestructor) => true
              case ("disarm", _: DispatchClosureDestructor) => true
              case _ => false
          }
          .getOrElse(fail(s"Missing $kind destruction"))
        val source    = SourceOrigin.Synth
        val boolType  = TypeRef(source, "Bool", resolvedId = "stdlib::typedef::Bool".some)
        val intType   = TypeRef(source, "Int", resolvedId = "stdlib::typealias::Int".some)
        val condition = expression(LiteralBool(source, true, boolType.some))
        val operand = expression(
          Cond(
            source,
            condition,
            destruction.operand,
            destruction.operand,
            typeSpec = destruction.operand.typeSpec
          )
        )
        val cleanup = destruction match
          case d: DestroyClosure => d.copy(operand = operand)
          case d: DestroyClosureEnvironment => d.copy(operand = operand)
          case d: DispatchClosureDestructor if kind == "disarm" =>
            DisarmClosureEnvironment(d.source, operand, "stdlib::bnd::mml_free_raw", d.typeSpec)
          case d: DispatchClosureDestructor => d.copy(operand = operand)
          case d: DisarmClosureEnvironment => d.copy(operand = operand)
        assertEquals(DestructionValidator.validate(cleanup, module.resolvables), Nil)
        val value  = expression(LiteralInt(source, 7, intType.some))
        val branch = sequence(expression(cleanup), value)
        val outer  = expression(Cond(source, condition, branch, value, typeSpec = value.typeSpec))
        val unit   = expression(LiteralUnit(source, destruction.typeSpec))
        val body   = sequence(outer, unit)
        val rewritten = binding.copy(value =
          binding.value.copy(
            terms = List(lambda.copy(body = body))
          )
        )
        val updated = module.copy(
          members = module.members.map {
            case b: Bnd if b.id == binding.id => rewritten
            case member => member
          },
          resolvables = module.resolvables.updated(rewritten)
        )
        List(TargetAbi.AArch64, TargetAbi.X86_64).foreach { abi =>
          val ir = LlvmIrEmitter
            .module(updated, none, "", abi, none, false)
            .fold(e => fail(e.toString), _.ir)
          val emittedBody = functionBody(ir, s"test_${binding.name}")
          assertEquals(phiCount(emittedBody), 2, emittedBody)
          assertPhiPredecessors(emittedBody)
        }
      }
    }
  }

  test("owned native and closure fields are destroyed in order before environment storage") {
    val source = """
      fn main(n: Int): Unit =
        let s = int_to_str n;
        let inner = ~{ println (int_to_str n); };
        let outer = ~{ println s; inner (); };
        outer ();
      ;
    """
    compileAndGenerate(source).map { ir =>
      val bodies = "(?s)define [^\\n]*\\{\\n(.*?)\\n\\}".r
        .findAllMatchIn(ir)
        .map(_.group(1))
        .toList
      val destructor = bodies
        .find(b =>
          b.contains("call void @__free_String") &&
            b.contains("call void @mml_free_raw")
        )
        .getOrElse(fail("Missing field destructor"))
      val valueFree   = destructor.indexOf("call void @__free_String")
      val closureFree = destructor.indexOf("call void @test___free___closure_env_")
      val storageFree = destructor.indexOf("call void @mml_free_raw")
      assert(valueFree < closureFree && closureFree < storageFree, destructor)
      assertEquals("call void @mml_free_raw".r.findAllIn(destructor).size, 1)
      assertEquals("call void @__free_String".r.findAllIn(destructor).size, 1)
    }
  }

  test("destruction after recursion preserves ordinary call and cleanup ordering") {
    compileAndGenerate("""
      fn run(n: Int): Unit =
        if n > 0 then
          let s = int_to_str n;
          let f = ~{ println s; };
          f ();
          run (n - 1);
        ;
      ;
    """).map { ir =>
      val body    = functionBody(ir, "test_run")
      val call    = body.indexOf("call void @test_run")
      val cleanup = body.indexOf("call void @test___free___closure_env_")
      assert(call >= 0 && cleanup > call, body)
      assert(!body.contains("loop.header"), body)
    }
  }

  test("intrinsic evaluates a function-producing operand exactly once on both target ABIs") {
    semNotFailed("""
      fn make(n: Int): Unit -> Unit = ~{ println (int_to_str n); };;
      fn main(): Unit =
        let f = make 42;
        f ();
      ;
    """).map { module =>
      val main = module.members
        .collectFirst { case b: Bnd if b.name == "main" => b }
        .getOrElse(fail("Missing main"))
      val lambda = main.value.terms
        .collectFirst { case l: Lambda => l }
        .getOrElse(fail("Missing lambda"))
      val makeId = module.members.collectFirst { case b: Bnd if b.name == "make" => b.id }.flatten
      val creation = TermTraversal
        .collect(lambda.body) {
          case a: App if a.fn match
                case r: Ref =>
                  r.resolvedId.nonEmpty && r.resolvedId == makeId
                case _ => false
              =>
            a
        }
        .head
      val cleanup = TermTraversal.collect(lambda.body) { case d: DestroyClosure => d }.head
      val operand = Expr(creation.source, List(creation), typeSpec = creation.typeSpec)
      val body =
        Expr(cleanup.source, List(cleanup.copy(operand = operand)), typeSpec = cleanup.typeSpec)
      val rewritten = main.copy(value = main.value.copy(terms = List(lambda.copy(body = body))))
      val updated = module.copy(
        members = module.members.map {
          case b: Bnd if b.id == main.id => rewritten
          case member => member
        },
        resolvables = module.resolvables.updated(rewritten)
      )
      List(TargetAbi.AArch64, TargetAbi.X86_64).foreach { abi =>
        val ir = LlvmIrEmitter
          .module(updated, none, "", abi, none, false)
          .fold(e => fail(e.toString), _.ir)
        val mainBody = functionBody(ir, "test_main")
        assertEquals("call \\{ ptr, ptr \\} @test_make".r.findAllIn(mainBody).size, 1)
        assert(mainBody.indexOf("@test_make") < mainBody.indexOf("extractvalue"), mainBody)
        assertEquals("call void @test___free_closure".r.findAllIn(mainBody).size, 1)
      }
    }
  }

  test("struct destruction keeps the MML ABI while native field destruction follows the target") {
    semNotFailed("""
      struct Pair { a: String, b: String };
      fn main(n: Int): Unit =
        let pair = Pair (int_to_str n) (int_to_str 2);
        let f = ~{ println pair.a; };
        f ();
      ;
    """).map { module =>
      List(TargetAbi.AArch64, TargetAbi.X86_64).foreach { abi =>
        val ir = LlvmIrEmitter
          .module(module, none, "", abi, none, false)
          .fold(e => fail(e.toString), _.ir)
        assertEquals("call void @test___free_Pair\\(%struct.Pair %".r.findAllIn(ir).size, 1)
        assert(!ir.contains("declare void @test___free_Pair"), ir)
        val pairBody = functionBody(ir, "test___free_Pair")
        val nativeSignature =
          if abi == TargetAbi.X86_64 then "call void @__free_String(i64 "
          else "call void @__free_String([2 x i64] "
        assert(pairBody.contains(nativeSignature), pairBody)
      }
    }
  }

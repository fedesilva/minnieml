package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, SemanticStage}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class CallableTypeTests extends BaseEffFunSuite:

  private def assertIntResult(module: Module): Unit =
    val result = module.members
      .collectFirst { case b: Bnd if b.name == "result" => b }
      .getOrElse(fail("Expected result binding"))
    val resultType = result.typeSpec.flatMap(TypeUtils.canonical(_, module.resolvables))
    assertEquals(
      resultType.collect { case ref: TypeRef => ref.resolvedId },
      Some(Some("stdlib::typedef::Int32"))
    )

  for
    (label, resultType) <- List(
      "plain" -> "Int -> Int -> Int",
      "grouped" -> "(Int -> Int -> Int)",
      "aliased" -> "Callback",
      "grouped alias" -> "(Callback)"
    )
    (form, body) <- List(
      "inline" -> "let q = (f ()) 10; q 2;",
      "bound" -> "let g = f (); let q = g 10; q 2;"
    )
  do
    test(s"$form returned $label callable accepts partial application") {
      semNotFailed(s"""
        type Callback = Int -> Int -> Int;
        fn apply(f: Unit -> $resultType): Int = $body;
        let result = apply ???;
      """).map(assertIntResult)
    }

  for (label, body) <- List(
      "reference" -> "f { x -> x; };",
      "returned function" -> "(factory ()) { x -> x; };",
      "field" -> "box.call { x -> x; };",
      "nullary lambda result" -> "({ f; } ()) { x -> x; };"
    )
  do
    test(s"$label shares grouped callable argument inference") {
      semNotFailed(s"""
        type Callback = (Float -> Float) -> Int;
        struct Box { call: (Callback) };
        fn apply(f: (Callback), factory: Unit -> (Callback), box: Box): Int = $body;
        let result = apply ??? ??? ???;
      """).map(assertIntResult)
    }

  test("immediate lambda and local binding retain argument-first inference") {
    semNotFailed("""
      fn apply(): Int =
        let value = { x -> x; } 12;
        value;
      ;
      let result = apply ();
    """).map(assertIntResult)
  }

  test("monomorphic scheme wrappers support reference and returned callable application") {
    val source = """
      type Callback = (Int -> Int);
      fn apply(f: Callback, factory: Unit -> Callback): Int = f 1 + (factory ()) 2;;
      let result = apply ??? ???;
    """
    parseNotFailed(source).map { parsed =>
      val members = parsed.members.map {
        case alias: TypeAlias =>
          alias.copy(typeRef = TypeScheme(alias.source, Nil, alias.typeRef))
        case member => member
      }
      val initial = CompilerState.empty(
        parsed.copy(members = members),
        SourceInfo(source),
        CompilerConfig.default
      )
      val checked = SemanticStage.rewrite(initial)
      assertEquals(checked.errors, Vector.empty)
      assertIntResult(checked.module)
    }
  }

  for (label, expression, expectedBy) <- List(
      ("reference", "f true", Some("f")),
      ("returned function", "(factory ()) true", None),
      ("field", "box.call true", Some("call"))
    )
  do
    test(s"$label preserves argument mismatch location and label") {
      val source = s"""
        struct Box { call: (Int -> Int) };
        fn apply(f: (Int -> Int), factory: Unit -> (Int -> Int), box: Box): Int =
          $expression;
        ;
      """
      semState(source).map { result =>
        val mismatches = result.errors.collect {
          case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
        }
        assertEquals(mismatches.size, 1)
        val mismatch = mismatches.head
        assertEquals(mismatch.expectedBy, expectedBy)
        assertEquals(
          mismatch.actual match
            case ref: TypeRef => ref.resolvedId
            case _ => None,
          Some("stdlib::typedef::Bool")
        )
        assertEquals(
          mismatch.node match
            case expr: Expr =>
              expr.spanOpt.map(span => source.slice(span.start.index, span.end.index).trim)
            case _ => None,
          Some("true")
        )
        assert(!result.errors.exists {
          case SemanticError.TypeCheckingError(_: TypeError.InvalidApplication) => true
          case _ => false
        })
      }
    }

  for expression <- List("value 1", "(factory ()) 1", "box.value 1") do
    test(s"non-callable $expression remains an invalid application") {
      semState(s"""
        struct Box { value: Int };
        fn apply(value: Int, factory: Unit -> Int, box: Box): Int = $expression;;
      """).map { result =>
        val invalid = result.errors.collect {
          case SemanticError.TypeCheckingError(error: TypeError.InvalidApplication) => error
        }
        assertEquals(invalid.size, 1)
      }
    }

  test("a returned partial application retains its fresh callee environment") {
    semNotFailed("""
      fn make(seed: Int): Int -> Int -> Int = ~{ a: Int, b: Int -> seed + a + b; };;
      fn partial(seed: Int): Int -> Int = (make seed) 1;;
      let result = (partial 9) 2;
    """).map(assertIntResult)
  }

  for
    (form, setup, argument) <- List(
      ("inline", "", "((make 9) 1)"),
      ("bound", "let partial = (make 9) 1;", "partial")
    )
    (sink, body) <- List(
      "consuming parameter" -> s"consume $argument;",
      "struct constructor" -> s"let holder = Holder $argument; holder.call 2;"
    )
  do
    test(s"$form returned-callee PAP transfers its fresh environment to a $sink") {
      semNotFailed(s"""
        struct Holder { call: Int -> Int };
        fn make(seed: Int): Int -> Int -> Int = ~{ a: Int, b: Int -> seed + a + b; };;
        fn consume(~call: Int -> Int): Int = call 2;;
        fn run(): Int = $setup $body;
        let result = run ();
      """).map(assertIntResult)
    }

  test("an unknown returned callee cannot lend its environment to an escaping PAP") {
    semState("""
      type Callback = Int -> Int -> Int;
      fn partial(factory: Unit -> Callback): Int -> Int = (factory ()) 1;;
    """).map { result =>
      assert(result.errors.exists {
        case error: SemanticError.InvalidExpression =>
          error.msg == "An ownership sink cannot receive a value with borrowed ownership"
        case _ => false
      })
    }
  }

  test("a retained borrowed callee stays borrowed when the PAP transfers its String payload") {
    semNotFailed("""
      struct Holder { call: String -> Int -> Int };
      fn run(): Int =
        let seed = int_to_str 7;
        let holder = Holder ~{ ~payload: String, n: Int ->
          seed.length + payload.length + n;
        };
        let text = int_to_str 123;
        let partial = ({ holder.call; } ()) text;
        partial 2 + holder.call (int_to_str 4) 3;
      ;
      let result = run ();
    """).map { module =>
      assertIntResult(module)
      val payloadId = resolvedBindingId(module, "run", "text")
      val partials = module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
        TermTraversal.collect(binding.value) {
          case lambda: Lambda
              if lambda.meta.exists(meta =>
                meta.isPartialApplication && meta.transferredCaptures.contains(payloadId)
              ) =>
            lambda
        }
      }
      assertEquals(partials.size, 1)
      val partial = partials.head
      val calleeIds = partial.captures
        .map(_.ref)
        .filter { ref =>
          ref.typeSpec.flatMap(TypeUtils.canonical(_, module.resolvables)).exists {
            case _: TypeFn => true
            case _ => false
          }
        }
        .flatMap(_.resolvedId)
        .toSet
      assertEquals(calleeIds.size, 1)
      assertEquals(partial.meta.map(_.transferredCaptures), Some(Set(payloadId)))
      assertEquals(partial.meta.map(_.borrowedCaptures), Some(calleeIds))
      assertEquals(partial.meta.map(_.retainedCaptures), Some(Set.empty[String]))
      assertEquals(
        partial.captures.collect { case Capture.BorrowedRef(ref) => ref.resolvedId }.flatten.toSet,
        calleeIds
      )
      val destroyedIds = TermTraversal
        .collect(partial.body) { case destroy: DestroyClosure =>
          TermTraversal.collect(destroy.operand) { case ref: Ref => ref.resolvedId }.flatten
        }
        .flatten
        .toSet
      assertEquals(destroyedIds.intersect(calleeIds), Set.empty[String])
    }
  }

  for
    (origin, declaration, parameters) <- List(
      ("unknown", "", "factory: Unit -> Callback"),
      ("native", "fn factory(): Callback = @native[mem=static];;", "")
    )
    (form, setup, argument) <- List(
      ("inline", "", "((factory ()) 1)"),
      ("bound", "let partial = (factory ()) 1;", "partial")
    )
    (sink, callee, body) <- List(
      ("consuming parameter", "consume", s"consume $argument;"),
      ("struct constructor", "__mk_Holder", s"let holder = Holder $argument; holder.call 2;")
    )
  do
    test(s"$form PAP from an $origin returned callee cannot enter a $sink") {
      semState(s"""
        type Callback = Int -> Int -> Int;
        struct Holder { call: Int -> Int };
        fn consume(~call: Int -> Int): Int = call 2;;
        $declaration
        fn run($parameters): Int = $setup $body;
      """).map { result =>
        assertEquals(result.errors.size, 1, result.errors.toString)
        if form == "bound" then
          val errors = result.errors.collect {
            case error: SemanticError.BorrowedValuePassedToConsumingParam => error
          }
          assertEquals(errors.size, 1, result.errors.toString)
          assertEquals(
            errors.head.ref.resolvedId,
            Some(resolvedBindingId(result.module, "run", "partial"))
          )
          assertEquals(
            errors.head.param.id,
            Some(resolvedBindingId(result.module, callee, "call"))
          )
        else
          val errors = result.errors.collect { case error: SemanticError.InvalidExpression =>
            error
          }
          assertEquals(errors.size, 1, result.errors.toString)
          assertEquals(
            errors.head.msg,
            "An ownership sink cannot receive a value with borrowed ownership"
          )
      }
    }

  for (literal, alias, expectedId) <- List(
      ("1", "Int", "stdlib::typedef::Int32"),
      ("1.0", "Float", "stdlib::typedef::Float"),
      ("true", "Bool", "stdlib::typedef::Bool"),
      ("()", "Unit", "stdlib::typedef::Unit"),
      ("\"text\"", "String", "stdlib::typedef::String")
    )
  do
    test(s"literal $literal resolves a grouped alias ascription") {
      semNotFailed(s"type Value = ($alias); let result = $literal: Value;").map { module =>
        val resultLiteral = module.members
          .collectFirst {
            case binding: Bnd if binding.name == "result" => binding.value.terms.head
          }
          .getOrElse(fail("Expected result literal"))
        val ascribedType = resultLiteral.typeAsc.flatMap(TypeUtils.canonical(_, module.resolvables))
        assertEquals(
          ascribedType.collect { case ref: TypeRef => ref.resolvedId }.flatten,
          Some(expectedId)
        )
      }
    }

  test("a returned partial application can retain a static callee") {
    semNotFailed("""
      fn add(a: Int, b: Int): Int = a + b;;
      fn pick(): Int -> Int -> Int = add;;
      fn partial(): Int -> Int = (pick ()) 10;;
      let result = (partial ()) 2;
    """).map(assertIntResult)
  }

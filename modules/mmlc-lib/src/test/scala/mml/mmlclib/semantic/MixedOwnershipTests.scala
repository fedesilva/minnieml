package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class MixedOwnershipTests extends BaseEffFunSuite:

  private val prelude = """
    struct Box { text: String };
    fn take(~text: String): Int = text.length;;
    fn staged(~text: String, extra: Int): Int = text.length + extra;;
    fn borrow(text: String, extra: Int): Int = text.length + extra;;
    fn inc(n: Int): Int = n + 1;;
  """

  private def selected(body: String): String = prelude + s"""
    fn check(flag: Bool): Int =
      let value = if flag then int_to_str 123; else "abc";;
      $body;
    ;
  """

  private val validTransfers = List(
    "named consuming call" -> "take value",
    "two local aliases" -> "let first = value; let second = first; take second",
    "shadowing after an alias transfer" ->
      "let alias = value; (let value = \"shadow\"; take alias + value.length)",
    "inline consumer" -> "{ ~text: String -> text.length; } value",
    "owning field" -> "let box = Box value; box.text.length",
    "borrow before transfer" -> "let size = value.length; size + take value",
    "conditional transfer" -> "if flag then take value; else value.length;",
    "borrow closure retains its source" ->
      "let f = { n: Int -> value.length + n; }; f 1 + f 2 + value.length",
    "move closure borrows its payload repeatedly" ->
      "let f = ~{ n: Int -> value.length + n; }; f 1 + f 2",
    "local move function" ->
      "fn ~measure(n: Int): Int = value.length + n;; measure 1 + measure 2",
    "move closure transfers its payload" ->
      "let f = ~{ n: Int -> take value + n; }; f 1",
    "move closure transfers into a field" ->
      "let f = ~{ n: Int -> let box = Box value; box.text.length + n; }; f 1",
    "normalized capture enters a consuming PAP" ->
      "let f = ~{ unit: Unit -> staged value; }; let p = f (); p 1",
    "nested move capture" -> (
      "let outer = ~{ unit: Unit -> ~{ n: Int -> value.length + n; }; }; " +
        "let inner = outer (); inner 1 + inner 2"
    ),
    "borrowed PAP retains the mixed value" ->
      "let p = borrow value; p 1 + p 2 + value.length"
  )

  validTransfers.foreach { (name, body) =>
    test(s"mixed ownership accepts $name") {
      semNotFailed(selected(body)).void
    }
  }

  private val scopedTransfers = List(
    "consuming result" -> "take (let value = if flag then int_to_str 123; else \"abc\";; value)",
    "borrowed result" -> (
      "let value = (let inner = if flag then int_to_str 123; else \"abc\";; inner); " +
        "value.length + value.length"
    ),
    "conditional scope result" -> (
      "let value = if flag then let inner = int_to_str 123; inner; else \"abc\";; " +
        "let alias = value; take alias"
    ),
    "shadowed result" -> (
      "let value = int_to_str 456; " +
        "let result = (let value = if flag then int_to_str 123; else \"abc\";; value); " +
        "value.length + take result"
    )
  )

  scopedTransfers.foreach { (name, body) =>
    test(s"mixed ownership preserves $name") {
      semNotFailed(prelude + s"fn check(flag: Bool): Int = $body;;").void
    }
  }

  List(
    "conditional borrowing argument" ->
      "let size = borrow (if flag then int_to_str 4567; else text;) 0; size + text.length",
    "conditional local initializer" ->
      "let selected = if flag then int_to_str 4567; else text;; selected.length + text.length"
  ).foreach { (name, body) =>
    test(s"$name borrows an existing owner while retaining fresh allocations") {
      semNotFailed(prelude + s"""
        fn example(flag: Bool): Int =
          let text = int_to_str 123;
          $body;
        ;
      """).void
    }
  }

  test("mixed local borrowing an existing owner cannot enter a consuming call through an alias") {
    semState(prelude + """
      fn example(flag: Bool): Int =
        let text = int_to_str 123;
        let selected = if flag then int_to_str 4567; else text;;
        let alias = selected;
        take alias;
      ;
    """).map { result =>
      assert(
        result.errors.exists {
          case _:     SemanticError.BorrowedValuePassedToConsumingParam => true
          case error: SemanticError.InvalidExpression =>
            error.message.toLowerCase.contains("borrow")
          case _ => false
        },
        result.errors
      )
      assert(!result.errors.exists(_.isInstanceOf[SemanticError.DuplicateName]), result.errors)
    }
  }

  test("named mixed returns can supply stored consuming PAP payloads") {
    semNotFailed(prelude + """
      fn make(flag: Bool): String =
        let value = if flag then int_to_str 123; else "abc";;
        let alias = value;
        alias;
      ;
      fn check(flag: Bool): Int =
        let p = staged (make flag);
        p 1;
      ;
    """).void
  }

  test("mixed static payloads cannot acquire ownership implicitly inside consuming PAPs") {
    semState(selected("let p = staged value; p 1")).map { result =>
      assert(
        result.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.message.contains("stored consuming argument requires ownership on every path")
          case _ => false
        },
        result.errors
      )
    }
  }

  test("mixed global alternatives do not acquire the literal move-capture clone exception") {
    semState("""
      let global = "abc";
      fn check(flag: Bool): Int =
        let value = if flag then int_to_str 123; else global;;
        let f = ~{ n: Int -> value.length + n; };
        f 1;
      ;
    """).map { result =>
      assert(
        result.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.message.contains("capture cannot establish ownership")
          case _ => false
        },
        result.errors
      )
    }
  }

  private val invalidBorrows = List(
    "consuming call" -> "take value",
    "owning field" -> "let box = Box value; box.text.length",
    "move capture" -> "let f = ~{ n: Int -> value.length + n; }; f 1",
    "stored consuming payload" -> "let p = staged value; p 1"
  )

  invalidBorrows.foreach { (name, body) =>
    test(s"mixed borrowed alternatives remain rejected at $name") {
      semState(prelude + s"""
        fn check(flag: Bool, borrowed: String): Int =
          let original = if flag then int_to_str 123; else borrowed;;
          let alias = original;
          let value = alias;
          $body;
        ;
      """).map { result =>
        assert(result.errors.nonEmpty)
        assert(
          result.errors.forall {
            case _:     SemanticError.BorrowedValuePassedToConsumingParam => true
            case _:     SemanticError.CapturedBorrowedHeapBinding => true
            case _:     SemanticError.BorrowEscapeViaReturn => true
            case error: SemanticError.InvalidExpression =>
              error.message.toLowerCase.contains("borrow")
            case _ => false
          },
          result.errors
        )
      }
    }
  }

  test("mixed return cannot promote a borrowed alternative hidden by aliases") {
    semState("""
      fn choose(flag: Bool, borrowed: String): String =
        let first = if flag then int_to_str 123; else borrowed;;
        let second = first;
        second;
      ;
    """).map { result =>
      val borrowedId = TermTraversal
        .collect(member(result.module, "choose").value) { case lambda: Lambda =>
          lambda.params.find(_.name == "borrowed").flatMap(_.id)
        }
        .flatten
        .headOption
      val escaped = result.errors.collect { case error: SemanticError.BorrowEscapeViaReturn =>
        error.ref.resolvedId
      }
      assert(borrowedId.nonEmpty)
      assert(escaped.contains(borrowedId), result.errors)
    }
  }

  test("mixed field aliases cannot escape their local owner through a scope result") {
    semState(prelude + """
      fn choose(flag: Bool): String =
        let value = (
          let owner = Box (int_to_str 123);
          let selected = if flag then int_to_str 456; else owner.text;;
          let alias = selected;
          alias
        );
        value;
      ;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowEscapeViaReturn]),
        result.errors
      )
    }
  }

  List(
    "alias transfer" -> "let alias = value; let size = take alias; size + value.length",
    "consuming call" -> "let size = take value; size + value.length",
    "move capture" -> "let f = ~{ n: Int -> value.length + n; }; f 1 + value.length",
    "conditional transfer" ->
      "let size = if flag then take value; else value.length;; size + value.length",
    "call-once capture" -> "let f = ~{ n: Int -> take value + n; }; let first = f 1; f first"
  ).foreach { (name, body) =>
    test(s"mixed ownership rejects later use after $name") {
      semState(selected(body)).map { result =>
        assert(
          result.errors.exists {
            case _: SemanticError.UseAfterMove => true
            case _: SemanticError.ConsumingParamNotLastUse => true
            case _ => false
          },
          result.errors
        )
      }
    }
  }

  private def member(module: Module, name: String): Bnd =
    module.members.collectFirst { case value: Bnd if value.name == name => value }.get

  private def referencesTo(module: Module, value: Term, target: String): List[Ref] =
    val id = member(module, target).id
    TermTraversal.collect(value) { case ref: Ref if ref.resolvedId == id => ref }

  test("scoped ownership results destroy unrelated locals before their outer continuation") {
    semNotFailed("""
      fn read(text: String): Int = text.length;;
      fn example(flag: Bool): Int =
        let value = (
          let unrelated = int_to_str 456;
          let ignored = unrelated.length;
          let selected = if flag then int_to_str 123; else "abc";;
          selected
        );
        read value;
      ;
    """).map { module =>
      val outer = TermTraversal
        .collect(member(module, "example").value) {
          case binding: BindOwnership if binding.valueParam.name == "value" => binding
        }
        .headOption
        .getOrElse(fail("expected the outer ownership result binding"))
      val unrelatedId = TermTraversal
        .collect(outer.initializer) { case lambda: Lambda =>
          lambda.params.find(_.name == "unrelated").flatMap(_.id)
        }
        .flatten
        .headOption
      val selectedId = TermTraversal
        .collect(outer.initializer) {
          case binding: BindOwnership if binding.valueParam.name == "selected" =>
            binding.valueParam.id
        }
        .flatten
        .headOption
      assert(unrelatedId.nonEmpty)
      assert(selectedId.nonEmpty)
      val freeName = TypeUtils.freeFnFor("String", module.resolvables).get
      val freeId   = member(module, freeName).id

      def destructions(term: Term, ownerId: Option[String]): List[App] =
        TermTraversal.collect(term) { case app: App => app }.filter { app =>
          val callsFree = app.fn match
            case ref: Ref => ref.resolvedId == freeId
            case _ => false
          callsFree && TermTraversal
            .collect(app.arg) {
              case ref: Ref if ref.resolvedId == ownerId => ref
            }
            .nonEmpty
        }

      assertEquals(destructions(outer.initializer, unrelatedId).size, 1)
      assertEquals(destructions(outer.body, unrelatedId).size, 0)
      assertEquals(destructions(outer.initializer, selectedId).size, 0)
      val returnedValues = TermTraversal.collect(outer.initializer) { case result: YieldOwnership =>
        result.value
      }
      assert(
        returnedValues.exists { value =>
          TermTraversal
            .collect(value) {
              case ref: Ref if ref.resolvedId == selectedId => ref
            }
            .nonEmpty
        },
        "the selected result must transfer out of its scope before unrelated-local cleanup"
      )
      assertEquals(referencesTo(module, outer.body, "read").size, 1)
    }
  }

  test("conditional captures use their lexical witness without adding an environment field") {
    semNotFailed("""
      fn make(flag: Bool): Int -> Int =
        let value = if flag then int_to_str 123; else "abc";;
        ~{ n: Int -> value.length + n; };
      ;
    """).map { module =>
      val binding = TermTraversal
        .collect(member(module, "make").value) {
          case binding: BindOwnership if binding.valueParam.name == "value" => binding
        }
        .headOption
        .getOrElse(fail("expected the conditional source binding"))
      val closure = TermTraversal
        .collect(binding.body) {
          case lambda: Lambda if lambda.captures.exists {
                case _: Capture.CapturedConditional => true
                case _ => false
              } =>
            lambda
        }
        .headOption
        .getOrElse(fail("expected a conditional capture in the binding's lexical scope"))
      val conditional = closure.captures.collect { case capture: Capture.CapturedConditional =>
        capture
      }
      assertEquals(conditional.size, 1)
      val capture = conditional.head
      assertEquals(capture.ref.resolvedId, binding.valueParam.id)
      assertEquals(capture.witness.resolvedId, binding.witnessParam.id)
      assert(capture.witness.resolvedId.nonEmpty)
      assert(capture.witness.typeSpec.flatMap(TypeUtils.getTypeName).contains("Bool"))
      assertEquals(
        capture.witness.resolvedId.flatMap(module.resolvables.lookup).flatMap(_.id),
        binding.witnessParam.id
      )
      assertEquals(closure.captures.map(_.ref.resolvedId), List(binding.valueParam.id))
      val environment = module.members
        .collectFirst {
          case layout: TypeStruct if closure.meta.flatMap(_.envStructName).contains(layout.name) =>
            layout
        }
        .getOrElse(fail("expected the closure's registered environment layout"))
      assertEquals(environment.fields.size, 2, "one destructor pointer and one source payload")
      assertEquals(
        environment.fields
          .drop(1)
          .map(field => TypeUtils.canonical(field.typeSpec, module.resolvables)),
        Vector(binding.valueParam.typeSpec.flatMap(TypeUtils.canonical(_, module.resolvables)))
      )
    }
  }

  test("nested ownership predicates stay once in their selected source branches") {
    semNotFailed("""
      fn outer_choice(flag: Bool): Bool = flag;;
      fn inner_choice(flag: Bool): Bool = flag;;
      fn make(outer: Bool, inner: Bool): Int -> Int =
        let value =
          if outer_choice outer then
            if inner_choice inner then int_to_str 123; else "abc";;
          else
            "def";
          ;
        ~{ n: Int -> value.length + n; };
      ;
    """).map { module =>
      val body = member(module, "make").value
      assertEquals(referencesTo(module, body, "outer_choice").size, 1)
      assertEquals(referencesTo(module, body, "inner_choice").size, 1)
      val outer = TermTraversal
        .collect(body) {
          case condition: Cond if referencesTo(module, condition.cond, "outer_choice").nonEmpty =>
            condition
        }
        .headOption
        .getOrElse(fail("expected the source outer conditional"))
      assertEquals(referencesTo(module, outer.ifTrue, "inner_choice").size, 1)
      assertEquals(referencesTo(module, outer.ifFalse, "inner_choice").size, 0)
      assertEquals(referencesTo(module, outer.cond, "inner_choice").size, 0)
    }
  }

  test("owned return through aliases needs neither cloning nor callee destruction") {
    semNotFailed("""
      fn relay(~text: String): String =
        let first = text;
        let second = first;
        second;
      ;
    """).map { module =>
      val body  = member(module, "relay").value
      val clone = TypeUtils.cloneFnFor("String", module.resolvables).get
      val free  = TypeUtils.freeFnFor("String", module.resolvables).get
      assertEquals(referencesTo(module, body, clone).size, 0)
      assertEquals(referencesTo(module, body, free).size, 0)
    }
  }

  test("incidental allocations keep an entirely static result unowned") {
    semNotFailed("""
      fn static_result(flag: Bool): String =
        let temporary = int_to_str 123;
        let ignored = temporary.length;
        if flag then "abc"; else "def";;
      ;
      fn caller(flag: Bool): Int =
        let value = static_result flag;
        value.length;
      ;
    """).map { module =>
      val clone = TypeUtils.cloneFnFor("String", module.resolvables).get
      val free  = TypeUtils.freeFnFor("String", module.resolvables).get
      assertEquals(referencesTo(module, member(module, "static_result").value, clone).size, 0)
      assertEquals(referencesTo(module, member(module, "static_result").value, free).size, 1)
      assertEquals(referencesTo(module, member(module, "caller").value, free).size, 0)
    }
  }

  List(
    "direct" -> "fixed ()",
    "scoped aliases" -> "let first = fixed (); let second = first; second",
    "recursive" -> "recursive 3",
    "indirect static alternatives" -> "let f = if flag then fixed; else nested;; f ()"
  ).foreach { (name, result) =>
    test(s"mixed returns promote proven literal helper results through $name") {
      semNotFailed(s"""
        fn fixed(): String = "abc";;
        fn nested(): String =
          let temporary = int_to_str 123;
          let ignored = temporary.length;
          let value = fixed ();
          value;
        ;
        fn recursive(n: Int): String =
          if n == 0 then nested (); else recursive (n - 1);;
        ;
        fn choose(flag: Bool): String =
          if flag then int_to_str 123; else $result;;
        ;
      """).map { module =>
        val clone = TypeUtils.cloneFnFor("String", module.resolvables).get
        assertEquals(referencesTo(module, member(module, "choose").value, clone).size, 1)
        assertEquals(referencesTo(module, member(module, "nested").value, clone).size, 0)
      }
    }
  }

  test("an unowned native result is not proof of a static return") {
    semState("""
      fn unknown(text: String): String = @native;;
      fn choose(flag: Bool, text: String): String =
        if flag then int_to_str 123; else unknown text;;
      ;
    """).map { result =>
      assert(
        result.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.message.contains("borrowed ownership")
          case _ => false
        },
        result.errors
      )
    }
  }

  List(
    "field" -> "let holder = Holder fixed; holder.f ()",
    "aliased field" -> "let holder = Holder fixed; let f = holder.f; f ()",
    "nested field" -> "let outer = Outer (Holder fixed); outer.inner.f ()",
    "conditional aggregate" ->
      "let holder = if flag then Holder fixed; else Holder alternate;; holder.f ()",
    "returned aggregate" -> "let holder = make (); holder.f ()",
    "generic returned aggregate" -> "let holder = wrap fixed; holder.f ()",
    "repeated identity wrapper" -> "let holder = relay (relay (Holder fixed)); holder.f ()",
    "aliased repeated wrapper" ->
      "let f = relay; let holder = f (f (Holder fixed)); holder.f ()"
  ).foreach { (name, result) =>
    test(s"mixed returns promote known literal helpers through a $name") {
      semNotFailed(s"""
        struct Holder { f: Unit -> String };
        struct Outer { inner: Holder };
        fn fixed(): String = "abc";;
        fn alternate(): String = "def";;
        fn make(): Holder = Holder fixed;;
        fn wrap(~f: Unit -> String): Holder = Holder f;;
        fn relay(~holder: Holder): Holder = holder;;
        fn choose(flag: Bool): String =
          if flag then int_to_str 123; else $result;;
        ;
      """).map { module =>
        val clone = TypeUtils.cloneFnFor("String", module.resolvables).get
        assertEquals(referencesTo(module, member(module, "choose").value, clone).size, 1)
      }
    }
  }

  test("a known field alternative does not prove an unknown aggregate field static") {
    semState("""
      struct Holder { f: Unit -> String };
      fn fixed(): String = "abc";;
      pub fn choose(flag: Bool, unknown: Holder): String =
        let holder = if flag then Holder fixed; else unknown;;
        if flag then int_to_str 123; else holder.f ();;
      ;
    """).map { result =>
      assert(
        result.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.message.contains("borrowed ownership")
          case _ => false
        },
        result.errors
      )
    }
  }

  test("a generic returned field preserves its unknown actual despite a known incoming caller") {
    semState("""
      struct Holder { f: Unit -> String };
      fn fixed(): String = "abc";;
      fn wrap(~f: Unit -> String): Holder = Holder f;;
      fn relay(~holder: Holder): Holder = holder;;
      pub fn choose(flag: Bool, ~unknown: Unit -> String): String =
        let holder = relay (relay (wrap unknown));
        if flag then int_to_str 123; else holder.f ();;
      ;
      fn caller(flag: Bool): Int =
        let value = choose flag fixed;
        value.length;
      ;
    """).map { result =>
      assert(
        result.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.message.contains("borrowed ownership")
          case _ => false
        },
        result.errors
      )
    }
  }

  List(
    ("direct", "", "unknown"),
    ("aliased", "let alias = unknown;", "alias"),
    ("scoped", "", "(let alias = unknown; alias)")
  ).foreach { (name, setup, alternative) =>
    test(s"mixed returns reject a $name unknown callee despite known incoming arguments") {
      semState(s"""
        fn fixed(): String = "abc";;
        pub fn choose(flag: Bool, unknown: Unit -> String): String =
          $setup
          let f = if flag then fixed; else $alternative;;
          if flag then int_to_str 123; else f ();;
        ;
        fn caller(flag: Bool): Int =
          let value = choose flag fixed;
          value.length;
        ;
      """).map { result =>
        assert(result.errors.nonEmpty)
        assert(
          result.errors.forall {
            case error: SemanticError.InvalidExpression =>
              error.message.contains("borrowed ownership")
            case _ => false
          },
          result.errors
        )
      }
    }
  }

  test("resource-free function alternatives satisfy an owning return without a clone helper") {
    semNotFailed(prelude + """
      fn choose(flag: Bool): Int -> Int =
        let value = if flag then int_to_str 123; else "abc";;
        if flag then ~{ n: Int -> value.length + n; }; else inc;;
      ;
      fn check(flag: Bool): Int =
        let f = choose flag;
        f 1 + f 2;
      ;
    """).void
  }

  test("function-bearing global alternatives still require an unavailable clone") {
    semState("""
      struct Holder { f: Int -> Int };
      fn inc(n: Int): Int = n + 1;;
      let global = Holder inc;
      fn choose(flag: Bool): Holder =
        let value = if flag then Holder inc; else global;;
        value;
      ;
    """).map { result =>
      assert(
        result.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.message == "This type cannot be cloned"
          case _ => false
        },
        result.errors
      )
    }
  }

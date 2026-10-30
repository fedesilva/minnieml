package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class CloneContractTests extends BaseEffFunSuite:

  test("a conventionally named native helper does not grant Clone support"):
    semState("""
      type Token = @metal:repr[t=*i8, mem=heap, free=release_token];
      fn release_token(~value: Token): Unit = @metal:ffi[name="free"];;
      fn __clone_Token(value: Token): Token = @metal:ffi[mem=alloc];;
      fn duplicate(value: Token): Token = ^value;;
    """).map { state =>
      assert(state.errors.exists {
        case SemanticError.ProtocolErrorFound(_: ProtocolError.MissingInstance, _) => true
        case _ => false
      })
    }

  List(true, false).foreach { instanceFirst =>
    test(s"source native Clone enables nested struct generation, instance first: $instanceFirst"):
      val instance     = "instance Clone Token = op ^(value) = copy_token value;; ;"
      val structs      = "struct Outer { inner: Inner }; struct Inner { token: Token };"
      val declarations = if instanceFirst then s"$instance $structs" else s"$structs $instance"
      semNotFailed(s"""
        type Token = @metal:repr[t=*i8, mem=heap, free=release_token];
        fn release_token(~value: Token): Unit = @metal:ffi[name="free"];;
        fn copy_token(value: Token): Token = @metal:ffi[mem=alloc];;
        $declarations
        fn duplicate(value: Outer): Outer = ^value;;
      """).map { module =>
        def cloneFor(name: String): Bnd =
          cloneBindingFor(typeReference(name, module.resolvables), module)
            .getOrElse(fail(s"Missing Clone instance for $name"))

        List("Outer" -> "Inner", "Inner" -> "Token").foreach { (outer, inner) =>
          val calls = TermTraversal.collect(cloneFor(outer).value) { case ref: Ref =>
            ref.resolvedId
          }
          assert(calls.contains(cloneFor(inner).id))
        }
      }
  }

  test("an explicit native Clone instance cannot return its borrowed input"):
    semState("""
      type Token = @metal:repr[t=*i8, mem=heap, free=release_token];
      fn release_token(~value: Token): Unit = @metal:ffi[name="free"];;
      instance Clone Token = op ^(value) = value;; ;
    """).map { state =>
      assert(state.errors.exists {
        case SemanticError.InvalidExpression(_, message, _) =>
          message.contains("Clone must return an independently owned value")
        case _ => false
      })
    }

  List(true, false).foreach { instanceFirst =>
    test(s"manual struct Clone supports generated outer instances, instance first: $instanceFirst"):
      val instance = """
        instance Clone PairHolder =
          op ^(value) = PairHolder (make_pair value.pair.left value.pair.right);;
        ;
      """
      val structs  = "struct Outer { inner: PairHolder }; struct PairHolder { pair: NativePair };"
      val declarations = if instanceFirst then s"$instance $structs" else s"$structs $instance"
      semNotFailed(s"""
        type NativePair = @metal:repr { left: Int, right: Int };
        fn make_pair(left: Int, right: Int): NativePair = @metal:ffi;;
        $declarations
        fn duplicate(value: Outer): Outer = ^value;;
      """).map { module =>
        val index = module.resolvables
        assertEquals(cloneBindingFor(typeReference("NativePair", index), module), None)
        val manual = cloneBindingFor(typeReference("PairHolder", index), module)
          .getOrElse(fail("Missing explicit Clone instance for PairHolder"))
        val outer = cloneBindingFor(typeReference("Outer", index), module)
          .getOrElse(fail("Missing generated Clone instance for Outer"))
        val calls = TermTraversal.collect(outer.value) { case ref: Ref => ref.resolvedId }
        assert(calls.contains(manual.id))
      }
  }

  test("an explicit instance conflicts with an eligible generated struct instance"):
    semState("""
      struct Point { x: Int };
      instance Clone Point = op ^(value) = Point value.x;; ;
    """).map { state =>
      assert(state.errors.exists {
        case SemanticError.ProtocolErrorFound(_: ProtocolError.DuplicateInstance, _) => true
        case _ => false
      })
    }

  test("unsupported native fields and function fields prevent generated Clone instances"):
    semNotFailed("""
      type Token = @metal:repr[t=*i8, mem=heap, free=release_token];
      fn release_token(~value: Token): Unit = @metal:ffi[name="free"];;
      struct Resource { token: Token };
      type NativePair = @metal:repr { left: Int, right: Int };
      struct PairHolder { pair: NativePair };
      struct Callback { apply: Int -> Int };
      struct Nested { callback: Callback };
    """).map { module =>
      List("Resource", "Callback", "Nested", "PairHolder").foreach { name =>
        assertEquals(cloneBindingFor(typeReference(name, module.resolvables), module), None)
      }
    }

  test("native Clone helpers explicitly name allocating FFI symbols"):
    semNotFailed("let value = 1;").map { module =>
      List("String", "Buffer", "Rng", "IntArray", "StringArray", "FloatArray").foreach { name =>
        val helper = module.members
          .collectFirst {
            case binding: Bnd if binding.name == s"clone_$name" => binding
          }
          .getOrElse(fail(s"Missing native clone binding for $name"))
        val natives = TermTraversal.collect(helper.value) { case native: NativeImpl => native }
        assertEquals(natives.map(_.nativeSymbol), List(Some(s"__clone_$name")))
        assertEquals(natives.map(_.memEffect), List(Some(MemEffect.Alloc)))
        val instance = cloneBindingFor(typeReference(name, module.resolvables), module)
          .getOrElse(fail(s"Missing Clone instance for $name"))
        val calls = TermTraversal.collect(instance.value) { case ref: Ref => ref.resolvedId }
        assert(calls.contains(helper.id))
      }
    }

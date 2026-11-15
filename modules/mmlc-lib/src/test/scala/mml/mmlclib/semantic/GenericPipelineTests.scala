package mml.mmlclib.semantic

import mml.mmlclib.ast.queries.DeclarationQueries
import mml.mmlclib.test.BaseEffFunSuite

class GenericPipelineTests extends BaseEffFunSuite:

  test("generic declarations and concrete instances share the module and index"):
    semStateNotFailed("fn identity(~value: 'T): 'T = value;; fn main(): Int = identity 7;;")
      .map { state =>
        assert(state.timings.exists(_.name == "fn-monomorphizer"))
        assert(GenericSyntax.exists(state.module))
        val templates = DeclarationQueries.genericFunctions(state.module.resolvables)
        assertEquals(templates.map(_.name), List("identity"))
        assert(state.module.members.contains(templates.head))
        val executable = state.module.copy(members =
          state.module.members.filterNot(DeclarationQueries.isGenericFunction)
        )
        assert(!GenericSyntax.exists(executable))
      }

  test("unused local generic templates remain indexed outside executable bodies"):
    semStateNotFailed("fn outer(): Int = fn identity(value: 'T): 'T = value;; 1;;")
      .map { state =>
        assert(!GenericSyntax.exists(state.module))
        assertEquals(
          DeclarationQueries.genericFunctions(state.module.resolvables).map(_.name),
          List("identity")
        )
      }

  test("a local generic result survives an unannotated parameter and receives type checking"):
    semState("fn outer(): Int = fn inner(value): 'T = value + 1;; inner 1;;")
      .map { state => assert(state.errors.nonEmpty) }

  test("an unbound variable in a body annotation receives a resolution diagnostic"):
    semState("fn outer(): Int = let value: 'T = 1; 1;;").map { state =>
      assert(state.errors.exists {
        case _: SemanticError.UndefinedTypeVariable => true
        case _ => false
      })
    }

  test("explicit function type arguments reach concrete instantiation"):
    semNotFailed("""
      fn identity(~value: 'T): 'T = value;;
      fn increment(value: Int): Int = value + 1;;
      fn main(): Int =
        let selected = identity (Int -> Int) increment;
        selected 7;
      ;
    """)

  test("ordinary protocol variables and concrete instance calls remain executable"):
    semStateNotFailed("""
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      fn main(): Int = identity 1;;
    """).map { result =>
      assert(result.timings.exists(_.name == "type-checker"))
      assert(!GenericSyntax.exists(result.module))
    }

  test("uppercase constructor values remain ordinary value arguments"):
    semStateNotFailed("""
      struct Person { age: Int };
      fn apply(f: Int -> Person): Person = f 1;;
      fn main(): Int = let person = apply Person; person.age;;
    """).map { result =>
      assert(result.timings.exists(_.name == "type-checker"))
      assert(!GenericSyntax.exists(result.module))
    }

  test("specializing a generic main does not create a source entry point"):
    semStateNotFailed("pub fn main(args: 'T): Int = 0;; let selected = main StringArray;")
      .map { state =>
        assertEquals(mml.mmlclib.codegen.EntryPoint.find(state.module), None)
      }

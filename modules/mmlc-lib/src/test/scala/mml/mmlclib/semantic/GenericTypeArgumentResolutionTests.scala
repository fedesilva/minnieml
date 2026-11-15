package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class GenericTypeArgumentResolutionTests extends BaseEffFunSuite:

  private def resolve(code: String): IO[CompilerState] =
    parseNotFailed(code).map { parsed =>
      val initial = CompilerState.empty(
        injectBasicTypes(parsed),
        SourceInfo(code),
        CompilerConfig.default
      )
      val declarations = ConstructorGenerator.rewriteModule(
        TypeResolver.rewriteModule(IdAssigner.rewriteModule(initial))
      )
      ExpressionRewriter.rewriteModule(RefResolver.rewriteModule(declarations))
    }

  private def fixtureBindingId(state: CompilerState, name: String): String =
    val bindings = state.module.members.collect {
      case binding: Bnd if binding.name == name => binding
    }
    assertEquals(bindings.size, 1, s"Expected one fixture binding named $name")
    bindings.head.id.getOrElse(fail(s"Missing identity for fixture binding $name"))

  private def constructor(state: CompilerState, tpe: Type): Bnd =
    state.module.members
      .collectFirst {
        case binding: Bnd
            if binding.meta.exists(_.kind == BindingKind.Constructor) &&
              binding.typeAsc.exists(result => typeIdentity(result) == typeIdentity(tpe)) =>
          binding
      }
      .getOrElse(fail("Missing constructor for fixture type"))

  private def references(state: CompilerState, targetId: String): List[Ref] =
    state.module.members
      .collect { case binding: Bnd => binding.value }
      .flatMap(TermTraversal.collect(_) {
        case ref: Ref if ref.resolvedId.contains(targetId) => ref
      })

  private def constructorReferences(state: CompilerState, typeName: String): List[Ref] =
    val binding = constructor(state, typeReference(typeName, state.module.resolvables))
    references(state, binding.id.getOrElse(fail("Missing constructor identity")))

  private def typeIdentity(tpe: Type): Option[String] = tpe match
    case ref:      TypeRef => ref.resolvedId
    case variable: TypeVariable => variable.resolvedId
    case _ => None

  test("a primitive type prefix becomes explicit arguments without value lookup errors"):
    resolve("fn identity(x: 'T): 'T = x;; let result = identity Int 1;").map { state =>
      assertEquals(state.errors, Vector.empty)
      val use   = references(state, fixtureBindingId(state, "identity")).head
      val intId = typeReference("Int", state.module.resolvables).resolvedId
      assertEquals(use.explicitTypeArgs.toList.flatten.map(typeIdentity), List(intId))
    }

  test("parentheses preserve explicit type selection on a generic callee"):
    resolve("fn identity(x: 'T): 'T = x;; let result = (identity) Int 1;").map { state =>
      assertEquals(state.errors, Vector.empty)
      val use = references(state, fixtureBindingId(state, "identity")).head
      assertEquals(use.explicitTypeArgs.toList.flatten.size, 1)
    }

  test("parenthesized named callees accept certain function type arguments"):
    resolve("fn identity(x: 'T): 'T = x;; let result = (identity) (Int -> Int);")
      .map { state =>
        assertEquals(state.errors, Vector.empty)
        val use = references(state, fixtureBindingId(state, "identity")).head
        assertEquals(use.explicitTypeArgs.toList.flatten.size, 1)
        assert(
          use.explicitTypeArgs.toList.flatten.exists(
            TypeTraversal.exists(_)(
              _.isInstanceOf[TypeFn]
            )
          )
        )
      }

  test("constructor names in a generic prefix select the declared type"):
    resolve(
      "struct Person { age: Int }; fn identity(x: 'T): 'T = x;; " +
        "let result = identity Person;"
    ).map { state =>
      assertEquals(state.errors, Vector.empty)
      val use = references(state, fixtureBindingId(state, "identity")).head
      assertEquals(use.explicitTypeArgs.toList.flatten.size, 1)
      val personId = typeReference("Person", state.module.resolvables).resolvedId
      assertEquals(use.explicitTypeArgs.toList.flatten.map(typeIdentity), List(personId))
      assertEquals(constructorReferences(state, "Person"), Nil)
    }

  test("type selection leaves a following constructor as a value argument"):
    resolve(
      "struct Person { age: Int }; fn identity(x: 'T): 'T = x;; " +
        "let result = identity Int Person;"
    ).map { state =>
      assertEquals(state.errors, Vector.empty)
      val use = references(state, fixtureBindingId(state, "identity")).head
      assertEquals(use.explicitTypeArgs.toList.flatten.size, 1)
      assertEquals(constructorReferences(state, "Person").size, 1)
    }

  test("certain function type arguments retain their resolved components"):
    resolve("fn identity(x: 'T): 'T = x;; let result = identity (Int -> Int);")
      .map { state =>
        assertEquals(state.errors, Vector.empty)
        val use = references(state, fixtureBindingId(state, "identity")).head
        val variables = use.explicitTypeArgs.toList.flatten.flatMap(TypeTraversal.collect(_) {
          case ref: TypeRef => ref
        })
        assertEquals(variables.size, 2)
        assert(variables.forall(_.resolvedId.isDefined))
        assertEquals(variables.map(_.resolvedId).distinct.size, 1)
      }

  test("ordinary constructor arguments are unchanged"):
    resolve(
      "struct Person { age: Int }; fn apply(f: Int -> Person): Person = f 1;; " +
        "let result = apply Person;"
    ).map { state =>
      assertEquals(state.errors, Vector.empty)
      val use = references(state, fixtureBindingId(state, "apply")).head
      assertEquals(use.explicitTypeArgs, None)
      assertEquals(constructorReferences(state, "Person").size, 1)
    }

  test("local generic names retain explicit arguments inside their continuation"):
    resolve("fn outer(): Int = fn identity(x: 'T): 'T = x;; identity Int 1;;")
      .map { state =>
        assertEquals(state.errors, Vector.empty)
        val use = references(state, resolvedBindingId(state.module, "outer", "identity")).head
        assertEquals(use.explicitTypeArgs.toList.flatten.size, 1)
        assert(use.resolvedId.isDefined)
      }

  test("a certain type variable leaves a trailing constructor in value position"):
    resolve(
      "struct Person { age: Int }; fn identity(x: 'T): 'T = x;; " +
        "fn outer(x: 'U): 'U = identity 'U Person;;"
    ).map { state =>
      assertEquals(state.errors, Vector.empty)
      val use = references(state, fixtureBindingId(state, "identity")).head
      assertEquals(use.explicitTypeArgs.toList.flatten.size, 1)
      assert(use.explicitTypeArgs.toList.flatten.forall(_.isInstanceOf[TypeVariable]))
      assertEquals(constructorReferences(state, "Person").size, 1)
    }

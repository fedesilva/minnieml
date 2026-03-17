package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class DataConstructorTests extends BaseEffFunSuite:

  test("struct constructor is synthesized") {
    val code =
      """
        struct Person {
          name: String,
          age: Int
        };
        let p = Person "Fede" 34;
      """

    semNotFailed(code).map { module =>
      val ctor    = module.members.collectFirst { case b: Bnd if b.name == "__mk_Person" => b }
      val ctorBnd = ctor.getOrElse(fail("Expected __mk_Person constructor"))
      val meta    = ctorBnd.meta.getOrElse(fail("Expected constructor meta"))
      assertEquals(meta.originalName, "Person")

      val lambda     = ctorBnd.value.terms.collectFirst { case l: Lambda => l }
      val lambdaNode = lambda.getOrElse(fail("Expected constructor lambda"))
      assertEquals(lambdaNode.params.map(_.name), List("name", "age"))
      assertEquals(
        lambdaNode.params.flatMap(_.typeAsc).map(typeSpecToName),
        List("String", "Int")
      )

      lambdaNode.body.terms.headOption match
        case Some(DataConstructor(_, Some(TypeRef(_, "Person", _, _)))) => ()
        case other => fail(s"Expected DataConstructor for Person, got $other")

      val binding    = module.members.collectFirst { case b: Bnd if b.name == "p" => b }
      val bindingBnd = binding.getOrElse(fail("Expected let binding p"))
      bindingBnd.value.terms.headOption match
        case Some(App(_, _, _, _, _)) => ()
        case other => fail(s"Expected App (constructor call), got $other")
    }
  }

  test("native struct constructor is synthesized") {
    val code =
      """
        type Vec2 = @native { x: Float, y: Float };
      """

    semNotFailed(code).map { module =>
      val ctor    = module.members.collectFirst { case b: Bnd if b.name == "__mk_Vec2" => b }
      val ctorBnd = ctor.getOrElse(fail("Expected __mk_Vec2 constructor"))
      val meta    = ctorBnd.meta.getOrElse(fail("Expected constructor meta"))
      assertEquals(meta.originalName, "Vec2")
      assertEquals(meta.origin, BindingOrigin.Constructor)

      val lambda     = ctorBnd.value.terms.collectFirst { case l: Lambda => l }
      val lambdaNode = lambda.getOrElse(fail("Expected constructor lambda"))
      assertEquals(lambdaNode.params.map(_.name), List("x", "y"))

      lambdaNode.body.terms.headOption match
        case Some(DataConstructor(_, Some(TypeRef(_, "Vec2", _, _)))) => ()
        case other => fail(s"Expected DataConstructor for Vec2, got $other")
    }
  }

  private def typeSpecToName(typeSpec: Type): String =
    typeSpec match
      case TypeRef(_, name, _, _) => name
      case other => other.getClass.getSimpleName

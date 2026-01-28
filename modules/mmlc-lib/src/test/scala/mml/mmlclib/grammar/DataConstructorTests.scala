package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.parser.Parser
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

    Parser
      .parseModule(code, "Test")
      .fold(
        err => fail(s"Parser failed: $err"),
        module => {
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
            case Some(Ref(_, "Person", _, _, _, _, _)) => ()
            case other => fail(s"Expected Person ref, got $other")
        }
      )
  }

  private def typeSpecToName(typeSpec: Type): String =
    typeSpec match
      case TypeRef(_, name, _, _) => name
      case other => other.getClass.getSimpleName

package mml.mmlclib.grammar

import mml.mmlclib.ast.{Bnd, ParsingMemberError, Ref}
import mml.mmlclib.parser.Parser
import mml.mmlclib.test.BaseEffFunSuite

class StructTests extends BaseEffFunSuite:

  test("parse data type"):

    val code =
      """
        struct Person {
          name: String
        };
      """

    Parser
      .parseModule(code, "Test")
      .fold(
        e => fail(s"Parser failed: $e"),
        _ => {
          // println(module)
          ()
        }
      )

  test("empty struct reports member error"):
    val code =
      """
        struct Empty {};
      """

    Parser
      .parseModule(code, "Test")
      .fold(
        e => fail(s"Parser failed: $e"),
        module => {
          val errors = module.members.collect { case err: ParsingMemberError => err }
          assert(errors.nonEmpty, "Expected ParsingMemberError for empty struct")
          assert(errors.exists(_.message.contains("at least one field")))
        }
      )

  test("parse struct selection"):
    val code =
      """
        let n = person.name;
      """

    Parser
      .parseModule(code, "Test")
      .fold(
        e => fail(s"Parser failed: $e"),
        module => {
          val binding = module.members.collectFirst { case b: Bnd if b.name == "n" => b }
          val bnd     = binding.getOrElse(fail("Expected binding named n"))
          bnd.value.terms match
            case List(ref: Ref) =>
              assertEquals(ref.name, "name")
              ref.qualifier match
                case Some(Ref(_, "person", _, _, _, _, _)) => ()
                case other =>
                  fail(s"Expected qualifier Ref(person), got: $other")
            case other =>
              fail(s"Expected single Ref term, got: $other")
        }
      )

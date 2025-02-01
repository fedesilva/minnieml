package mml.mmlclib.grammar

import mml.mmlclib.test.BaseEffFunSuite
import munit.*

@munit.IgnoreSuite
class CommentTests extends BaseEffFunSuite:
  
  test("single line comments are ignored") {
    modNotFailed(
      """
        |# a comment
        |let a = 1
      """.stripMargin)
  }
  
  test("single line comments can go are everywhere") {
    modNotFailed(
      """
        | let a =
        |   1 # Hola, Manola
        |   + # Como
        |   2 # te
        |   + # va,
        |   a # che
        |
      """.stripMargin
    )
  }
  
  test("multiline comments are ignored") {
    modNotFailed(
      """
        |(*
        |
        |MULTI LINE
        |
        |*)
        |
        |let a = 1
      """.stripMargin)
  }
  
  test("multiline doc comments on bindings") {
    modNotFailed(
      """
        |(**
        |
        |This is a let
        |
        |*)
        |
        |let a = 1
      """.stripMargin)
  }
  
  test("multiline doc comments on function definitions") {
    modNotFailed(
      """
        |(**
        |
        |This is a fn
        |
        |*)
        |
        |fn a = 1
      """.stripMargin
    )
  }
  
  test("multiline doc comments are invalid if not in a binding or a definition.") {
    modFailed(
      """
        |let a = 1
        |(**
        |
        |This is a not a let
        |
        |*)
        |
      """.stripMargin)
  }
  
  test("comments interleaved #1") {
    modNotFailed(
      """
        |  let a =
        |    (* fede *)       1 # Hola, Manola
        |           + # Como
        |           2 # te
        |           + # va,
        |           a # che
        |        
      """.stripMargin
    )
  }
  
  test("comments interleaved #2"){
    modNotFailed(
      """
        |let (* a is a parameter *) a = (*now we bind*) 1
      """.stripMargin
    )
  }
  
  
    test("nested doc comments #1") {
      modNotFailed(
        """
          |(**
          | fede
          |
          |  (** lalala *)
          |
          |*)
          |let a = 1
        """.stripMargin)
    }

    test("nested comments #1") {
      modNotFailed(
        """
          |(*
          | fede
          |
          |  (* lalala *)
          |
          |*)
          |let a = 1
        """.stripMargin)
    }

    test("nested comments #2") {
      modNotFailed(
        """
          |(*
          | fede
          |
          |  (** lalala *)
          |
          |*)
          |let a = 1
        """.stripMargin)
    }
  

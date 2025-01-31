package mml.mmlclib.grammar

import mml.mmlclib.test.BaseEffFunSuite

/** Randomly formatted stuff.
 *
 * Mostly things I make up when playing with the grammar, put into a test.
 *
 */
class MiscTests extends BaseEffFunSuite:

  test("let and many comments") {
    modNotFailed(
      """
        |(** Soy la comadreja *)
        |let a =
        |   (
        |    2 +
        |    (8 / 2) # doh
        |    + 4 (*
        |      lalala I lala you
        |    *)
        |    ) -  (* Jaimico *)
        |    4
        |
        |    (* random comment *)
        |
        |    # and another random comment
        |
        |    (* esto es multiline
        |          loco
        |  loco
        |          loco oooooo
        |           (* nested *)
        |           (** nested *)
        |            loco
        |         #colocolocolo
        |    *)
        |
        | fn main args = 0
        |
      """.stripMargin)
  }
  


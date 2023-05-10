package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class MatchTests extends BaseFunSuite:

  test("match all the things") {

    modNotFailed("""
        let a: String =
            x match
              | 1                 = "1"
              | 2                 = "2"
              | (a, _)            = a
              | Person { name }   = name     # nominal
              | {name}            = name     # structural
              | a: String         = a
              | Monday            = "monday"
              | Numbr a           = num2String a
        ;
      """)
  }

  test("match function") {
  
    modNotFailed(
      """
        fn x match
          |  A = "a"
          |  B = "b"
        ;
      """
    )
  
  }

  test("match function with function type pattern") {
  
    modNotFailed(
      """
        fn x match
         |  A = "a"
         | f @ B -> C -> X = "b"
        ;
      """
    )
  
  }

  test("match function with return type") {
    modNotFailed(
      """
        fn  x 'T  : String match
          | A                 = "a"
          | f @ 'T -> String  = f z
        ;
      """
    )
  }

  test("match fn with fnLet") {
    modNotFailed(
      """
        (** lalala *)
        fn x ('T: Real) : String match
          |  A                 = "a"
          | f @ 'T -> String  = f z
          | s =
              let str = show s,
                  otr = "other"
              in
                upperCase (concat str otr)
        ;
      """
    )
  }

  test("nominal match union") {
    modNotFailed(
      """
      let name =
        person match
        |  Person { name } = name
        | _ = "unknown"
      ;
      """
    )
  }

  test("structural match") {
    modNotFailed(
      """
      let name =
        person match
          |  { name } = name
          | _ = "unknown"
      ;
      """
    )
  }

  test("structural match with if, a tuple and meh case") {
    modNotFailed(
      """
      let name =
        person match
          | { name } if name == "fede"  = "große"
          | { name }                    = name
          | (name, _)                   = name
          | _                           = "unknown"
      ;
      """
    )
  }


  test("match record with a guard on member") {
    modNotFailed(
      """
      let name =
        person match
          |  { name } if name == "fede" = name
          | _ = "unknown"
      ;
      """
    )
  }

  test("reference the matched pattern besides its components") {
    modNotFailed("""
        let name =
          person match
          |  p @ Person{ name } if name == "fede" = p.lastName
          | _ = "unknown"
        ;
      """)
  }
  
  test("match lambda literal, passed as a value on application ") {
    modNotFailed(
      """
        fn apply v f = f v;

        # Lambda is disambiguated by using parens, else it looks like a match on `2`
        let x =
          apply 2
            ( _ match
                | 1 = "Uno"
                | _ = "No Uno"
            )
        ;
      """)
  }
  
  test("match lambda, bound to a name") {
    modNotFailed(
      """
        let z =
          _ match
            | 1 = "Uno"
            | _ = "No Uno"
        ;
      """)
  }
  

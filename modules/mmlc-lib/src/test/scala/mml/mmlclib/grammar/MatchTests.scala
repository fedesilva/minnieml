package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class MatchTests extends BaseFunSuite {

  test("match all the things") {

    modNotFailed("""
        let a: String =
            x match
                1                 = "1"
              | 2                 = "2"
              | (a, _)            = "tuple"
              | Person { name }   = name
              | {name}            = name
              | a: String         = a
              | Monday            = "monday"
        ;
      """)
  }

  test("match function") {
  
    modNotFailed(
      """
        fn x match
             A = "a"
          |  B = "b"
        ;
      """
    )
  
  }

  test("match function with function type pattern") {
  
    modNotFailed(
      """
        fn x match
           A = "a"
         | f @ B -> C -> X = "b"
        ;
      """
    )
  
  }

  test("match function with return type") {
    modNotFailed(
      """
        fn x : String match
          A               = "a" |
          f @ 'T -> String = f z
        ;
      """
    )
  }

  test("match fn with fnLet") {
    modNotFailed(
      """
        (** lalala *)
        fn x : String match
            A                 = "a"
          | f @ 'T -> String  = f z
          | x                 =
            let str = show x,
                otr = "other"
            in
              upperCase (concat str otr)
        ;
      """
    )
  }

  test("match union") {
    modNotFailed(
      """
      let name =
        person match
          Person { name } = name
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
            { name }    = name
          | _ = "unknown"
      ;
      """
    )
  }

  test("structural match with if") {
    modNotFailed(
      """
      let name =
        person match
            { name } if name == "fede"  = "grosso"
          | { name }                    = name
          | (name, _)                   = name
          | _                           = "unknown"
      ;
      """
    )
  }

  // FIXME wat
  test("reference the full matched expression") {
    modNotFailed(
      """
      let name =
        person match
            { name } if name == "fede" = name
          | _ = "unknown"
      ;
      """
    )
  }

  test("reference the matched pattern besides its components") {
    modNotFailed("""
        let name =
          person match
            p @ Person{ name } if name == "fede" = p.lastName
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
                  1 = "Uno"
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
              1 = "Uno"
            | _ = "No Uno"
        ;
      """)
  }
  
}

package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class ExportsTests extends BaseFunSuite {

  test("export names verbatim"){
    modNotFailed(
      """
        module A =

          exports =
            x
            a
          ;

          let x = 1;
          let a = 2;

      """)
  }

  test("export names renaming"){
    modNotFailed(
      """
        module A =

          exports =
            x = equis
            a = dos
          ;

          let x = 1;
          let a = 2;

      """)
  }



  // export and type the alias

  test("export with type ascription"){
    modNotFailed(
      """
        module A =

          exports =
            x = equis : Int
            a = dos: String
          ;

          let x = 1;
          let a = "2";

      """)
  }

  // export selection
  
}


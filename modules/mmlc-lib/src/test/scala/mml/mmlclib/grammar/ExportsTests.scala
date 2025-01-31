package mml.mmlclib.grammar

import mml.mmlclib.test.BaseEffFunSuite

class ExportsTests extends BaseEffFunSuite:

  test("export names verbatim"){
    modNotFailed(
      """
        module A =

          exports =
            x
            a
          ;
          
          

          let x = 1
          let a = 2

      """)
  }

  test("export names renaming"){
    modNotFailed(
      """
        module A =

          exports =
            equis = x
            dos   = a
          ;
          
          

          let x = 1
          let a = 2

      """)
  }



  // export and type the alias

  test("export with type ascription"){
    modNotFailed(
      """
        module A =

          exports =
            equis : Int = x
            dos: String = a
          ;
          

          let x = 1
          let a = "2"

      """)
  }

  // export selection
  


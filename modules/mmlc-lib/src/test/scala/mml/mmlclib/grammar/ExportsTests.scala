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

  // export and rename id
  // export and types to the alias
  // export selection
  
}


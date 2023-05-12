package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class ProtocolTests extends BaseFunSuite:

  test("protocol, instance and functions") {
    modNotFailed(
      """
        # Define a protocol / type class
        protocol Num 'T = {
          add      :  'T -> 'T -> 'T
          subtract :  'T -> 'T -> 'T
          multiply :  'T -> 'T -> 'T
          divide   :  'T -> 'T -> 'T
        }
        
        # Define an instance for a concrete type: Int
        instance Num Int = {
          fn add a b        = ???
          fn subtract a b   = ???
          fn multiple a b   = ???
          fn divide a b     = ???
        }
        
        # Define an instance for a concrete type: Float
        instance Num Float = {
          fn add a b        = ???
          fn subtract a b   = ???
          fn multiple a b   = ???
          fn divide a b     = ???
        }
        
        fn sum 'N: Num ( x: 'N y: 'N ): N = Num.add(x, y)
            
        let a = sum 1 2
        
        let b = sum 1.2 2.1
      """
    )

  }

  test("depends: single var, single dep") {
    modNotFailed(
      """
        protocol Semigroup 'T = {
          assoc: 'T -> 'T ->  'T
        }
        
        # A Monoid is a Semigroup with an identity element.
        protocol Monoid 'T <: Semigroup 'T = {
          id:  'T
        }
        
        instance Semigroup Int = {
          fn assoc a b = a + b
        }
        
        instance Monoid Int = {
          fn id = 0
        }
        
      """
    )
  }

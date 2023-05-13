package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class ProtocolTests extends BaseFunSuite:

  test("protocol, instance and functions") {
    modNotFailed(
      """
        # Define a protocol
        protocol Num 'T =
          add      :  'T -> 'T -> 'T
          subtract :  'T -> 'T -> 'T
          multiply :  'T -> 'T -> 'T
          divide   :  'T -> 'T -> 'T
        ;
        
        # Define an instance for a concrete type: Int
        instance Num Int =
          fn add a b        = a + b
          fn subtract a b   = ???
          fn multiple a b   = ???
          fn divide a b     = ???
        ;
        
        instance Num Float =
         fn add a b        = a + b
         fn subtract a b   = ???
         fn multiple a b   = ???
         fn divide a b     = ???
       ;
        
        fn sum 'N: Num ( x: 'N y: 'N ): N = Num.add(x, y)
            
        let a = sum 1 2
        let b = sum 1.2 2.1
        
      """
    )

  }

  test("implies: single var, single dep") {
    modNotFailed(
      """
        
        # A Semigroup defines an associative operation
        protocol Semigroup 'T =
          + : 'T -> 'T ->  'T
        ;
        
        # A Monoid is a Semigroup with an identity element.
        protocol Monoid 'T <: Semigroup 'T =
          zero:  'T
        ;
        
        # concrete instance for Int. if not defined,  monoid instance will not compile.
        instance Semigroup Int =
          op + a b = a + b
        ;
        
        instance Monoid Int =
          let zero = 0
        ;
        
      """
    )
  }

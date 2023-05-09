package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class ProtocolTests extends BaseFunSuite :
  
  test("protocol, instance and functions") {
    modNotFailed(
      """
        # Define a protocol / type class
        protocol Num 'T {
          add       =  (s1: 'T s2: 'T): 'T
          subtract  =  (s1: 'T s2: 'T): 'T
          multiply  =  (s1: 'T s2: 'T): 'T
          divide    =  (s1: 'T s2: 'T): 'T
        }
        
        # Define an instance for a concrete type: Int
        instance Num Int {
          add       = (s1: 'T s2: 'T): 'T = ???
          substract = (s1: 'T s2: 'T): 'T = ???
          multiple  = (s1: 'T s2: 'T): 'T = ???
          divide    = (s1: 'T s2: 'T): 'T = ???
        }
        
        # Define an instance for a concrete type: Float
        instance Num Float {
          add       = (s1: 'T s2: 'T): 'T = ???
          substract = (s1: 'T s2: 'T): 'T = ???
          multiple  = (s1: 'T s2: 'T): 'T = ???
          divide    = (s1: 'T s2: 'T): 'T = ???
        }
        
        fn sum 'N: Num ( x: 'N y: 'N ): N = Num.add(x, y)
            
        let a = sum 1 2
        
        let b = sum 1.2 2.1
      """
    )
    
    
  }

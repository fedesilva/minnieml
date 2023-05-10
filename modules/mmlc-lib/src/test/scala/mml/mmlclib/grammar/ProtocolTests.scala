package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class ProtocolTests extends BaseFunSuite :
  
  test("protocol, instance and functions") {
    modNotFailed(
      """
        # Define a protocol / type class
        protocol Num 'T {
          add       =  (a: 'T b: 'T): 'T
          subtract  =  (a: 'T b: 'T): 'T
          multiply  =  (a: 'T b: 'T): 'T
          divide    =  (a: 'T b: 'T): 'T
        }
        
        # Define an instance for a concrete type: Int
        instance Num Int {
          add       = (a: 'T b: 'T): 'T = ???
          substract = (a: 'T b: 'T): 'T = ???
          multiple  = (a: 'T b: 'T): 'T = ???
          divide    = (a: 'T b: 'T): 'T = ???
        }
        
        # Define an instance for a concrete type: Float
        instance Num Float {
          add       = (a: 'T b: 'T): 'T = ???
          substract = (a: 'T b: 'T): 'T = ???
          multiple  = (a: 'T b: 'T): 'T = ???
          divide    = (a: 'T b: 'T): 'T = ???
        }
        
        fn sum 'N: Num ( x: 'N y: 'N ): N = Num.add(x, y)
            
        let a = sum 1 2
        
        let b = sum 1.2 2.1
      """
    )
    
    
  }

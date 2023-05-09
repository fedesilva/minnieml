package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class TypeTests extends BaseFunSuite:

  test("simple let with type") {
    modNotFailed(
      """
         let c: String = "tres";
      """
    )
  }
  
  test("simple let with type inference") {
    modNotFailed(
      """
         let c = "I know thish ish a shtring, mkay";
      """
    )
  }

  test("multibind let with types") {
    modNotFailed(
      """
        let  a: Int     = 1,
             b: String  = "b"
        ;
      """
    )
  }

  test("simple fn with full param type declaration and return type") {
    modNotFailed(
      """
        # type: Int -> Int -> Real
        fn sum a: Int b: Int : Real =
          a + b
        ;
      """
    )
  }
  
  test("simple fn with full type inference") {
    modNotFailed(
      """
        # type: 'T1 -> 'T2 -> 'R
        fn sum a b =
          a + b
        ;
      """
    )
  }

  test("simple fun with paren disambiguating return type") {
    modNotFailed(
      """
        # since there is not way to know that Real is the return type,
        # instead of the type of the last param, the parens group the formal arguments.        
        fn sum (a b) : Real = a + b;
      """
    )
  }

  test("simple fn with partial type declaration") {
    modNotFailed(
      """
        fn sum a: Int b: Int =
          a + b;
      """
    )
  }

  test("let tuple decon with full type declaration") {
    modNotFailed(
      """
        let ( a :Int , b: Int ) = (1,2);
      """
    )
  }

  test("let tuple decon with partial type declaration") {
    modNotFailed(
      """
        let (a, b: Int) = (1,2);
      """
    )
  }

  test("type declaration") {
    modNotFailed(
      """
        data Person {
          name: String
          age:  Number & Real
        }
      """
    )
  }

  test("data type declaration, construction and access") {
    modNotFailed(
      """
        module A =
        
          (** Represents a person, duh *)
          data Person {
          
            (** The name *)
            name: String
            
            (** The age must be a natural number *)
            age : Natural
            
          }
          
          (** We only care about the name of the Pet *)
          data Pet { name: String }
          
          (*
             Explicit return type, parameter is in a group so the type is not assigned to it,
           but to the function itself.
          
           Type of p: { name: String }
           
           Type of nameOf: 'T { name: String } -> String
          
          *)
          fn nameOf (p) : String = p.name;
          
          let fede = Person "Fede" 35; (* ¬¬ *)
          
          let fido = Pet "fido";
          
          let nameOfPerson = nameOf fede;
          let nameOfPet    = nameOf fido;
        
      """
    )
  }
  test("enum") {
    modNotFailed(
      """
        enum ABCD = | A | B | C | D;
      """
    )
  }

  test("union") {
    modNotFailed(
      """
        (** A union of random rubbish *)
        union X =
          | A
          | B: Int, Int
          | C: { name: String age: Int }
          | D: String -> String
          | E: Real
          | F: { allCaps: String & AllCaps }
        ;
    """
    )
  }

  test("grouped type declarations #1") {
    modNotFailed(
      """
        fn apply val fun : ((String & AllCaps) -> Int) -> Int =
          ???
        ;
      """
    )
  }

  test("grouped type declarations #2") {
    modNotFailed(
      """
        let fun : ((String & AllCaps) -> Int) -> Int = ???;
      """
    )
  }

  test("grouped type declarations #3") {
    modNotFailed(
      """
        fn apply val fun : ( (String & AllCaps) -> Int ) -> Int : O = fun val;
      """
    )
  }

  test("functional type, with hole expression") {
    modNotFailed(
      """
        let fun : String -> String -> String = ???;
      """
    )
  }

  test("fn with type parameters") {
    modNotFailed(
      """
        fn p
          'T1: AllCaps
          a: 'T1
          b: 'T1 = ???;
      """
    )
  }

  test("data type with type parameters") {
    modNotFailed(
      """
        data Data 'T1: Num {
           a:  'T1
           b:  Data
        }
      """
    )
  }

  test("union with type parameter") {
    modNotFailed(
      """
       union BinaryTree 'T1: Num =
         | Leaf: 'T1
         | Branch:  (BinaryTree, BinaryTree)
       ;
      """
    )
  }

  test("union with intersection type parameter") {
    modNotFailed(
      """
        union BinaryTree 'T1: Num & MidiValue =
          | Leaf: 'T1
          | Branch:  (BinaryTree, BinaryTree)
        ;
      """
    )
  }

  test("operator with type params and return type") {
    modNotFailed(
      """
        op +
           'T1: Num
           a:   'T1
           b:   'T1 = ???
       ;
      """
    )
  }

  test(
    "union type with type params, type alias refines a type, type application and constructed type usage"
  ) {
    modNotFailed(
      """
        union NumBTree 'T1: Num =
          | Leaf: 'T1
          | Branch:  (NumBTree, NumBTree)
        ;
        
        # assume Real derives Num
        type RealBtree = NumBTree Real;
        
        let t : RealBTree = Branch (Leaf 1) (Leaf 2);
      """
    )
  }


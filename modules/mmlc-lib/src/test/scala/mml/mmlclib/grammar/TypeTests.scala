package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class TypeTests extends BaseFunSuite {

  test("simple let with type") {
    modNotFailed(
      """
        | let c: String = "tres";
      """.stripMargin
    )
  }

  test("multibind let with types") {
    modNotFailed(
      """
        |let  a:  Int     = 1,
        |     b : String  = "b";
      """.stripMargin
    )
  }

  test("simple fn with full param type declaration and return type") {
    modNotFailed(
      """
        |fn sum a: Int b: Int : Real =
        |  a + b
        |;
      """.stripMargin
    )
  }

  test("simple fun with paren disambiguating return type") {
    modNotFailed(
      """
        |fn sum (a b) : Real = a + b;
      """.stripMargin
    )
  }

  test("simple fn with partial type declaration") {
    modNotFailed(
      """
        |fn sum a: Int b: Int = a + b;
      """.stripMargin
    )
  }

  test("let tuple decon with full declaration") {
    modNotFailed(
      """
        |let ( a :Int , b: Int) = (1,2);
      """.stripMargin
    )
  }

  test("let tuple decon with partial type declaration") {
    modNotFailed(
      """
        |let (a, b: Int) = (1,2);
      """.stripMargin
    )
  }

  test("data type declaration") {
    modNotFailed(
      """
        | data Person {
        |   name: String
        |   age: Number & Real
        | }
      """.stripMargin
    )
  }

  test("data type declaration, construction and access") {
    modNotFailed(
      """
        |module A =
        |
        |(** Represents a person *)
        |data Person {
        |  (** The name *)
        |  name: String
        |  (** The age must be a natural number *)
        |  age : Natural
        |}
        |
        |(** We only name the Pet *)
        |data Pet { name: String }
        |
        |(*
        |   Explicit return type, parameter is in a group so the type is not assigned to it,
        | but to the function itself.
        |
        | Type of p: { name: String }
        |
        |*)
        |fn nameOf (p) : String = p.name;
        |
        |let fede = Person "Fede" 45;
        |
        |let fido = Pet "fido";
        |
        |let namePerson = nameOf fede;
        |let namePet    = nameOf fido;
        |
      """.stripMargin
    )
  }
  test("enum") {
    modNotFailed(
      """
        |enum ABCD = A | B | C | D;
      """.stripMargin
    )
  }

  test("union") {
    modNotFailed(
      """
        |(** A union of random shit *)
        |      union X =
        |          A
        |        | B (Int, Int)
        |        | C { name: String age: Int }
        |        | D String -> String
        |        | E Real
        |        ;
        |
    """.stripMargin
    )
  }

  test("grouped type declarations #1") {
    modNotFailed(
      """
        |fn apply val fun : ((String & AllUp) -> Int) -> Int = ???;
      """.stripMargin
    )
  }

  test("grouped type declarations #2") {
    modNotFailed(
      """
        |let fun : ((String & AllUp) -> Int) -> Int = z;
      """.stripMargin
    )
  }

  test("grouped type declarations #3") {
    modNotFailed(
      """
        |fn apply val fun : ( (String & AllUp) -> Int ) -> Int : O = fun val;
      """.stripMargin
    )
  }

  test("functional type, with hole expression") {
    modNotFailed(
      """
        |let fun : String -> String -> String = ???;
      """.stripMargin
    )
  }

  test("fn with type parameters") {
    modNotFailed(
      """
        |fn p 'T1: AllUp a: 'T1 b: 'T1 = ???;
      """.stripMargin
    )
  }

  test("data type with type parameters") {
    modNotFailed(
      """
        |data Tree 'T1: Num {
        |  payload: 'T1
        |  node1: Tree
        |  node2: Tree
        |}
      """.stripMargin
    )
  }

  test("union with type parameter") {
    modNotFailed(
      """
        |union BinaryTree 'T1: Num =
        |     Leave 'T1
        |  |  Node  (Tree, Tree)
        |;
      """.stripMargin
    )
  }

  test("union with intersection type parameter") {
    modNotFailed(
      """
        |union BinaryTree 'T1: Num & MidiValue =
        |     Leave 'T1
        |  |  Node  (Tree, Tree)
        |;
      """.stripMargin
    )
  }

  test("operator with type params and return type") {
    modNotFailed(
      """
        |op +
        |   'T1: Num
        |   a: 'T1
        |   b: 'T1 : 'T1 = ???;
      """.stripMargin
    )
  }

  test(
    "data type with type params, type alias constructs a type, type application and constructed type usage"
  ) {
    modNotFailed(
      """
        |union NumBTree 'T1: Num =
        |    Leaf 'T1
        |  | Node  (NumBTree, NumBTree)
        |;
        |
        |type RealBtree = NumBTree Real;
        |
        |let t : RealBTree = Node (Leaf 1) (Leaf 2);
      """.stripMargin
    )
  }

}

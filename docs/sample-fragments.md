## Sample fragments

### To profile

    (* fede *)
    type BTree 'T =
        Leaf 'T
      | Node  (BTree, BTree)
    ;
    
    type First3Letters = 
        A
      | B
      | C
    ;
    
    type Person {
      name: String
      age
    }
    
    type NumBTree   = Btree Num;
    type RealBtree  = Btree Real;
    
    let t : RealBTree = Node (Leaf 1) (Leaf 2);



 
package mml.mmlclib.grammar

import mml.mmlclib.test.BaseFunSuite

class ModuleTests extends BaseFunSuite:
  
  test("Simple Module") {
    modNotFailed(
      """
        # a comment
        let a = 1;
      """)
  }
  
  test("Named top level module") {
    modNotFailed(
      """
        module MyModule =
          # a comment
          let a = 1;
      """)
  }
  
  test("Anon top level, nested module") {
    modNotFailed(
      """
        # a comment
        let a = 1;
        
        module Nested =
          # another comment
          let b = 1;
        ;
        
      """)
  }
  
  test("Named top level, nested module") {
    modNotFailed(
      """
        module ATopLevelModule =
        # a comment
        let a = 1;
        
        module Nested =
          # another comment
          let b = 1;
        ;
        
      """)
  }
  
  test("named module with exports table"){
    modNotFailed(
      """
        module F =
          exports =
            a
            Module
            Type
          ;
        
          let a = true;
          fn pr = 9;
        ;
      """
    )
  }
  
  test("named module with exports table, nested modules with access mods"){
    modNotFailed(
      """
        (** A module has a name *) 
        module F =
        
          exports =
            A      # Access: F.A.a
            B.sum  # Access: F.sum
          ;
        
          (** A module is nested and public *)
          pub module A =
            let a = 1;
          ;
        
          protected module B =
            fn sum a b = a + b;
          ;
        
      """
    )
  }
  

# Compiler pipeline

## High level

parse -> semantic analysis -> typechecking -> codegen

## Things to do

- Don't stop on errors:
  we should use validation instead of either and charge forward accumulating errors.
  many of them will cause errors down the line but we want to continue parsing so the user
  has information available for things that work

## multimodule resolution

- parse each module in parallel.
- check internal references and shallow imports ref (do not look outside the module)
- write a symbol table to lang db
- write references to lang db
- wait for all references to update their symbol tables.
- before rewriting, do a second pass on external references.
  - using lang db to find symbols
  - so that we can validate the names are valid
  - what types of references are (fn, bnd, op)
  - so we can collect associativity and precedence information
- proceed to rewrite

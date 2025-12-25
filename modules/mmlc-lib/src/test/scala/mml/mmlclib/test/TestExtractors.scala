package mml.mmlclib.test

import mml.mmlclib.ast.*

import scala.annotation.tailrec

/** Custom extractors for simplifying AST pattern matching in tests. */
object TestExtractors:

  /** Extractor for function applications (`App` nodes).
    *
    * Recursively unwraps nested `App` structures (like `App(_, App(_, fn, arg1), arg2)`) into a
    * tuple containing:
    *   1. The base `Ref` node being applied.
    *   2. An `Option[Bnd]` if the `Ref`'s `resolvedAs` field contains a `Bnd` (function/operator).
    *   3. A flat `List[Expr]` of the arguments applied to the function.
    *
    * Returns `None` if the base term being applied is not a `Ref` or if the input `term` is not an
    * `App` node.
    *
    * Example: `App(_, App(_, Ref(_, "f", ...), arg1), arg2)` becomes
    * `Some((Ref(_, "f", ...), resolvedBndOpt, List(arg1, arg2)))`
    */
  object TXApp:
    def unapply(term: Term): Option[(Ref, Option[Bnd], List[Expr])] =
      // Helper to recursively collect args and find the base term
      @tailrec
      def collect(currentTerm: Term, accumulatedArgs: List[Expr]): Option[(Term, List[Expr])] =
        currentTerm match
          case App(_, fn, arg, _, _) =>
            collect(fn, arg :: accumulatedArgs) // Prepend arg, recurse on fn
          case baseTerm =>
            Some(
              (baseTerm, accumulatedArgs)
            ) // Base case: return the non-App term and collected args

      term match
        // Start collection only if the input is an App
        case app: App =>
          collect(app, Nil)
            .flatMap { // flatMap to handle potential None from collect (though unlikely with current @tailrec impl)
              case (ref: Ref, args) => // Check if the base term is a Ref
                val bndOpt = ref.resolvedAs.collect { case bnd: Bnd =>
                  bnd
                } // Extract Bnd if resolvedAs is Some(Bnd)
                Some((ref, bndOpt, args))
              case _ => None // Base term was not a Ref, pattern fails
            }
        case _ => None // Input term was not an App node

package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class ElaborationRecoveryTests extends BaseEffFunSuite:

  private val makers = """
    fn take(~text: String, extra: Int): Int = text.length + extra;;
    fn make(~text: String): Int -> Int = take text;;
    fn wrapped(~text: String): Int -> Int =
      let consuming = take text;
      ~{ extra: Int -> consuming extra; };
    ;
    fn returned(~text: String): Unit -> (Int -> Int) =
      let consuming = take text;
      ~{ consuming; };
    ;
  """

  private def isMismatch(error: SemanticError): Boolean = error match
    case SemanticError.TypeCheckingError(_: TypeError.TypeMismatch) => true
    case _ => false

  List(
    ("unrelated statement", "1;", 1, 0),
    ("discarded second call", "pap 1;", 1, 1),
    ("bound second call", "let second = pap 1;", 0, 1)
  ).foreach { (label, statement, mismatches, moves) =>
    test(s"recovery preserves ownership with $label"):
      semState(s"""
        $makers
        fn example(): Unit =
          let pap = make (int_to_str 123);
          let first = pap 1;
          $statement
          ();
        ;
      """).map { result =>
        assertEquals(result.errors.count(isMismatch), mismatches, result.errors)
        assertEquals(
          result.errors.count(_.isInstanceOf[SemanticError.UseAfterMove]),
          moves,
          result.errors
        )
        assertEquals(result.errors.size, mismatches + moves, result.errors)
        val paps = result.module.members.collect { case b: Bnd => b }.flatMap { binding =>
          TermTraversal.collect(binding.value) {
            case lambda: Lambda if lambda.meta.exists(_.isPartialApplication) => lambda
          }
        }
        assert(paps.nonEmpty)
        assert(paps.forall(_.meta.exists(_.transferredCaptures.nonEmpty)))
      }
  }

  test("an error in another member preserves genuine ownership diagnostics"):
    semState(s"""
      $makers
      fn bad(): Int = true;;
      fn borrowed(text: String): Int -> Int = take text;;
      fn example(): Int =
        let pap = make (int_to_str 123);
        let first = pap 1;
        pap 2;
      ;
    """).map { result =>
      assertEquals(result.errors.count(isMismatch), 1, result.errors)
      assertEquals(
        result.errors.count(_.isInstanceOf[SemanticError.UseAfterMove]),
        1,
        result.errors
      )
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.CapturedBorrowedHeapBinding]),
        result.errors
      )
    }

  test("an invalid PAP cannot supply capture or return ownership"):
    semState(s"""
      $makers
      fn broken(): Unit -> (Int -> Int) =
        let incomplete = take true;
        ~{ incomplete; };
      ;
      fn independent(text: String): String = text;;
    """).map { result =>
      assertEquals(result.errors.count(isMismatch), 1, result.errors)
      val borrowedReturns = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn =>
        e
      }
      assertEquals(borrowedReturns.size, 1, result.errors)
      assert(
        !result.errors.exists(_.isInstanceOf[SemanticError.CapturedBorrowedHeapBinding]),
        result.errors
      )
      assertEquals(result.errors.size, 2, result.errors)
    }

  List("missing", "true").foreach { argument =>
    test(s"unavailable PAP values propagate through aliases: $argument"):
      semState(s"""
        $makers
        fn broken(): Unit -> (Int -> Int) =
          let incomplete = take $argument;
          let alias = incomplete;
          ~{ alias; };
        ;
        fn independent(): Int =
          let pap = make (int_to_str 123);
          let first = pap 1;
          pap 2;
        ;
      """).map { result =>
        assert(
          result.errors.exists {
            case _: SemanticError.UndefinedRef => true
            case error => isMismatch(error)
          },
          result.errors
        )
        assertEquals(
          result.errors.count(_.isInstanceOf[SemanticError.UseAfterMove]),
          1,
          result.errors
        )
        assert(
          !result.errors.exists(_.isInstanceOf[SemanticError.CapturedBorrowedHeapBinding]),
          result.errors
        )
        assert(
          !result.errors.exists(_.isInstanceOf[SemanticError.BorrowEscapeViaReturn]),
          result.errors
        )
      }
  }

  test("an invalid outer application retains independent argument diagnostics"):
    semState(s"""
      $makers
      fn example(): Unit =
        let pap = make (int_to_str 123);
        let first = pap 1;
        let broken = take (let second = pap 2; true);
        ();
      ;
    """).map { result =>
      assertEquals(result.errors.count(isMismatch), 1, result.errors)
      assertEquals(
        result.errors.count(_.isInstanceOf[SemanticError.UseAfterMove]),
        1,
        result.errors
      )
      assertEquals(result.errors.size, 2, result.errors)
    }

  test("valid ownership checks continue after an unavailable value in a statement chain"):
    semState(s"""
      $makers
      fn example(): Unit =
        let broken = take true;
        let pap = make (int_to_str 123);
        let first = pap 1;
        let second = pap 2;
        ();
      ;
    """).map { result =>
      assertEquals(result.errors.count(isMismatch), 1, result.errors)
      assertEquals(
        result.errors.count(_.isInstanceOf[SemanticError.UseAfterMove]),
        1,
        result.errors
      )
      assertEquals(result.errors.size, 2, result.errors)
    }

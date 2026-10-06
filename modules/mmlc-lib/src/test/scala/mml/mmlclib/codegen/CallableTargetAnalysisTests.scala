package mml.mmlclib.codegen

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.CallableTargetAnalysis
import mml.mmlclib.test.BaseEffFunSuite

class CallableTargetAnalysisTests extends BaseEffFunSuite:

  test("a PAP capture identifies its original local entry") {
    semNotFailed("""
      fn main(dummy: Int): Int =
        let add = { x: Int, y: Int -> x + y };
        let addDummy = add dummy;
        addDummy 1;
      ;
    """).map { module =>
      val analysis = CallableTargetAnalysis.analyze(module)
      val refs = module.members
        .collect { case binding: Bnd => binding }
        .flatMap(binding =>
          TermTraversal.collect(binding.value) {
            case lambda: Lambda if lambda.meta.exists(_.isPartialApplication) => lambda
          }
        )
        .flatMap(_.captures.map(_.ref))
        .filter(_.name == "add")
      assertEquals(refs.size, 1)
      assertEquals(analysis.target(refs.head), refs.head.resolvedId)
    }
  }

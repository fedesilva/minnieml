package mml.mmlclib.codegen.emitter

import mml.mmlclib.ast.*
import munit.FunSuite

class FunctionEmitterBorrowClosureValidatorTest extends FunSuite:

  private val synth = SourceOrigin.Synth

  private def synthRef(name: String): Ref =
    Ref(synth, name)

  private def synthExpr(terms: Term*): Expr =
    Expr(synth, terms.toList)

  test("validator allows immediately-invoked borrow lambdas on loopified paths") {
    val param = FnParam(synth, Name.synth("y"))
    val lambda = Lambda(
      source   = synth,
      params   = List(param),
      body     = synthExpr(synthRef("n")),
      captures = List(Capture.CapturedRef(synthRef("n")))
    )
    val body = TailRecCall(
      preStatements = List(
        BoundStatement(
          bindingName = Some("next"),
          expr        = synthExpr(App(synth, lambda, synthExpr(synthRef("acc"))))
        )
      ),
      args = List(synthExpr(synthRef("next")))
    )

    validateLoopifiedBorrowClosures(body) match
      case Left(err) =>
        fail(s"Expected direct lambda invocation to validate, got: ${err.message}")
      case Right(_) =>
        ()
  }

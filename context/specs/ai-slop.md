# Cleanup AI Slop 

Fucking AI agents (claude and codex) generated a bunch of ai slop.

### validate and processnative

// why does this not call validate?
def processNative(state: CompilerState): IO[CompilerState] =

 /** Pure pipeline: validation only. */
  def validate(state: CompilerState): CompilerState =
    state |> CompilerState.timePhase("codegen", "pre-codegen-validation")(runValidation)



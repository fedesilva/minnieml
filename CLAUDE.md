# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IMPORTANT: Before Starting Any Task
- ALWAYS read README.md to understand the project's purpose and current status
- ALWAYS read ALL files in the memory-bank directory, as they contain critical context
- THINK DEEPLY about the information in these files before proceeding with any task
- Do not start making changes until you are told to do so. Always present a plan before proceeding and ask for explicit approval.
- Follow instructions faithfully. If you find instructions are contradictory or impossible to follow, stop, think and explain your problem.
- Limit yourself to simple focused solutions, do not overreach.

## Build and Test Commands
- Build project: `sbt compile`
- Build native image: `sbt "mmlcDistro"`
- Run from source: `sbt "run --help"`
- Run a single test: `sbt "mmlclib/testOnly mml.mmlclib.grammar.FnTests"`
- Run all tests: `sbt test`
- Format code: `sbt scalafmtAll`
- Check formatting: `sbt scalafmtCheckAll`
- Run scalafix: `sbt "scalafixAll --check"`

## Code Style Guidelines
- Scala 3 syntax with significant indentation (no braces)
- Max line length: 100 characters
- Imports: Use OrganizeImports with grouped imports; avoid unused imports
- Types: Explicit return types for public members
- Avoid `using` clause except for type-related functionality, no implicit parameters
- Functional programming style with cats/cats-effect where appropriate
- Error handling: Prefer functional error handling with Either and IO
- Avoid deeply nested conditionals; refactor complex logic into separate functions
- Keep code simple and understandable for less experienced developers
- Prefer val over var for immutability
- Follow existing naming conventions in the codebase

## Tools

* To analyze code, always prefer the metals mcp interface.


**Note**: When triggered by **update memory bank**, I MUST review every memory bank file, even if some don't require updates. Focus particularly on activeContext.md  as it tracks current state.



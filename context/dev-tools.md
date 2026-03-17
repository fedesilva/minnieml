# Scala Tools

How to build, test, and run the MML compiler during development.

## Build tool

The project uses sbt with a thin client (`sbtn`). Always use `sbtn`.

The root project `mml` aggregates `mmlclib` (compiler library) and `mmlc` (CLI).
Always run tasks from the root — aggregation handles subprojects automatically.

## Running the compiler during development

Use `sbtn` to run the in-development compiler directly, without publishing.

```
sbtn "run run <file>.mml"          # compile and run
sbtn "run <file>.mml"              # compile only
sbtn "run ir <file>.mml"           # emit LLVM IR
sbtn "run run -s <file>.mml"       # compile and run with ASan
```

## Tests

```
sbtn test                          # full test suite (all modules)
sbtn "testOnly <fully.qualified.TestClass>"   # single test class
```

### Filtering tests within a suite

Use munit's built-in filtering via `testOnly` arguments:

```
sbtn "testOnly <TestClass> -- *substring*"           # glob match on test name
sbtn "testOnly <TestClass> -- --tests=exact-name"    # exact test name
sbtn "testOnly <TestClass> -- \"--tests=name with spaces\""
```

In-code filtering (for development only, do not commit):
- `test("name".only) { ... }` — run only this test
- `test("name".ignore) { ... }` — skip this test

Tag-based filtering:
```
sbtn "testOnly <TestClass> -- --include-tags=TagName"
sbtn "testOnly <TestClass> -- --exclude-tags=TagName"
```

## Formatting and linting

Run before finishing any code change:
```
sbtn scalafmtAll
sbtn scalafixAll
```

Fix all compiler warnings and exhaustivity errors.

## Publishing

Install the fat jar to `~/bin` for standalone use:
```
sbtn mmlcPublishLocal
```

Never publish without first verifying the compiler works via `sbtn "run run ..."`.

After publishing, `mmlc` is available system-wide:
```
mmlc run <file>.mml                # compile and run
mmlc <file>.mml                    # compile only
mmlc ir <file>.mml                 # emit LLVM IR
mmlc -I <file>.mml                 # optimized IR
mmlc -s <file>.mml                 # ASan instrumented
mmlc -h                            # Compiler arguments help
```

## Distribution

```
sbtn mmlcDistroAssembly            # create distribution directory
sbtn mmlcDistro                    # create distribution zip
```

## Memory tests

Run when changes touch memory management or ownership:
```
./tests/mem/run.sh all
```

All tests must pass both ASan and leaks checks.

## Benchmarks

Run after publishing:
```
make -C benchmark clean
make -C benchmark mml
```


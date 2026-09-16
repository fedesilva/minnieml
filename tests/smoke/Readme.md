# Compiler smoke tests

Run from the repository root before publishing the compiler:

```sh
./tests/smoke/run.sh all
```

The harness uses the in-development compiler through sequential `sbtn` invocations.
Outputs and individual logs live under `build/test-smoke/`. A failed compilation or
execution makes the harness fail; it still checks the remaining programs.

Every `.mml` file in this directory is compiled and run except `style-guide.mml`,
`lambda-factorial.mml`, and `raytracer3_p6.mml`, which are compile-only checks.
`nested-tco.mml` checks three nested capturing loops and exits unsuccessfully if its
checksum differs from `6048`.

These programs are copies of the smoke samples in `mml/samples/`. When intentionally
changing a smoke example, update its copy here too. Add new smoke programs as `.mml`
files; only compile-only cases need an entry in `run.sh`.

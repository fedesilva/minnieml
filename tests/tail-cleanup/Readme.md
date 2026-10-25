# Owned-closure tail recursion

`OwnedClosureTailRecursionTests` assembles emitted LLVM and executes its fixtures with a
small C runtime. Payload counters check exactly-once destruction, zero implicit clones,
one million iterations, one million correct String literal uses, and at most one live String
payload. Native stack-address samples bound the stress loop's stack span. The O0 and O3
cases run with ASan/LSan and separately
without sanitizers under a 256 KiB stack limit.

Pointer aliases created before a payload moves into a closure must retain the backing storage
through their native consumer. O0/O3 regressions cover local, returned, and nested closures,
aggregate fields, and borrowed reader closures. An unrelated literal pointer checks that
independent storage still allows early cleanup.

Sanitized fixtures mark generated LLVM functions with `sanitize_address` before optimization
and linking. Negative controls at both optimization levels require ASan to detect an invalid
generated load. Compiler-driver sanitizer flags alone do not establish that LLVM input
functions are instrumented; C runtime instrumentation and allocator checks are separate.

```sh
sbtn "testOnly *OwnedClosureTailRecursionTests"
```

The production-runtime stress case is [owned-closure-tail.mml](../mem/owned-closure-tail.mml).
It checks the total lengths of one million captured strings and runs in the complete memory
harness after compiler publication:

```sh
sbtn "run run -O0 tests/mem/owned-closure-tail.mml"
./tests/mem/run.sh all
```

The infinite probe uses the test fixture
[closure-heap-capture-fail.mml](closure-heap-capture-fail.mml), including its `if true`.
Build each optimization level using the development compiler, keeping
`sbtn` sessions sequential:

```sh
sbtn "run -O0 -b build/tail-cleanup-o0 -o build/tail-cleanup-o0/probe tests/tail-cleanup/closure-heap-capture-fail.mml"
python3 tests/tail-cleanup/infinite_probe.py build/tail-cleanup-o0/probe
sbtn "run -O3 -b build/tail-cleanup-o3 -o build/tail-cleanup-o3/probe tests/tail-cleanup/closure-heap-capture-fail.mml"
python3 tests/tail-cleanup/infinite_probe.py build/tail-cleanup-o3/probe
```

The probe requires macOS `vmmap`. It drains stdout and stderr while sampling the live
process, uses a 256 KiB stack limit, waits two seconds for warmup, and takes eight
measurements one second apart. Each interval must make progress, with at least 100,000
additional output lines after warmup. It checks resident and dirty stack variation within
16 KiB, heap allocation-count variation within 64, allocated-heap variation within
128 KiB, and dirty stack usage below 128 KiB. These are sustained measurements, not a
proof for every possible execution duration. The finite counter and sanitizer checks
complement them.

The harness explicitly sends SIGTERM and waits for termination. An early exit, missing
measurement, stalled output, exceeded bound, stderr diagnostic, or forced kill fails the
probe. Stderr is checked after termination and pipe-reader completion, including short
messages buffered until EOF. A timeout alone never counts as success. Each JSON line
contains measurements suitable for embedding in the task's versioned evidence.

The [stderr control](stderr_probe.c) writes one short diagnostic before producing continuous
stdout. Compile it and run the same harness; this command must fail with
`diagnostic-before-loop` and must not report `"result": "pass"`:

```sh
clang -O0 tests/tail-cleanup/stderr_probe.c -o build/tail-cleanup-stderr
python3 tests/tail-cleanup/infinite_probe.py build/tail-cleanup-stderr \
  --warmup 0.2 --samples 3 --interval 0.2
```

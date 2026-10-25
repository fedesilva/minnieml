# Benchmarks

Run `make bench-fizzbuzz` separately to compare ordinary and explicitly buffered
FizzBuzz output in C, Go, Rust, and MML. Buffered entries are named
`fizzbuzz-buffered-<language>`. Every program prints ten million lines.
The MML buffered version uses 40 KiB; C, Go, and Rust use 4 KiB. This target is
excluded from `bench` and `bench-mml`.
Use `make bench-fizzbuzz-time` on macOS for `/usr/bin/time -l` resource statistics
for all eight variants. Program output goes to `/dev/null`; timing statistics remain
visible on stderr. This target runs separately from `bench-time`.

Build the 37 MML computation benchmarks, including optimization and TCO variants, with `make mml`.
Run the MML-only timing collection with `make bench-mml`. The compiler must already be
published as `mmlc`; clean and rebuild before measuring a changed compiler.

The MML sieve programs accept one optional integer limit from 2 to 10,000,000:

```sh
bin/sieve-mml 10000000
bin/sieve-safe-mml 10000000
bin/sieve-mml-O0-no-tco 100000
```

Without an argument they retain the 10,000,000 default. Both `bench-mml` and the local
`bench-self-sieve` / `bench-self-sieve-time` targets use 100,000 only for builds with
self-tail-call optimization disabled (`--no-tco`). All other sieve builds use 10,000,000,
including the checked-array version and the cross-language `bench-sieve` collection.
At O0 with TCO disabled, ten million requires millions of recursive calls and exhausts
the standard stack. Local Hyperfine measurements group TCO and no-TCO builds separately;
their timings use different workloads and must not be compared as speedup ratios.

In `bench-mml`, each binary receives two warmups and ten measured runs, with a 300-second limit per
invocation. Results include Hyperfine JSON/CSV, captured output, logs, and status JSON
with command arguments. The CI summary displays arguments beside the binary name.
Compilation duration is separate from execution timing. Measurements include process
startup, timeout-wrapper overhead, and output capture; these can dominate short runs.
Successful execution does not independently establish output correctness.

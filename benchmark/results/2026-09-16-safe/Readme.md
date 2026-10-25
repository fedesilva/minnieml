# Checked matrix multiplication — 2026-09-16

The checked programs copy `mat-mul-nested.mml` and `mat-mul-opt-nested.mml`,
replacing every `unsafe_ar_int_get/set` with `ar_int_get/set`. Loop structure,
500 × 500 size, seeds (42 and 1337), initialization, and trace calculation match.
All four nested MML variants and all four Go variants returned trace checksum 381460.

Environment: macOS 26.6.2, arm64; LLVM 23.1.1 reports host/target CPU `apple-m5`;
Go 1.27.1; Hyperfine 1.20.0. MML uses `-O3`; both safe and unsafe `ikj` builds
also use `--llvm-opt-arg=-force-vector-interleave=4`. Go uses ordinary `go build`.
The installed compiler JAR SHA-256 is
`e4d7762d49f8bc1e38003e29ba7b1bb2800fe15eb57d4c00ce6c18af5fdcd00f`.

The full matrix suite used 20 warmups and 50 measured process runs per entry.
The command below makes the run's default optimization level explicit:

```sh
make -C benchmark bench-matmul MML_OPT=3 LOG_BENCH_RESULTS=1 RESULTSDIR=results/2026-09-16-safe
```

The measured working tree includes the nested-recursion compiler repair and unchecked
nested benchmark entries. Reproducing all entries requires a compiler built with the
[nested-recursion repair](../../../context/tasks/unify-lambdas.md#bug-preserve-valid-llvm-and-tco-for-nested-capturing-recursion).

Timings include process startup, allocation, initialization, multiplication,
checksum, output, and cleanup. They are whole-program measurements, not isolated
matrix-kernel timings. Existing up-to-date comparison binaries were reused;
the new checked binaries and their nested unsafe counterparts were rebuilt.

See [full results](matmul.md), [raw JSON](matmul.json), and [CSV](matmul.csv).
A focused repeat used the same warmup/run counts with the eight MML/Go entries
in reverse order; see [confirmation results](confirmation.md) and
[raw confirmation JSON](confirmation.json). Command order is recorded in each JSON.
Some entries had statistical outliers; the repeat supports the same conclusions.

| Loop order | Unsafe nested MML | Checked MML | Checked overhead | Go | Go BCE variant |
|---|---:|---:|---:|---:|---:|
| ijk | 25.46 ms | 45.69 ms | +79.5% | 47.52 ms | 53.74 ms |
| ikj | 17.81 ms | 22.01 ms | +23.6% | 47.53 ms | 36.42 ms |

These are means from the full suite. Checked `ijk` takes about 79% longer than
unsafe nested MML across both runs, and runs close to ordinary Go (about 3–4%
less elapsed time). Checked `ikj` takes about 22–24% longer than unsafe nested
MML, while Go takes 2.12–2.16 times as long and Go's row-slice BCE variant takes
1.63–1.65 times as long. Checked `ikj` also beats ordinary Go's `ijk` implementation
by about 2.1 times.

The `ijk` Go file named BCE uses upfront buffer checks; its source notes that
inner-loop checks remain with this Go version. The `ikj` BCE file uses equal-length
row slices. These measurements do not isolate bounds-check instruction cost from
optimizer effects caused by adding the checks.

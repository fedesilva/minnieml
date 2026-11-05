# B23 parser-work reproduction

## Scope and environment

- Date: 2026-11-04.
- Compiler source: `b7668e23a366f21cd63511cff6d7157eef1c8c4f`; no compiler changes.
- OpenJDK 25.0.4.1, macOS 26.6.2, aarch64, 10 available processors, 8 GiB maximum heap.
- Fork JVM arguments: `-XX:ThreadPriorityPolicy=1 -XX:+UnlockExperimentalVMOptions
  -XX:+EnableJVMCIProduct -XX:+EnableJVMCI -XX:-UnlockExperimentalVMOptions`.
- Existing [ParserBaseline](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/ParserBaseline.scala)
  measures `Parser.parseModuleWithInfo` batches, including source indexing. Process startup,
  semantic analysis, code generation, and instrumentation are outside the timed batches.
- Two fresh JVMs measure arithmetic inputs, each with 10 warmup batches and seven sample
  batches of three parses. Larger inputs run first to reduce early compilation effects.
- A separate fresh JVM checks member, tuple, and selected-group controls with three warmup
  batches and five single-parse samples. Only work counters are used from these controls.
- Every input returns a module with zero recovered parser errors. Instrumented and ordinary
  ASTs agree, and repeated parses pass the utility's result/checksum checks.
- The measurement command exits 0. The shared `mmlclib / Test / fork` setting is restored to
  `false`. The JDK reports a deprecated `sun.misc.Unsafe` call from the Scala runtime.

These are parser measurements, not execution tests or a compiler-wide verification pass.
Tuple and selected-group controls establish grammar behavior without asserting that their values
are well-typed. Rule counters include failed alternatives. The collector's `backtracks` counter
counts callbacks behind its furthest cursor, not distinct rewind operations.

## Arithmetic results

The nested family has N integer terms and N−2 parenthesis pairs. A six-term body is
`1 + (2 + (3 + (4 + (5 + 6))))`. The flat control has 16 terms and no parentheses.
Times are medians of per-batch average parse times; counters agree across both forks.

| Input | Bytes | Rule callbacks | Group-rule attempts | Selection-rule attempts | Fork 1 ms/parse | Fork 2 ms/parse |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| nested-6 | 54 | 8,156 | 108 | 93 | 0.12121 | 0.13517 |
| nested-8 | 66 | 32,588 | 444 | 381 | 0.46861 | 0.27861 |
| nested-10 | 79 | 130,316 | 1,788 | 1,533 | 1.29006 | 0.84262 |
| nested-12 | 93 | 521,228 | 7,164 | 6,141 | 3.00664 | 2.93446 |
| nested-14 | 107 | 2,084,876 | 28,668 | 24,573 | 11.97444 | 11.50847 |
| nested-16 | 121 | 8,339,468 | 114,684 | 98,301 | 49.09065 | 49.50121 |
| flat-16 | 91 | 2,705 | 31 | 31 | 0.08836 | 0.20722 |

Adding two terms multiplies rule work by approximately four. At 16 terms, 121 source
characters trigger 8,339,468 callbacks. The last two size steps also multiply warmed parse
time by approximately four. Small-input times remain sensitive to JIT and scheduling; no
absolute latency guarantee follows from these two forks.

## Grammar controls

`member-N` places the same arithmetic expression in a module-level `let`, exercising the
member-body grammar. `tuple-D` nests D tuples through their first element, starting from `1`
and repeatedly forming `(previous, 0)`. `selected-D` starts from `base` and repeatedly forms
`(previous).field`.

| Input | Bytes | Rule callbacks | Member group-rule attempts | Selection-rule attempts | Member tuple-rule attempts |
| --- | ---: | ---: | ---: | ---: | ---: |
| member-8 | 56 | 32,261 | 444 | 381 | 318 |
| member-12 | 83 | 517,061 | 7,164 | 6,141 | 5,118 |
| member-16 | 111 | 8,273,861 | 114,684 | 98,301 | 81,918 |
| tuple-2 | 26 | 3,165 | 36 | 31 | 36 |
| tuple-4 | 36 | 49,165 | 596 | 511 | 596 |
| tuple-6 | 46 | 785,165 | 9,556 | 8,191 | 9,556 |
| selected-8 | 83 | 1,045 | 9 | 9 | 1 |
| selected-16 | 147 | 1,765 | 17 | 17 | 1 |

Member arithmetic has the same exponential shape. Two extra tuple levels multiply callbacks
by about sixteen. Selected groups use 1,045 callbacks at depth eight and 1,765 at depth sixteen,
consistent with linear work in these controls. These measurements support factoring both
selection suffixes and parentheses.

## Baseline source explanation

In [expressions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala),
`termP` and `termMemberP` try a selection before their plain group alternative.
`selectionBaseP` / `selectionBaseMemberP` parse the whole group, then `selectionP` requires
at least one `.field`. Without a field, `NoCut` allows that attempt to rewind and the plain
group alternative parses the same contents again. Every nested ordinary group repeats this work.

The group and tuple alternatives also parse their first expression separately. A tuple first
fails the group's closing-parenthesis expectation at its comma. The selection path and ordinary
term path each repeat the group-versus-tuple attempt. The tuple measurements show why making
selection optional alone is an incomplete repair.

## Reproduction

Run from a checkout containing the compiler source revision above. These commands recreate
every workload; no retained build output is required. Keep sizes bounded at 16 arithmetic
terms and six tuple levels while measuring the unfixed parser.

```sh
python3 - <<'PY'
from pathlib import Path
root = Path('build/b23-parser-baseline')
root.mkdir(parents=True, exist_ok=True)
for terms in (6, 8, 10, 12, 14, 16):
    expression = f'{terms - 1} + {terms}'
    for value in range(terms - 2, 0, -1):
        expression = f'{value} + ({expression})'
    (root / f'nested-{terms}.mml').write_text(f'fn nested(): Int =\n  {expression};\n;\n')
    if terms in (8, 12, 16):
        (root / f'member-{terms}.mml').write_text(f'let nested = {expression};\n')
(root / 'flat-16.mml').write_text(
    'fn flat(): Int =\n  ' + ' + '.join(map(str, range(1, 17))) + ';\n;\n')
for depth in (2, 4, 6):
    expression = '1'
    for _ in range(depth):
        expression = f'({expression}, 0)'
    (root / f'tuple-{depth}.mml').write_text(f'let nested = {expression};\n')
for depth in (8, 16):
    expression = 'base'
    for _ in range(depth):
        expression = f'({expression}).field'
    (root / f'selected-{depth}.mml').write_text(f'let nested = {expression};\n')
PY

b23_timing='mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline . 10 7 3 build/b23-parser-baseline/nested-16.mml build/b23-parser-baseline/nested-14.mml build/b23-parser-baseline/nested-12.mml build/b23-parser-baseline/nested-10.mml build/b23-parser-baseline/nested-8.mml build/b23-parser-baseline/nested-6.mml build/b23-parser-baseline/flat-16.mml'
b23_controls='mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline . 3 5 1 build/b23-parser-baseline/member-8.mml build/b23-parser-baseline/member-12.mml build/b23-parser-baseline/member-16.mml build/b23-parser-baseline/tuple-2.mml build/b23-parser-baseline/tuple-4.mml build/b23-parser-baseline/tuple-6.mml build/b23-parser-baseline/selected-8.mml build/b23-parser-baseline/selected-16.mml'
sbtn "set mmlclib / Test / fork := true; $b23_timing; $b23_timing; $b23_controls; set mmlclib / Test / fork := false"
```

If a run fails before the last command, restore the shared setting with
`sbtn 'set mmlclib / Test / fork := false'` before other compiler work.

## Input identities

SHA-256 values identify the exact UTF-8 source bytes generated by the reproduction above.

| Input | SHA-256 |
| --- | --- |
| nested-16 | `3c5a06435a7515d6767eebec39f78e211bcde034732b02665c505762022ef8d7` |
| nested-14 | `fd8286551256d63350a79a08db81231355a5f3fc9e2f2e2b23d1324e70c68912` |
| nested-12 | `6d604204e39d52ba264515dee86e4a5a9f378de467b6d10b3b61911d51e6bb40` |
| nested-10 | `caf9db2b0958881ff713ed6d653c87d4dea2caf6fc623147743a3acf869dcf8e` |
| nested-8 | `bc207e79f1a6b828a96c5c0034eae1d9ef15bfac0f2e8a74ed86c44991c277b8` |
| nested-6 | `1293fc51c9a874b6af0fd25922fcdbf882117bf4785222f3e8a319a8964465a3` |
| flat-16 | `5107cabe292f900ab225cdd129af8dd8cb6a59b205d6f62a3b9b9fb5c0966ada` |
| member-8 | `99ae0874edb714ed3a64184407032dbb066974ecd1718b8dc51239a7c90e0f51` |
| member-12 | `e5d8930bb615f331efd8545a2cf743de59b0cf641ec37b17724c41fce976cfbc` |
| member-16 | `c45236b76672c3257d5390bc4487fd827952447dd9870e40c7d5f5494da21432` |
| tuple-2 | `b0cd0d7ade35df0bab4ec5cced764c533e814134fa69219d99295e8136e654a8` |
| tuple-4 | `7c528a5a96131e2e48544ac2a87508212cb7a4ae19c2d583bf1f5666efdf8ee3` |
| tuple-6 | `272313bbbeaec29bca09ebe623fc0e73f7886e3339b442f9853b02231d6d2252` |
| selected-8 | `a5dc383227a9d625418d5135693f17cad5949f8b8fe7b2336164abb3384f59d2` |
| selected-16 | `87602386171cc6e512d9f560d9aaf41eb10bb057b4974842510c385cc1121f01` |

## Retained timing samples

Each value is a complete three-parse batch in nanoseconds. Divide by three for its average
parse time. Seven samples are retained per input and fork.

| Input | Fork | Batch nanoseconds |
| --- | ---: | --- |
| nested-16 | 1 | 258499250, 170213250, 166858833, 141352417, 147271958, 140977708, 141390125 |
| nested-14 | 1 | 40538167, 35494875, 35184458, 36174958, 36365709, 35923334, 35126084 |
| nested-12 | 1 | 9019917, 9178542, 9133833, 9010500, 9289916, 8888042, 9011417 |
| nested-10 | 1 | 3778458, 4588167, 2619500, 2497875, 3870167, 5740208, 4569625 |
| nested-8 | 1 | 841209, 941458, 944084, 1405834, 2006500, 2027000, 1716959 |
| nested-6 | 1 | 848166, 398667, 363625, 346375, 344875, 347875, 374083 |
| flat-16 | 1 | 268208, 260333, 269542, 265417, 260375, 260292, 265084 |
| nested-16 | 2 | 251629625, 174713291, 149799791, 147209750, 146329333, 148503625, 144410042 |
| nested-14 | 2 | 34896083, 34138916, 34588458, 34654125, 34097000, 34349042, 34525417 |
| nested-12 | 2 | 8803375, 8822125, 8981834, 8907625, 8800125, 8671542, 8655167 |
| nested-10 | 2 | 2511917, 2649542, 2611375, 2466417, 2527875, 2656333, 2421417 |
| nested-8 | 2 | 887459, 890167, 804125, 835834, 854334, 783708, 777750 |
| nested-6 | 2 | 405541, 400709, 405500, 388542, 442041, 421292, 390875 |
| flat-16 | 2 | 840416, 705542, 621667, 1072416, 410458, 301250, 295792 |

## Repair measurements

The repair shares parenthesized contents and attaches an optional field suffix without
reparsing the base. Plain references retain their original alternative ordering and annotation
ownership. The commands and 15 input bytes above are unchanged. Two fresh timing forks use the
same 10 warmup / seven sample / three parses per batch settings. A separate control fork uses
the same three warmup / five single-parse sample settings.

All 15 source hashes, raw AST hashes, parser outcomes, and recovered-error counts match the
baseline. The command exits 0 and restores `mmlclib / Test / fork` to false. Exact rule totals
agree between the two repaired arithmetic forks.

| Input | Baseline callbacks | Repaired callbacks | Repaired fork 1 ms/parse | Repaired fork 2 ms/parse |
| --- | ---: | ---: | ---: | ---: |
| nested-6 | 8,156 | 1,352 | 0.21681 | 0.18226 |
| nested-8 | 32,588 | 1,772 | 0.56442 | 0.50106 |
| nested-10 | 130,316 | 2,192 | 0.77312 | 0.66285 |
| nested-12 | 521,228 | 2,612 | 0.44714 | 0.38864 |
| nested-14 | 2,084,876 | 3,032 | 0.87785 | 0.68690 |
| nested-16 | 8,339,468 | 3,452 | 0.91607 | 0.83136 |
| flat-16 | 2,705 | 2,612 | 0.21046 | 0.30224 |

Repaired arithmetic grows by 420 callbacks for each two added terms in this family. The
16-term case uses about 2,416 times fewer callbacks. The measured latency falls from roughly
49 ms to 0.83–0.92 ms under the same sampling protocol. The small repaired workload receives
less JIT work during warmup, and its noisy timings are supporting observations; callback growth
is the structural regression evidence.

| Control | Baseline callbacks | Repaired callbacks |
| --- | ---: | ---: |
| member-8 | 32,261 | 1,685 |
| member-12 | 517,061 | 2,517 |
| member-16 | 8,273,861 | 3,349 |
| tuple-2 | 3,165 | 585 |
| tuple-4 | 49,165 | 883 |
| tuple-6 | 785,165 | 1,181 |
| selected-8 | 1,045 | 1,019 |
| selected-16 | 1,765 | 1,715 |

## Compatibility corpus

An additional 68 literal cases retain exact raw AST hashes and recovered diagnostic counts
across the repair. For each expression below, the two complete source forms are
`let value = EXPR;\nlet after = 77;\n` and
`fn value() = EXPR;;\nlet after = 77;\n`. The comparison uses `ParserBaseline . 1 1 1`
with the same source paths and module names on both versions; instrumentation and ordinary
parsing also agree within each run. These cases check parser output, not type correctness.

```python
expressions = [
    '()', '( )', '(1)', '(1, 2)', '(1,)', '(,1)', '(1,,2)', '(1, 2',
    '(1', '(item).', '(item).field', '(item).field.', '(item).field: Int',
    'item: Int', 'item: Int.field', 'item.field: Int', 'Type.field', '1.5', '.5',
    '({ x -> x })', '({ x -> x }, { y -> y })', '({ x -> x }).field',
    '(if true then @bad;else 22;,33)', '(if true then @bad;else 22;)',
    '({ x -> if true then ;else x; },33)', '(1; 2)', '(1; 2,3)',
    '((item): Int)', '(item): Int.field', '(item).field: Int: String',
    'item: Int: String', '(1,,)', '((1,2),3).field', '((1),2)',
]
```

The compatibility corpus exposed an extra-annotation acceptance regression in an intermediate
implementation. Preserving bare reference alternative ordering restores the original single
parse error for `item: Int: String` in both source forms. Dedicated regressions cover that
contract; the final 68-case comparison has no differences.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-c` | 380.3 ± 21.7 | 364.9 | 476.4 | 6.05 ± 0.45 |
| `bin/matmul-restricted-c` | 62.9 ± 3.1 | 58.8 | 74.6 | 1.00 |
| `bin/matmul-mml` | 150.5 ± 0.7 | 149.7 | 153.2 | 2.39 ± 0.12 |
| `bin/matmul-opt-mml` | 104.1 ± 3.8 | 100.1 | 115.1 | 1.66 ± 0.10 |
| `bin/matmul-go` | 254.0 ± 1.5 | 250.2 | 256.6 | 4.04 ± 0.20 |
| `bin/matmul-hoisted-go` | 344.4 ± 2.0 | 340.8 | 350.2 | 5.48 ± 0.27 |

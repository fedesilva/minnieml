| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-c` | 368.8 ± 1.3 | 367.5 | 376.4 | 5.81 ± 0.13 |
| `bin/matmul-restricted-c` | 63.4 ± 1.4 | 60.7 | 66.7 | 1.00 |
| `bin/matmul-mml` | 151.7 ± 0.6 | 150.6 | 153.5 | 2.39 ± 0.05 |
| `bin/matmul-opt-mml` | 103.8 ± 3.6 | 100.6 | 115.7 | 1.64 ± 0.07 |
| `bin/matmul-go` | 254.4 ± 1.6 | 251.2 | 258.8 | 4.01 ± 0.09 |
| `bin/matmul-hoisted-go` | 344.9 ± 2.0 | 340.7 | 351.8 | 5.44 ± 0.12 |

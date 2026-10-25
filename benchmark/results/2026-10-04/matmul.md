| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-c` | 27.4 ± 0.3 | 27.1 | 28.6 | 1.51 ± 0.03 |
| `bin/matmul-opt-c` | 19.1 ± 0.4 | 18.2 | 20.5 | 1.05 ± 0.03 |
| `bin/matmul-restricted-c` | 27.5 ± 0.6 | 27.1 | 31.0 | 1.51 ± 0.04 |
| `bin/matmul-mml` | 25.9 ± 0.1 | 25.7 | 26.1 | 1.42 ± 0.02 |
| `bin/matmul-nested-mml` | 25.9 ± 0.2 | 25.7 | 26.6 | 1.42 ± 0.02 |
| `bin/matmul-safe-mml` | 47.1 ± 0.9 | 45.9 | 49.0 | 2.59 ± 0.07 |
| `bin/matmul-opt-mml` | 18.3 ± 0.5 | 17.7 | 19.2 | 1.00 ± 0.03 |
| `bin/matmul-opt-nested-mml` | 18.9 ± 0.7 | 17.8 | 20.9 | 1.04 ± 0.04 |
| `bin/matmul-opt-safe-mml` | 27.7 ± 0.2 | 27.4 | 28.5 | 1.52 ± 0.03 |
| `bin/matmul-rs` | 26.0 ± 0.1 | 25.9 | 26.2 | 1.43 ± 0.02 |
| `bin/matmul-opt-rs` | 18.2 ± 0.3 | 18.0 | 18.9 | 1.00 |
| `bin/matmul-go` | 48.0 ± 0.2 | 47.7 | 48.8 | 2.64 ± 0.04 |
| `bin/matmul-bce-go` | 53.5 ± 0.3 | 52.9 | 54.4 | 2.94 ± 0.05 |
| `bin/matmul-opt-go` | 47.8 ± 0.4 | 47.4 | 49.2 | 2.63 ± 0.05 |
| `bin/matmul-opt-bce-go` | 36.8 ± 0.1 | 36.5 | 37.1 | 2.02 ± 0.03 |
| `bin/matmul-java-native` | 71.7 ± 0.2 | 71.4 | 72.9 | 3.94 ± 0.06 |

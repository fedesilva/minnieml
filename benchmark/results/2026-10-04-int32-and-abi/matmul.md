| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-c` | 25.9 ± 0.8 | 25.2 | 28.7 | 3.00 ± 0.10 |
| `bin/matmul-opt-c` | 8.7 ± 0.1 | 8.5 | 9.1 | 1.00 ± 0.02 |
| `bin/matmul-restricted-c` | 26.2 ± 0.9 | 25.2 | 30.2 | 3.03 ± 0.11 |
| `bin/matmul-mml` | 23.8 ± 0.3 | 23.5 | 24.7 | 2.75 ± 0.04 |
| `bin/matmul-nested-mml` | 23.7 ± 0.1 | 23.5 | 24.1 | 2.74 ± 0.02 |
| `bin/matmul-safe-mml` | 45.1 ± 1.4 | 44.0 | 52.4 | 5.21 ± 0.16 |
| `bin/matmul-opt-mml` | 8.7 ± 0.1 | 8.5 | 9.0 | 1.00 ± 0.02 |
| `bin/matmul-opt-nested-mml` | 8.7 ± 0.1 | 8.6 | 8.8 | 1.00 |
| `bin/matmul-opt-safe-mml` | 9.0 ± 0.2 | 8.7 | 9.3 | 1.04 ± 0.02 |
| `bin/matmul-rs` | 23.8 ± 0.2 | 23.6 | 24.7 | 2.75 ± 0.03 |
| `bin/matmul-opt-rs` | 8.8 ± 0.1 | 8.7 | 9.0 | 1.02 ± 0.01 |
| `bin/matmul-go` | 52.8 ± 0.8 | 52.3 | 57.4 | 6.10 ± 0.10 |
| `bin/matmul-bce-go` | 53.9 ± 0.6 | 52.9 | 55.8 | 6.23 ± 0.08 |
| `bin/matmul-opt-go` | 48.3 ± 0.3 | 47.9 | 49.3 | 5.58 ± 0.05 |
| `bin/matmul-opt-bce-go` | 37.5 ± 0.5 | 37.0 | 38.8 | 4.33 ± 0.06 |
| `bin/matmul-java-native` | 70.8 ± 0.6 | 70.3 | 73.3 | 8.18 ± 0.09 |

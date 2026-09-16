| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-c` | 26.6 ± 0.3 | 26.1 | 28.1 | 1.50 ± 0.03 |
| `bin/matmul-opt-c` | 18.0 ± 1.7 | 17.3 | 26.6 | 1.01 ± 0.09 |
| `bin/matmul-restricted-c` | 26.7 ± 0.2 | 26.3 | 27.7 | 1.50 ± 0.02 |
| `bin/matmul-mml` | 25.4 ± 0.3 | 25.0 | 26.4 | 1.43 ± 0.02 |
| `bin/matmul-nested-mml` | 25.5 ± 0.3 | 25.1 | 26.6 | 1.43 ± 0.03 |
| `bin/matmul-safe-mml` | 45.7 ± 1.9 | 44.5 | 58.4 | 2.57 ± 0.12 |
| `bin/matmul-opt-mml` | 17.8 ± 0.2 | 17.6 | 18.9 | 1.00 |
| `bin/matmul-opt-nested-mml` | 17.8 ± 0.2 | 17.5 | 18.5 | 1.00 ± 0.02 |
| `bin/matmul-opt-safe-mml` | 22.0 ± 0.2 | 21.7 | 22.8 | 1.24 ± 0.02 |
| `bin/matmul-rs` | 25.8 ± 0.1 | 25.5 | 26.1 | 1.45 ± 0.02 |
| `bin/matmul-opt-rs` | 18.1 ± 0.2 | 17.8 | 18.8 | 1.02 ± 0.02 |
| `bin/matmul-go` | 47.5 ± 0.3 | 47.1 | 48.8 | 2.68 ± 0.04 |
| `bin/matmul-bce-go` | 53.7 ± 2.7 | 52.2 | 68.4 | 3.03 ± 0.16 |
| `bin/matmul-opt-go` | 47.5 ± 0.4 | 46.9 | 48.8 | 2.68 ± 0.04 |
| `bin/matmul-opt-bce-go` | 36.4 ± 0.2 | 36.0 | 37.5 | 2.05 ± 0.03 |
| `bin/matmul-java-native` | 71.6 ± 0.3 | 71.0 | 72.9 | 4.03 ± 0.06 |

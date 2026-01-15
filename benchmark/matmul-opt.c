#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

void fill_matrix(int64_t* arr, int64_t n, int64_t seed) {
    int64_t size = n * n;
    int64_t current_seed = seed;
    for (int64_t i = 0; i < size; i++) {
        current_seed = (current_seed * 1664525) + 1013904223;
        arr[i] = current_seed % 100;
    }
}

// OPTIMIZATION: Manual Loop Interchange (i-k-j)
// NO restrict keyword
void mat_mul(int64_t* A, int64_t* B, int64_t* C, int64_t n) {
    // 1. Initialize C to zero (required for += logic)
    for (int64_t i = 0; i < n * n; i++) {
        C[i] = 0;
    }

    // 2. Loop i (Rows of A)
    for (int64_t i = 0; i < n; i++) {
        // 3. Loop k (Columns of A / Rows of B) -- SWAPPED!
        for (int64_t k = 0; k < n; k++) {
            // We load A[i][k] ONCE and keep it in a register
            int64_t valA = A[(i * n) + k];
            
            // 4. Loop j (Columns of B) -- SWAPPED!
            // Now we access B sequentially: B[k][0], B[k][1], B[k][2]...
            // This is purely sequential memory access -> Huge Cache Win + Vectorization
            for (int64_t j = 0; j < n; j++) {
                C[(i * n) + j] += valA * B[(k * n) + j];
            }
        }
    }
}

int64_t trace(int64_t* arr, int64_t n) {
    int64_t acc = 0;
    for (int64_t i = 0; i < n; i++) {
        acc += arr[(i * n) + i];
    }
    return acc;
}

int main() {
    int64_t n = 500;
    int64_t* A = (int64_t*)malloc(n * n * sizeof(int64_t));
    int64_t* B = (int64_t*)malloc(n * n * sizeof(int64_t));
    int64_t* C = (int64_t*)malloc(n * n * sizeof(int64_t));

    fill_matrix(A, n, 42);
    fill_matrix(B, n, 1337);

    mat_mul(A, B, C, n);

    int64_t result = trace(C, n);
    printf("Trace Checksum: %lld\n", result);

    free(A);
    free(B);
    free(C);
    return 0;
}

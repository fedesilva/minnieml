#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <inttypes.h>

void fill_matrix(int32_t* arr, int32_t n, int32_t seed) {
    int32_t size = n * n;
    int32_t current_seed = seed;
    for (int32_t i = 0; i < size; i++) {
        current_seed = (current_seed * 25173 + 13849) % 65536;
        arr[i] = (current_seed % 100) - 50;
    }
}

// OPTIMIZATION: Manual Loop Interchange (i-k-j)
// NO restrict keyword
void mat_mul(int32_t* A, int32_t* B, int32_t* C, int32_t n) {
    // 1. Initialize C to zero (required for += logic)
    for (int32_t i = 0; i < n * n; i++) {
        C[i] = 0;
    }

    // 2. Loop i (Rows of A)
    for (int32_t i = 0; i < n; i++) {
        // 3. Loop k (Columns of A / Rows of B) -- SWAPPED!
        for (int32_t k = 0; k < n; k++) {
            // We load A[i][k] ONCE and keep it in a register
            int32_t valA = A[(i * n) + k];
            
            // 4. Loop j (Columns of B) -- SWAPPED!
            // Now we access B sequentially: B[k][0], B[k][1], B[k][2]...
            // This is purely sequential memory access -> Huge Cache Win + Vectorization
            for (int32_t j = 0; j < n; j++) {
                C[(i * n) + j] += valA * B[(k * n) + j];
            }
        }
    }
}

int32_t trace(int32_t* arr, int32_t n) {
    int32_t acc = 0;
    for (int32_t i = 0; i < n; i++) {
        acc += arr[(i * n) + i];
    }
    return acc;
}

int main() {
    int32_t n = 500;
    int32_t* A = (int32_t*)malloc(n * n * sizeof(int32_t));
    int32_t* B = (int32_t*)malloc(n * n * sizeof(int32_t));
    int32_t* C = (int32_t*)malloc(n * n * sizeof(int32_t));

    fill_matrix(A, n, 42);
    fill_matrix(B, n, 1337);

    mat_mul(A, B, C, n);

    int32_t result = trace(C, n);
    printf("Trace Checksum: %" PRId32 "\n", result);

    free(A);
    free(B);
    free(C);
    return 0;
}

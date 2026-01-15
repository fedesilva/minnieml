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

// Naive O(N^3) multiplication with strided access on B
void mat_mul(int64_t* A, int64_t* B, int64_t* C, int64_t n) {
    for (int64_t i = 0; i < n; i++) {
        for (int64_t j = 0; j < n; j++) {
            int64_t acc = 0;
            for (int64_t k = 0; k < n; k++) {
                // A accesses row i, col k (Sequential)
                int64_t valA = A[(i * n) + k];
                // B accesses row k, col j (Strided/Jumping)
                int64_t valB = B[(k * n) + j];
                acc += valA * valB;
            }
            C[(i * n) + j] = acc;
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
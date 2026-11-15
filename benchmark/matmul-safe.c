#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

typedef struct {
    int32_t length;
    int32_t *data;
} IntArray;

static inline void check_index(IntArray arr, int32_t index) {
    if (!arr.data || index < 0 || index >= arr.length) {
        fprintf(stderr, "IntArray index out of bounds: %" PRId32 " (length: %" PRId32 ")\n",
                index, arr.length);
        fflush(stderr);
        exit(1);
    }
}

static inline int32_t ar_int_get(IntArray arr, int32_t index) {
    check_index(arr, index);
    return arr.data[index];
}

static inline void ar_int_set(IntArray arr, int32_t index, int32_t value) {
    check_index(arr, index);
    arr.data[index] = value;
}

static void fill_matrix(IntArray arr, int32_t n, int32_t seed) {
    int32_t size = n * n;
    int32_t current_seed = seed;
    for (int32_t i = 0; i < size; i++) {
        current_seed = (current_seed * 25173 + 13849) % 65536;
        ar_int_set(arr, i, (current_seed % 100) - 50);
    }
}

// Naive O(N^3) multiplication with strided access on B
static void mat_mul(IntArray A, IntArray B, IntArray C, int32_t n) {
    for (int32_t i = 0; i < n; i++) {
        for (int32_t j = 0; j < n; j++) {
            int32_t acc = 0;
            for (int32_t k = 0; k < n; k++) {
                // A accesses row i, col k (Sequential)
                int32_t valA = ar_int_get(A, (i * n) + k);
                // B accesses row k, col j (Strided/Jumping)
                int32_t valB = ar_int_get(B, (k * n) + j);
                acc += valA * valB;
            }
            ar_int_set(C, (i * n) + j, acc);
        }
    }
}

static int32_t trace(IntArray arr, int32_t n) {
    int32_t acc = 0;
    for (int32_t i = 0; i < n; i++) {
        acc += ar_int_get(arr, (i * n) + i);
    }
    return acc;
}

int main(void) {
    // Values in [-50, 49] keep the 500x500 products and trace within Int32.
    const int32_t n = 500;
    IntArray A = {n * n, malloc((size_t)n * n * sizeof(int32_t))};
    IntArray B = {n * n, malloc((size_t)n * n * sizeof(int32_t))};
    IntArray C = {n * n, malloc((size_t)n * n * sizeof(int32_t))};

    if (!A.data || !B.data || !C.data) {
        free(A.data);
        free(B.data);
        free(C.data);
        fputs("Out of memory\n", stderr);
        return 1;
    }

    fill_matrix(A, n, 42);
    fill_matrix(B, n, 1337);

    mat_mul(A, B, C, n);

    int32_t result = trace(C, n);
    printf("Trace Checksum: %" PRId32 "\n", result);

    free(A.data);
    free(B.data);
    free(C.data);
    return 0;
}

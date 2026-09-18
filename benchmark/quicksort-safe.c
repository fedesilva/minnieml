#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    int64_t length;
    int64_t *data;
} IntArray;

static inline void check_index(IntArray arr, int64_t index) {
    if (!arr.data || index < 0 || index >= arr.length) {
        fprintf(stderr, "IntArray index out of bounds: %" PRId64 " (length: %" PRId64 ")\n",
                index, arr.length);
        fflush(stderr);
        exit(1);
    }
}

static inline int64_t ar_int_get(IntArray arr, int64_t index) {
    check_index(arr, index);
    return arr.data[index];
}

static inline void ar_int_set(IntArray arr, int64_t index, int64_t value) {
    check_index(arr, index);
    arr.data[index] = value;
}

static void swap(IntArray arr, int64_t a, int64_t b) {
    int64_t tmp = ar_int_get(arr, a);
    ar_int_set(arr, a, ar_int_get(arr, b));
    ar_int_set(arr, b, tmp);
}

static int64_t partition(IntArray arr, int64_t low, int64_t high) {
    int64_t pivot = ar_int_get(arr, high);
    int64_t i = low - 1;

    for (int64_t j = low; j < high; j++) {
        if (ar_int_get(arr, j) < pivot) {
            i++;
            swap(arr, i, j);
        }
    }
    swap(arr, i + 1, high);
    return i + 1;
}

static void quicksort(IntArray arr, int64_t low, int64_t high) {
    if (low < high) {
        int64_t p = partition(arr, low, high);
        quicksort(arr, low, p - 1);
        quicksort(arr, p + 1, high);
    }
}

static int64_t run_sort(int64_t size) {
    if (size <= 0 || (uint64_t)size > SIZE_MAX / sizeof(int64_t)) {
        fputs("Invalid array size\n", stderr);
        exit(1);
    }

    IntArray arr = {size, malloc((size_t)size * sizeof(int64_t))};
    if (!arr.data) {
        fputs("Out of memory\n", stderr);
        exit(1);
    }

    // Wrap at 64 bits, then interpret the bits as signed for MML's remainder.
    uint64_t next = 42;
    for (int64_t i = 0; i < size; i++) {
        next = next * UINT64_C(1664525) + UINT64_C(1013904223);
        int64_t signed_next;
        memcpy(&signed_next, &next, sizeof(signed_next));
        ar_int_set(arr, i, signed_next % 100000);
    }

    quicksort(arr, 0, size - 1);

    int64_t result = ar_int_get(arr, size / 2);
    free(arr.data);
    return result;
}

int main(void) {
    int64_t result = run_sort(1000000);
    printf("Median checksum: %" PRId64 "\n", result);
    return 0;
}

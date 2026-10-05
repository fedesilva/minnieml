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

static void swap(IntArray arr, int32_t a, int32_t b) {
    int32_t tmp = ar_int_get(arr, a);
    ar_int_set(arr, a, ar_int_get(arr, b));
    ar_int_set(arr, b, tmp);
}

static int32_t partition(IntArray arr, int32_t low, int32_t high) {
    int32_t pivot = ar_int_get(arr, high);
    int32_t i = low - 1;

    for (int32_t j = low; j < high; j++) {
        if (ar_int_get(arr, j) < pivot) {
            i++;
            swap(arr, i, j);
        }
    }
    swap(arr, i + 1, high);
    return i + 1;
}

static void quicksort(IntArray arr, int32_t low, int32_t high) {
    if (low < high) {
        int32_t p = partition(arr, low, high);
        quicksort(arr, low, p - 1);
        quicksort(arr, p + 1, high);
    }
}

static int32_t run_sort(int32_t size) {
    if (size <= 0 || (size_t)size > SIZE_MAX / sizeof(int32_t)) {
        fputs("Invalid array size\n", stderr);
        exit(1);
    }

    IntArray arr = {size, malloc((size_t)size * sizeof(int32_t))};
    if (!arr.data) {
        fputs("Out of memory\n", stderr);
        exit(1);
    }

    // LCG state stays in [0, 65535]; multiply and add fit Int32.
    int32_t next = 42;
    for (int32_t i = 0; i < size; i++) {
        next = (next * 25173 + 13849) % 65536;
        ar_int_set(arr, i, next);
    }

    quicksort(arr, 0, size - 1);

    int32_t result = ar_int_get(arr, size / 2);
    free(arr.data);
    return result;
}

int main(void) {
    int32_t result = run_sort(1000000);
    printf("Median checksum: %" PRId32 "\n", result);
    return 0;
}

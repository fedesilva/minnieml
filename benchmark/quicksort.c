#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <inttypes.h>

// Simple swap helper
void swap(int32_t* arr, int32_t a, int32_t b) {
    int32_t tmp = arr[a];
    arr[a] = arr[b];
    arr[b] = tmp;
}

// Partition logic
int32_t partition(int32_t* arr, int32_t low, int32_t high) {
    int32_t pivot = arr[high];
    int32_t i = low - 1;

    for (int32_t j = low; j < high; j++) {
        if (arr[j] < pivot) {
            i++;
            swap(arr, i, j);
        }
    }
    swap(arr, i + 1, high);
    return i + 1;
}

// Recursive Quicksort
void quicksort(int32_t* arr, int32_t low, int32_t high) {
    if (low < high) {
        int32_t p = partition(arr, low, high);
        quicksort(arr, low, p - 1);
        quicksort(arr, p + 1, high);
    }
}

int32_t run_sort(int32_t size) {
    int32_t* arr = (int32_t*)malloc(size * sizeof(int32_t));
    
    // Fill random (Same LCG logic as MML)
    int32_t next = 42;
    for (int32_t i = 0; i < size; i++) {
        next = (next * 25173 + 13849) % 65536;
        arr[i] = next;
    }

    quicksort(arr, 0, size - 1);
    
    int32_t result = arr[size / 2];
    free(arr);
    return result;
}

int main() {
    int32_t result = run_sort(1000000);
    printf("Median checksum: %" PRId32 "\n", result);
    return 0;
}
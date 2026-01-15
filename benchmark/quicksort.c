#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

// Simple swap helper
void swap(int64_t* arr, int64_t a, int64_t b) {
    int64_t tmp = arr[a];
    arr[a] = arr[b];
    arr[b] = tmp;
}

// Partition logic
int64_t partition(int64_t* arr, int64_t low, int64_t high) {
    int64_t pivot = arr[high];
    int64_t i = low - 1;

    for (int64_t j = low; j < high; j++) {
        if (arr[j] < pivot) {
            i++;
            swap(arr, i, j);
        }
    }
    swap(arr, i + 1, high);
    return i + 1;
}

// Recursive Quicksort
void quicksort(int64_t* arr, int64_t low, int64_t high) {
    if (low < high) {
        int64_t p = partition(arr, low, high);
        quicksort(arr, low, p - 1);
        quicksort(arr, p + 1, high);
    }
}

int64_t run_sort(int64_t size) {
    int64_t* arr = (int64_t*)malloc(size * sizeof(int64_t));
    
    // Fill random (Same LCG logic as MML)
    int64_t next = 42;
    for (int64_t i = 0; i < size; i++) {
        next = (next * 1664525) + 1013904223;
        arr[i] = next % 100000;
    }

    quicksort(arr, 0, size - 1);
    
    int64_t result = arr[size / 2];
    free(arr);
    return result;
}

int main() {
    int64_t result = run_sort(1000000);
    printf("Median checksum: %lld\n", result);
    return 0;
}
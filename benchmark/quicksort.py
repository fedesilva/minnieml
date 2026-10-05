#!/usr/bin/env python3


def partition(arr, low, high):
    pivot = arr[high]
    i = low - 1

    for j in range(low, high):
        if arr[j] < pivot:
            i += 1
            arr[i], arr[j] = arr[j], arr[i]

    arr[i + 1], arr[high] = arr[high], arr[i + 1]
    return i + 1


def quicksort(arr, low, high):
    if low < high:
        p = partition(arr, low, high)
        quicksort(arr, low, p - 1)
        quicksort(arr, p + 1, high)


def run_sort(size):
    arr = []
    seed = 42
    for _ in range(size):
        seed = (seed * 25173 + 13849) % 65536
        arr.append(seed)
    quicksort(arr, 0, size - 1)
    return arr[size // 2]


if __name__ == "__main__":
    print(f"Median checksum: {run_sort(1000000)}")

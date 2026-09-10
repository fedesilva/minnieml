package main

import "fmt"

func partition(arr []int64, low, high int64) int64 {
	pivot := arr[high]
	i := low - 1
	for j := low; j < high; j++ {
		if arr[j] < pivot {
			i++
			arr[i], arr[j] = arr[j], arr[i]
		}
	}
	arr[i+1], arr[high] = arr[high], arr[i+1]
	return i + 1
}

func quicksort(arr []int64, low, high int64) {
	if low < high {
		p := partition(arr, low, high)
		quicksort(arr, low, p-1)
		quicksort(arr, p+1, high)
	}
}

func runSort(size int64) int64 {
	arr := make([]int64, size)
	var next int64 = 42
	for i := range arr {
		next = next*1664525 + 1013904223
		arr[i] = next % 100000
	}
	quicksort(arr, 0, size-1)
	return arr[size/2]
}

func main() {
	fmt.Printf("Median checksum: %d\n", runSort(1000000))
}

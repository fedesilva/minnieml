package main

import "fmt"

func partition(arr []int32, low, high int32) int32 {
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

func quicksort(arr []int32, low, high int32) {
	if low < high {
		p := partition(arr, low, high)
		quicksort(arr, low, p-1)
		quicksort(arr, p+1, high)
	}
}

func runSort(size int32) int32 {
	arr := make([]int32, size)
	var next int32 = 42
	for i := range arr {
		next = (next*25173 + 13849) % 65536
		arr[i] = next
	}
	quicksort(arr, 0, size-1)
	return arr[size/2]
}

func main() {
	fmt.Printf("Median checksum: %d\n", runSort(1000000))
}

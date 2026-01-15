package main

import (
	"fmt"
)

func fillMatrix(arr []int64, n int64, seed int64) {
	size := n * n
	currentSeed := seed
	for i := int64(0); i < size; i++ {
		currentSeed = (currentSeed * 1664525) + 1013904223
		arr[i] = currentSeed % 100
	}
}

func matMul(A []int64, B []int64, C []int64, n int64) {
	for i := int64(0); i < n; i++ {
		for j := int64(0); j < n; j++ {
			var acc int64 = 0
			for k := int64(0); k < n; k++ {
				// Flattened access
				valA := A[(i*n)+k]
				valB := B[(k*n)+j]
				acc += valA * valB
			}
			C[(i*n)+j] = acc
		}
	}
}

func trace(arr []int64, n int64) int64 {
	var acc int64 = 0
	for i := int64(0); i < n; i++ {
		acc += arr[(i*n)+i]
	}
	return acc
}

func main() {
	var n int64 = 500
	A := make([]int64, n*n)
	B := make([]int64, n*n)
	C := make([]int64, n*n)

	fillMatrix(A, n, 42)
	fillMatrix(B, n, 1337)

	matMul(A, B, C, n)

	result := trace(C, n)
	fmt.Printf("Trace Checksum: %d\n", result)
}

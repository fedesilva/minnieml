package main

import (
	"fmt"
)

func fillMatrix(arr []int32, n int32, seed int32) {
	size := n * n
	currentSeed := seed
	for i := int32(0); i < size; i++ {
		currentSeed = (currentSeed*25173 + 13849) % 65536
		arr[i] = (currentSeed % 100) - 50
	}
}

func matMul(A []int32, B []int32, C []int32, n int32) {
	for i := int32(0); i < n; i++ {
		for j := int32(0); j < n; j++ {
			var acc int32 = 0
			for k := int32(0); k < n; k++ {
				// Flattened access
				valA := A[(i*n)+k]
				valB := B[(k*n)+j]
				acc += valA * valB
			}
			C[(i*n)+j] = acc
		}
	}
}

func trace(arr []int32, n int32) int32 {
	var acc int32 = 0
	for i := int32(0); i < n; i++ {
		acc += arr[(i*n)+i]
	}
	return acc
}

func main() {
	var n int32 = 500
	A := make([]int32, n*n)
	B := make([]int32, n*n)
	C := make([]int32, n*n)

	fillMatrix(A, n, 42)
	fillMatrix(B, n, 1337)

	matMul(A, B, C, n)

	result := trace(C, n)
	fmt.Printf("Trace Checksum: %d\n", result)
}

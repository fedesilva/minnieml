package main

import "fmt"

func fillMatrix(arr []int64, n int64, seed int64) {
	currentSeed := seed
	for i := range arr {
		currentSeed = (currentSeed * 1664525) + 1013904223
		arr[i] = currentSeed % 100
	}
}

// matMul uses loop interchange (i-k-j) for cache friendliness.
// This ensures sequential access to both B and C in the innermost loop.
func matMul(A, B, C []int64, n int64) {
	N := int(n)
	for i := 0; i < N; i++ {
		rowA := i * N
		for k := 0; k < N; k++ {
			valA := A[rowA+k]
			rowB := k * N
			for j := 0; j < N; j++ {
				C[rowA+j] += valA * B[rowB+j]
			}
		}
	}
}

func trace(arr []int64, n int64) int64 {
	var acc int64 = 0
	N := int(n)
	for i := 0; i < N; i++ {
		acc += arr[(i*N)+i]
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

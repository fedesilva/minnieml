package main

import "fmt"

func fillMatrix(arr []int64, n int64, seed int64) {
	currentSeed := seed
	// Range loop allows BCE (Bounds Check Elimination)
	for i := range arr {
		currentSeed = (currentSeed * 1664525) + 1013904223
		arr[i] = currentSeed % 100
	}
}

func matMul(A []int64, B []int64, C []int64, n int64) {
	N := int(n)
	size := N * N

	// BCE Hint: Prove to the compiler that all arrays are large enough.
	// This single check allows the compiler to eliminate bounds checks 
	// inside the loops because i, j, k are bounded by N.
	_ = A[size-1]
	_ = B[size-1]
	_ = C[size-1]

	for i := 0; i < N; i++ {
		rowOffset := i * N
		for j := 0; j < N; j++ {
			var acc int64 = 0
			for k := 0; k < N; k++ {
				// Naive i-j-k access pattern
				// A[i*N + k]
				valA := A[rowOffset+k]
				// B[k*N + j]
				valB := B[k*N+j]

				acc += valA * valB
			}
			C[rowOffset+j] = acc
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
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
	// Pre-calculate strict integer bounds to help BCE
	N := int(n)

	for i := 0; i < N; i++ {
		// Optimization: Slice the row out of A once.
		// rowA becomes a simple slice. Accessing rowA[k] is
		// much easier for the compiler to optimize than A[i*n+k]
		rowStart := i * N
		rowA := A[rowStart : rowStart+N]

		for j := 0; j < N; j++ {
			var acc int64 = 0
			for k := 0; k < N; k++ {
				// rowA[k] is now a direct access with fewer checks
				valA := rowA[k]

				// B is still hard because of the stride (k*n + j),
				// but we stick to the algorithm.
				valB := B[k*N+j]

				acc += valA * valB
			}
			C[rowStart+j] = acc
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

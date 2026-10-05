package main

import "fmt"

func fillMatrix(arr []int32, n int32, seed int32) {
	currentSeed := seed
	// Range loop allows BCE (Bounds Check Elimination)
	for i := range arr {
		currentSeed = (currentSeed*25173 + 13849) % 65536
		arr[i] = (currentSeed % 100) - 50
	}
}

func matMul(A []int32, B []int32, C []int32, n int32) {
	N := int(n)
	size := N * N

	// Check that all buffers cover the matrix. These checks do not eliminate
	// the inner-loop bounds checks with Go 1.27.1 on darwin/arm64.
	_ = A[size-1]
	_ = B[size-1]
	_ = C[size-1]

	for i := 0; i < N; i++ {
		rowOffset := i * N
		for j := 0; j < N; j++ {
			var acc int32 = 0
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

func trace(arr []int32, n int32) int32 {
	var acc int32 = 0
	N := int(n)
	for i := 0; i < N; i++ {
		acc += arr[(i*N)+i]
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

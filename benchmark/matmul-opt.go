package main

import "fmt"

func fillMatrix(arr []int32, n int32, seed int32) {
	currentSeed := seed
	for i := range arr {
		currentSeed = (currentSeed*25173 + 13849) % 65536
		arr[i] = (currentSeed % 100) - 50
	}
}

// matMul uses loop interchange (i-k-j) for cache friendliness.
// This ensures sequential access to both B and C in the innermost loop.
func matMul(A, B, C []int32, n int32) {
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

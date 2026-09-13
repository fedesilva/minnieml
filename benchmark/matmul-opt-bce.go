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

// matMul uses i-k-j order with equal-length row slices so the inner range
// loop needs no bounds checks for B or C.
func matMul(A, B, C []int64, n int64) {
	N := int(n)
	for i := 0; i < N; i++ {
		aRow := A[i*N : (i+1)*N]
		cRow := C[i*N : (i+1)*N]
		clear(cRow)

		for k, a := range aRow {
			bRow := B[k*N : (k+1)*N]
			for j, b := range bRow {
				cRow[j] += a * b
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

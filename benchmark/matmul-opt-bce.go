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

// matMul uses i-k-j order with equal-length row slices so the inner range
// loop needs no bounds checks for B or C.
func matMul(A, B, C []int32, n int32) {
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

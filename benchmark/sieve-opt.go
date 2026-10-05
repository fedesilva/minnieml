package main

import "fmt"

func initSieve(arr []int32) {
	// Optimization 1: Use range loop.
	// Go compiler proves index is safe -> Removes bounds checks.
	// likely compiles to efficient memclr/memset logic.
	for i := range arr {
		arr[i] = 1
	}
}

func clearMultiples(arr []int32, factor, num, size int32) {
	// Optimization 2: Slice the array up front.
	// This proves to the compiler that 'arr' has at least 'size' elements.
	// It removes bounds checks inside the loop logic below.
	arr = arr[:size]

	for num < size {
		arr[num] = 0
		num += factor
	}
}

func findNextPrime(arr []int32, i, limit int32) int32 {
	for i <= limit {
		if arr[i] == 1 {
			return i
		}
		i++
	}
	return 0
}

func isqrt(n, guess int32) int32 {
	for {
		next := (guess + n/guess) / 2
		if next >= guess {
			return guess
		}
		guess = next
	}
}

func countPrimes(arr []int32) int32 {
	var count int32 = 1
	// Optimization 3: Range loop + Branchless addition.
	// - Range eliminates bounds checks.
	// - Adding 'v' eliminates branch misprediction.
	for _, v := range arr {
		count += v
	}
	return count
}

func runSieve(limit int32) int32 {
	size := (limit + 1) / 2
	arr := make([]int32, size)

	initSieve(arr)
	arr[0] = 0

	q := isqrt(limit, limit/2)

	for factor := int32(3); factor <= q; {
		next := findNextPrime(arr, factor/2, q/2)
		if next == 0 {
			break
		}

		actualFactor := next*2 + 1
		start := actualFactor * actualFactor / 2
		clearMultiples(arr, actualFactor, start, size)

		factor = actualFactor + 2
	}

	return countPrimes(arr)
}

func main() {
	count := runSieve(1_000_000)
	fmt.Printf("Primes found: %d\n", count)
}

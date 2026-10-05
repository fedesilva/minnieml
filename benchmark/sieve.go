package main

import "fmt"

func initSieve(arr []int32) {
	// Optimization: Use range to eliminate bounds checks
	for i := range arr {
		arr[i] = 1
	}
}

func clearMultiples(arr []int32, factor, num int32) {
	// Optimization: Hoist the length check to help the compiler
	// eliminate bounds checks inside the loop
	size := int32(len(arr))
	for num < size {
		arr[num] = 0
		num += factor
	}
}

func findNextPrime(arr []int32, i, limit int32) int32 {
	// We iterate manually, but we can hint the compiler
	for i <= limit {
		// Optimization: Branchless return is hard here,
		// but this loop runs much less often than the others.
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
	// Optimization 1: Use range for BCE (Bounds Check Elimination)
	// Optimization 2: Branchless summation
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

	// Note: We use an explicit loop here because the stride is irregular
	for factor := int32(3); factor <= q; {
		next := findNextPrime(arr, factor/2, q/2)
		if next == 0 {
			break
		}

		actualFactor := next*2 + 1
		start := actualFactor * actualFactor / 2

		clearMultiples(arr, actualFactor, start)

		factor = actualFactor + 2
	}

	return countPrimes(arr)
}

func main() {
	count := runSieve(1_000_000)
	fmt.Printf("Primes found: %d\n", count)
}

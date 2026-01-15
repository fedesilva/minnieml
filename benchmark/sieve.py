#!/usr/bin/env python3

def init_sieve(arr, i, size):
    while i < size:
        arr[i] = 1
        i += 1

def clear_multiples(arr, factor, num, size):
    while num < size:
        arr[num] = 0
        num += factor

def find_next_prime(arr, i, limit):
    while i <= limit:
        if arr[i] == 1:
            return i
        i += 1
    return 0

def isqrt(n, guess):
    while True:
        next_guess = (guess + n // guess) // 2
        if next_guess >= guess:
            return guess
        guess = next_guess

def count_primes(arr, size):
    count = 1
    i = 0
    while i < size:
        if arr[i] == 1:
            count += 1
        i += 1
    return count

def run_sieve(limit):
    size = (limit + 1) // 2
    arr = [0] * size
    init_sieve(arr, 0, size)
    arr[0] = 0

    q = isqrt(limit, limit // 2)

    factor = 3
    while factor <= q:
        next_prime = find_next_prime(arr, factor // 2, q // 2)
        if next_prime == 0:
            break
        actual_factor = next_prime * 2 + 1
        start = actual_factor * actual_factor // 2
        clear_multiples(arr, actual_factor, start, size)
        factor = actual_factor + 2

    return count_primes(arr, size)

if __name__ == "__main__":
    count = run_sieve(1000000)
    print(f"Primes found: {count}")

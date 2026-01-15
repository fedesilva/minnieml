#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

void init_sieve(int64_t *arr, int64_t i, int64_t size) {
    while (i < size) {
        arr[i] = 1;
        i++;
    }
}

void clear_multiples(int64_t *arr, int64_t factor, int64_t num, int64_t size) {
    while (num < size) {
        arr[num] = 0;
        num += factor;
    }
}

int64_t find_next_prime(int64_t *arr, int64_t i, int64_t limit) {
    while (i <= limit) {
        if (arr[i] == 1) {
            return i;
        }
        i++;
    }
    return 0;
}

int64_t isqrt(int64_t n, int64_t guess) {
    while (1) {
        int64_t next = (guess + n / guess) / 2;
        if (next >= guess) {
            return guess;
        }
        guess = next;
    }
}

// int64_t count_primes(int64_t *arr, int64_t size) {
//     int64_t count = 1;
//     int64_t i = 0;
//     while (i < size) {
//         if (arr[i] == 1) {
//             count++;
//         }
//         i++;
//     }
//     return count;
// }

int64_t count_primes(int64_t *arr, int64_t size)
{
    int64_t count = 1; // Start at 1 because we skipped 2
    int64_t i = 0;
    while (i < size)
    {
        // OLD: if (arr[i] == 1) count++;

        // NEW: Branchless summation
        count += arr[i];

        i++;
    }
    return count;
}

int64_t run_sieve(int64_t limit) {
    int64_t size = (limit + 1) / 2;
    int64_t *arr = malloc(size * sizeof(int64_t));
    init_sieve(arr, 0, size);
    arr[0] = 0;

    int64_t q = isqrt(limit, limit / 2);

    int64_t factor = 3;
    while (factor <= q) {
        int64_t next = find_next_prime(arr, factor / 2, q / 2);
        if (next == 0) {
            break;
        }
        int64_t actual_factor = next * 2 + 1;
        int64_t start = actual_factor * actual_factor / 2;
        clear_multiples(arr, actual_factor, start, size);
        factor = actual_factor + 2;
    }

    int64_t result = count_primes(arr, size);
    free(arr);
    return result;
}

int main() {
    int64_t count = run_sieve(1000000);
    printf("Primes found: %lld\n", count);
    return 0;
}

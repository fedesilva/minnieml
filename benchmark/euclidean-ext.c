#include <stdio.h>
#include <stdint.h>

// Extended Euclidean Algorithm - returns s coefficient
int64_t egcd_loop(int64_t r0, int64_t r1, int64_t s0, int64_t s1) {
    while (r1 != 0) {
        int64_t q = r0 / r1;
        int64_t r2 = r0 - (q * r1);
        int64_t s2 = s0 - (q * s1);
        r0 = r1;
        r1 = r2;
        s0 = s1;
        s1 = s2;
    }
    return s0;
}

// Returns x such that (a * x) mod m = 1
int64_t mod_inverse(int64_t a, int64_t m) {
    int64_t x = egcd_loop(a, m, 1, 0);
    return (x < 0) ? (x + m) : x;
}

// Check if number is odd
int is_odd(int64_t n) {
    return (n % 2) == 1;
}

// Fast modular exponentiation: computes (base^exp) mod m
int64_t mod_exp_loop(int64_t base, int64_t exp, int64_t m, int64_t result) {
    while (exp != 0) {
        int64_t new_result = is_odd(exp) ? (result * base) % m : result;
        int64_t new_base = (base * base) % m;
        result = new_result;
        base = new_base;
        exp = exp / 2;
    }
    return result;
}

int64_t mod_exp(int64_t base, int64_t exp, int64_t m) {
    return mod_exp_loop(base, exp, m, 1);
}

// Benchmark: RSA-style operations
int64_t rsa_bench_loop(int64_t i, int64_t n, int64_t p, int64_t sum) {
    while (i < n) {
        int64_t encrypted = mod_exp(i, 65537, p);
        int64_t inv = mod_inverse(encrypted, p);
        sum += inv;
        i++;
    }
    return sum;
}

int main(void) {
    int64_t p = 1000000007;  // Large prime
    int64_t n = 10000;
    
    int64_t result = rsa_bench_loop(2, n, p, 0);
    printf("Checksum: %lld\n", result);
    
    return 0;
}

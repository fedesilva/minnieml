#include <stdio.h>
#include <stdint.h>
#include <inttypes.h>

// Extended Euclidean Algorithm - returns s coefficient
int32_t egcd_loop(int32_t r0, int32_t r1, int32_t s0, int32_t s1) {
    while (r1 != 0) {
        int32_t q = r0 / r1;
        int32_t r2 = r0 - (q * r1);
        int32_t s2 = s0 - (q * s1);
        r0 = r1;
        r1 = r2;
        s0 = s1;
        s1 = s2;
    }
    return s0;
}

// Returns x such that (a * x) mod m = 1
int32_t mod_inverse(int32_t a, int32_t m) {
    int32_t x = egcd_loop(a, m, 1, 0);
    return (x < 0) ? (x + m) : x;
}

// Check if number is odd
int is_odd(int32_t n) {
    return (n % 2) == 1;
}

// Fast modular exponentiation: computes (base^exp) mod m
int32_t mod_exp_loop(int32_t base, int32_t exp, int32_t m, int32_t result) {
    while (exp != 0) {
        int32_t new_result = is_odd(exp) ? (result * base) % m : result;
        int32_t new_base = (base * base) % m;
        result = new_result;
        base = new_base;
        exp = exp / 2;
    }
    return result;
}

int32_t mod_exp(int32_t base, int32_t exp, int32_t m) {
    return mod_exp_loop(base, exp, m, 1);
}

// Benchmark: RSA-style operations
int32_t rsa_bench_loop(int32_t i, int32_t n, int32_t p, int32_t sum) {
    while (i < n) {
        int32_t encrypted = mod_exp(i, 65537, p);
        int32_t inv = mod_inverse(encrypted, p);
        sum += inv;
        i++;
    }
    return sum;
}

int main(void) {
    int32_t p = 10007;  // Prime small enough for Int32 products and checksum
    int32_t n = 10000;
    
    int32_t result = rsa_bench_loop(2, n, p, 0);
    printf("Checksum: %" PRId32 "\n", result);
    
    return 0;
}

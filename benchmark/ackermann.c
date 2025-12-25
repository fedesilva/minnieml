// ackermann.c
// Build: clang -O3 -flto -march=native -DNDEBUG ackermann.c -o ackermann

#include <stdint.h>
#include <stdio.h>

static int64_t ackermann(int64_t m, int64_t n) {
  if (m == 0) return n + 1;
  if (n == 0) return ackermann(m - 1, 1);
  return ackermann(m - 1, ackermann(m, n - 1));
}

int main(void) {
  const int64_t result = ackermann(3, 10);
  // Fast enough; printf dominates basically nothing here, but keep it simple.
  printf("ackermann(3, 10) = %lld\n", (long long)result);
  return 0;
}


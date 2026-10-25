// ackermann.c
// Build: clang -O3 -flto -march=native -DNDEBUG ackermann.c -o ackermann

#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>

static int32_t ackermann(int32_t m, int32_t n) {
  if (m == 0) return n + 1;
  if (n == 0) return ackermann(m - 1, 1);
  return ackermann(m - 1, ackermann(m, n - 1));
}

int main(void) {
  const int32_t result = ackermann(3, 10);
  printf("ackermann(3, 10) = %" PRId32 "\n", result);
  return 0;
}


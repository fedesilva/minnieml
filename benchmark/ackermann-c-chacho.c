// ackermann.c — single file, recursive, prints via writev like your runtime.
//
// build (clang):
//   clang -O3 -flto -march=native -DNDEBUG -o ackermann-c ackermann.c

#define _POSIX_C_SOURCE 200809L

#include <stdint.h>
#include <stddef.h>
#include <sys/uio.h>
#include <unistd.h>

typedef struct {
    int64_t length;
    const char* data;
} String;

static inline void println(String str) {
    if (!str.data) return;
    struct iovec iov[2];
    iov[0].iov_base = (void*)str.data;
    iov[0].iov_len  = (size_t)str.length;
    iov[1].iov_base = (void*)"\n";
    iov[1].iov_len  = 1;
    (void)writev(STDOUT_FILENO, iov, 2);
}

static inline String lit(const char* s) {
    // compile-time literals only; caller provides correct length manually if you care.
    // (we avoid strlen to keep the benchmark focused on recursion)
    String r = {0, s};
    // best effort: count at compile-time? not in C; so do it manually below.
    return r;
}

// Minimal int64 -> decimal ASCII (no malloc). Returns a String pointing at a local buffer.
// Caller must use/print before buffer goes out of scope.
static inline String i64_to_string(int64_t v, char buf[32]) {
    char* p = buf + 31;
    *p = '\0';

    uint64_t u = (v < 0) ? (uint64_t)(-(v + 1)) + 1u : (uint64_t)v; // safe abs
    do {
        *--p = (char)('0' + (u % 10));
        u /= 10;
    } while (u);

    if (v < 0) *--p = '-';

    String s;
    s.data = p;
    s.length = (int64_t)((buf + 31) - p);
    return s;
}

//__attribute__((noinline))
static int64_t ackermann(int64_t m, int64_t n) {
    if (m == 0) return n + 1;
    if (n == 0) return ackermann(m - 1, 1);
    return ackermann(m - 1, ackermann(m, n - 1));
}

int main(void) {
    int64_t result = ackermann(3, 10);

    // Print: "ackermann(3, 10) = " + result
    // Keep it simple: two writev calls (prefix + number+newline) or one combined.
    static const char prefix[] = "ackermann(3, 10) = ";
    char numbuf[32];
    String num = i64_to_string(result, numbuf);

    struct iovec iov[3];
    iov[0].iov_base = (void*)prefix;
    iov[0].iov_len  = sizeof(prefix) - 1;
    iov[1].iov_base = (void*)num.data;
    iov[1].iov_len  = (size_t)num.length;
    iov[2].iov_base = (void*)"\n";
    iov[2].iov_len  = 1;
    (void)writev(STDOUT_FILENO, iov, 3);

    return 0;
}


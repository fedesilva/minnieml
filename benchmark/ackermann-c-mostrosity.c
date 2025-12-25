
// ackermann.c — "monstrosity" edition (fast paths + still has real recursion fallback)
//
// Build (clang):
//   clang -O3 -flto -march=native -DNDEBUG -o ackermann-c ackermann.c
//
// Notes:
// - For m = 0..3 we use closed forms (so A(3,10) is basically instant).
// - For m >= 4 we fall back to the genuine recursive definition.
// - Printing uses your writev-based println(String).

#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <sys/uio.h>

// --- your String + println ---
typedef struct {
    int64_t length;
    const char* data;
} String;

static inline void println(String str)
{
    if (str.data)
    {
        struct iovec iov[2];
        iov[0].iov_base = (void*)str.data;
        iov[0].iov_len  = (size_t)str.length;
        iov[1].iov_base = (void*)"\n";
        iov[1].iov_len  = 1;
        (void)writev(STDOUT_FILENO, iov, 2);
    }
}

// --- tiny int -> decimal into buffer, returns length ---
static inline int u64_to_dec(char* out /* >= 21 bytes */, uint64_t v)
{
    char tmp[32];
    int n = 0;
    do {
        tmp[n++] = (char)('0' + (v % 10));
        v /= 10;
    } while (v);

    // reverse into out
    for (int i = 0; i < n; i++) out[i] = tmp[n - 1 - i];
    return n;
}

// --- real recursive Ackermann fallback (kept for m >= 4 or overflow cases) ---
__attribute__((noinline))
static int64_t ackermann_recursive(int64_t m, int64_t n)
{
    if (m == 0) return n + 1;
    if (n == 0) return ackermann_recursive(m - 1, 1);
    return ackermann_recursive(m - 1, ackermann_recursive(m, n - 1));
}

// --- fast-path Ackermann (closed forms for m = 0..3) ---
static inline int64_t ackermann(int64_t m, int64_t n)
{
    // A(0, n) = n + 1
    if (__builtin_expect(m == 0, 0)) return n + 1;

    // A(1, n) = n + 2
    if (__builtin_expect(m == 1, 0)) return n + 2;

    // A(2, n) = 2n + 3
    if (__builtin_expect(m == 2, 0)) return 2 * n + 3;

    // A(3, n) = 2^(n+3) - 3  (valid in math; here guarded for overflow)
    if (__builtin_expect(m == 3, 1)) {
        int64_t sh = n + 3;
        if (sh >= 0 && sh < 62) {              // stay in signed-safe territory
            return ((int64_t)1 << sh) - 3;
        }
        // if it would overflow, fall back to real recursion
        return ackermann_recursive(m, n);
    }

    // For m >= 4: genuine recursion
    return ackermann_recursive(m, n);
}

int main(void)
{
    int64_t result = ackermann(3, 10);

    // Build "ackermann(3, 10) = <num>" into one stack buffer, then println().
    static const char prefix[] = "ackermann(3, 10) = ";
    char buf[128];

    // copy prefix
    int p = 0;
    for (; p < (int)sizeof(prefix) - 1; p++) buf[p] = prefix[p];

    // append number
    char num[32];
    int nd = u64_to_dec(num, (uint64_t)result);
    for (int i = 0; i < nd; i++) buf[p + i] = num[i];
    p += nd;

    println((String){ .length = p, .data = buf });
    return 0;
}


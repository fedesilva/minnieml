// fizzbuzz_buf.c
#include <unistd.h>
#include <stdint.h>
#include <string.h>

static inline int mod_like_yours(int a, int b) {
    // same semantics as: a - (a / b) * b  for positive a,b
    return a - (a / b) * b;
}

static inline void flush_buf(char *buf, size_t *pos) {
    if (*pos) {
        (void)write(1, buf, *pos);
        *pos = 0;
    }
}

static inline void append_bytes(char *buf, size_t *pos, const char *s, size_t len) {
    // flush if not enough room
    if (*pos + len > 4096) flush_buf(buf, pos);

    // if chunk itself is bigger than buffer, write directly
    if (len > 4096) {
        (void)write(1, s, len);
        return;
    }

    memcpy(buf + *pos, s, len);
    *pos += len;
}

static inline void append_cstr(char *buf, size_t *pos, const char *s) {
    append_bytes(buf, pos, s, strlen(s));
}

static inline void append_int_line(char *buf, size_t *pos, int32_t v) {
    // write decimal v + '\n' into a small temp, then append
    char tmp[16];
    int i = 0;

    // v is positive here (1..n)
    char digits[16];
    int d = 0;
    while (v >= 10) {
        int q = v / 10;
        int r = v - q * 10;
        digits[d++] = (char)('0' + r);
        v = q;
    }
    digits[d++] = (char)('0' + v);

    // reverse into tmp
    while (d--) tmp[i++] = digits[d];
    tmp[i++] = '\n';

    append_bytes(buf, pos, tmp, (size_t)i);
}

int main(void) {
    const int32_t n = 10000000;

    char out[4096];
    size_t pos = 0;

    for (int32_t i = 1; i <= n; i++) {
        if (mod_like_yours(i, 15) == 0) {
            append_cstr(out, &pos, "FizzBuzz\n");
        } else if (mod_like_yours(i, 3) == 0) {
            append_cstr(out, &pos, "Fizz\n");
        } else if (mod_like_yours(i, 5) == 0) {
            append_cstr(out, &pos, "Buzz\n");
        } else {
            append_int_line(out, &pos, i);
        }
    }

    flush_buf(out, &pos);
    return 0;
}


#include <string.h>
#include <unistd.h>

int main(void) {
    static char lines[65536];
    static const char message[] = "diagnostic-before-loop\n";
    memset(lines, '\n', sizeof lines);
    if (write(2, message, sizeof message - 1) != (ssize_t)(sizeof message - 1))
        return 2;
    while (write(1, lines, sizeof lines) > 0) {}
    return 3;
}

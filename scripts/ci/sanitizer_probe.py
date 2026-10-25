"""Check native sanitizer startup and leak detection before compiler tests."""

from pathlib import Path
import sys
from tempfile import TemporaryDirectory

from process import run_command


SOURCE = r"""
#include <stdio.h>
#include <stdlib.h>

__attribute__((noinline)) static void allocate(int leak) {
    void *memory = malloc(64);
    if (!memory) exit(2);
    fprintf(stderr, "allocated %p\n", memory);
    if (!leak) free(memory);
}

int main(int argc, char **argv) {
    allocate(argc > 1);
    puts("reached main");
    return 0;
}
"""


def probe(output):
    output.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix="mml-sanitizer-") as temporary:
        directory = Path(temporary)
        source = directory / "probe.c"
        source.write_text(SOURCE)
        (output / "sanitizer-probe.c").write_text(SOURCE)
        for instrumented in [False, True]:
            name = "sanitized" if instrumented else "plain"
            binary = directory / name
            flags = ["-fsanitize=address,undefined"] if instrumented else []
            code, _ = run_command(
                ["clang", "-O0", "-fuse-ld=lld", *flags, str(source), "-o", str(binary)],
                output / f"sanitizer-{name}-build.log", 120,
            )
            if code:
                return code
            environment = [
                "env", "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=0",
                "LSAN_OPTIONS=exitcode=99",
            ]
            code, _ = run_command(
                [*environment, str(binary)], output / f"sanitizer-{name}-run.log", 15,
            )
            if code:
                return code
            if instrumented:
                log = output / "sanitizer-leak-control.log"
                log.unlink(missing_ok=True)
                code, _ = run_command([*environment, str(binary), "leak"], log, 15)
                if code != 99 or "LeakSanitizer: detected memory leaks" not in log.read_text():
                    print("Sanitizer leak control failed: expected exit 99 and a leak diagnostic.",
                          flush=True)
                    return code if code else 1
    return 0


if __name__ == "__main__":
    sys.exit(probe(Path(sys.argv[1])))

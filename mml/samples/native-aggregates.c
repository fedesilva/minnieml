/*
 * Companion to native-aggregates.mml. Run these commands from the repository root
 * with the LLVM Clang used by mmlc on PATH:
 *
 * mmlc -x lib -O3 -I mml/samples/native-aggregates.mml
 * clang -std=c17 -O3 -S -emit-llvm mml/samples/native-aggregates.c \
 *   -o build/NativeAggregatesC_opt.ll
 * sample_target=$(cat build/local-target-triple)
 * clang -std=c17 -O3 build/target/nativeaggregates.o mml/samples/native-aggregates.c \
 *   build/target/mml_runtime-"$sample_target".o -lm -o build/target/nativeaggregates
 * build/target/nativeaggregates
 *
 * MML optimized IR: build/out/<target>/NativeAggregates_opt.ll
 * C optimized IR: build/NativeAggregatesC_opt.ll
 * Library generation exposes nativeaggregates_main; the C main below calls it.
 * All three functions take and return structures by value. Vec2 and Rect contain
 * only floats; Wide contains three 64-bit integers and occupies 24 bytes.
 * Expected output:
 * Vec2: 11.5, 17.5
 * Rect: 4, 6, 8, 10
 * Wide: 22, 33, 11
 */

#include <stdint.h>

typedef struct { float x, y; } Vec2;
typedef struct { float x, y, width, height; } Rect;
typedef struct { int64_t a, b, c; } Wide;

Vec2 shift_point(Vec2 point)
{
    return (Vec2){point.x + 10.0f, point.y + 20.0f};
}

Rect grow_rect(Rect rect)
{
    return (Rect){rect.x + 1.0f, rect.y + 2.0f, rect.width + 3.0f, rect.height + 4.0f};
}

Wide rotate_wide(Wide wide)
{
    return (Wide){wide.b, wide.c, wide.a};
}

extern void nativeaggregates_main(void);
extern void mml_sys_flush(void);

int main(void)
{
    nativeaggregates_main();
    mml_sys_flush();
    return 0;
}

# When Your Functional Language Beats C 

I recently got recursion and functions with complex expressions working so I implemented 
the Ackermann function to stress-test recursion and function call performance.

## The Test

The Ackermann function is perfect for testing recursive performance - it's simple to implement but creates deep call stacks:

```mml
fn ackermann(m: Int, n: Int): Int =
  if m == 0 then
    n + 1
  else if n == 0 then
    ackermann (m - 1) 1
  else
    ackermann (m - 1) (ackermann m (n - 1))
;

fn main() =
  let result = ackermann 3 10;
  println (concat "ackermann(3, 10)
;
```

I ran `ackermann(3, 10)` which computes to 8189 after roughly 8,000 recursive calls—enough to stress-test
any calling convention.

Here's the Go version for comparison:

```go
package main

import "fmt"

func ackermann(m, n int64) int64 {
    if m == 0 {
        return n + 1
    } else if n == 0 {
        return ackermann(m-1, 1)
    } else {
        return ackermann(m-1, ackermann(m, n-1))
    }
}

func main() {
    result := ackermann(3, 10)
    fmt.Printf("ackermann(3, 10) = %d\n", result)
}
```

And the clean C version:

```c
#include <stdint.h>
#include <stdio.h>

static int64_t ackermann(int64_t m, int64_t n) {
    if (m == 0) return n + 1;
    if (n == 0) return ackermann(m - 1, 1);
    return ackermann(m - 1, ackermann(m, n - 1));
}

int main(void) {
    const int64_t result = ackermann(3, 10);
    printf("ackermann(3, 10) = %lld\n", (long long)result);
    return 0;
}
```

## The Surprise

MML's performance caught me off guard. Benchmarks on an Intel Core i9-9880H (macOS Sequoia),
measuring wall-clock time:

- Go: ~0.21s
- C (clean version): ~0.15s
- MML: ~0.12s

That's MML beating C by 20% and Go by 40% on pure recursion.

The C version was compiled with `clang -O3 -flto -march=native`. MML generates LLVM IR,
runs `opt -O3` on it, then links with `clang -O3 -flto -march=native`—same optimization
pipeline as the C version. 

To be clear: C can match or beat this with enough manual restructuring — the point is that MML didn’t require it.

## The Plot Twist

My friend Chacho sent me a C version that runs in ~0.006s. Looking at the code revealed the trick:

```c
// Fast-path Ackermann with closed forms for m = 0..3
static inline int64_t ackermann(int64_t m, int64_t n) {
    // A(0, n) = n + 1
    if (__builtin_expect(m == 0, 0)) return n + 1;
    
    // A(1, n) = n + 2
    if (__builtin_expect(m == 1, 0)) return n + 2;
    
    // A(2, n) = 2n + 3
    if (__builtin_expect(m == 2, 0)) return 2 * n + 3;
    
    // A(3, n) = 2^(n+3) - 3
    if (__builtin_expect(m == 3, 1)) {
        int64_t sh = n + 3;
        if (sh >= 0 && sh < 62) {  // stay in signed-safe territory
            return ((int64_t)1 << sh) - 3;
        }
        // if it would overflow, fall back to real recursion
        return ackermann_recursive(m, n);
    }
    
    // For m >= 4: genuine recursion
    return ackermann_recursive(m, n);
}
```

That's cheating, he sent me a ringer!

It's using closed-form solutions for small values of m, completely avoiding recursion for our test case. The key optimization for our benchmark:

```c
// A(3, n) = 2^(n+3) - 3
return ((int64_t)1 << (n + 3)) - 3;
```

Clever, but not exactly testing what we set out to test.

## The Response

MML didn't have bit shift operators yet. But here's the thing - adding them took exactly two lines of MML code:

```mml
op << (a: Int, b: Int): Int 55 left = @native[op=shl];
op >> (a: Int, b: Int): Int 55 left = @native[op=ashr];
```

That's it. Not a compiler modification, not a language extension - just two operator declarations written in MML itself. 
The operator system lets you define precedence (55) and associativity (left) right in the declaration, and `@native` bridges to LLVM's shift instructions.

With those operators, I could write the same optimization:

```mml
fn ackermann(m: Int, n: Int): Int =
  if m == 0 then n + 1
  else if m == 1 then n + 2
  else if m == 2 then 2 * n + 3
  else if m == 3 then (1 << (n + 3)) - 3
  else ackermann (m - 1) (ackermann m (n - 1))
;
```

Result: ~0.006s, matching the optimized C version.

## The Real Victory

While it's satisfying that MML can match C's tricks when needed, the real win is that the clean, recursive version - the one 
that actually tests function call performance - beats C's recursive implementation. 

This suggests LLVM can exploit optimization opportunities exposed by the functional formulation 
that aren’t as easily visible in the imperative version.

The ability to extend the language with two lines of code when you need low-level operations? 
That's the kind of flexibility that makes a language practical for real work. 
The operators aren't built into the compiler - they're just part of the standard library, written in MML itself.






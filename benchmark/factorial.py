"""
Factorial function implementation.

Usage examples:
  factorial(5)  => 120
  3 + factorial(4) => 3 + 24 = 27
"""


def factorial(n: int) -> int:
    if n <= 1:
        return 1
    else:
        return n * factorial(n - 1)


def main():
    # print("Give me a number")
    # num = int(input())
    # print(factorial(num))
    print(factorial(20))


if __name__ == "__main__":
    main()

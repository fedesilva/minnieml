public class Sieve {

    static void initSieve(long[] arr, int i, int size) {
        while (i < size) {
            arr[i] = 1;
            i++;
        }
    }

    static void clearMultiples(long[] arr, int factor, int num, int size) {
        while (num < size) {
            arr[num] = 0;
            num += factor;
        }
    }

    static int findNextPrime(long[] arr, int i, int limit) {
        while (i <= limit) {
            if (arr[i] == 1) {
                return i;
            }
            i++;
        }
        return 0;
    }

    static int isqrt(int n, int guess) {
        while (true) {
            int next = (guess + n / guess) / 2;
            if (next >= guess) {
                return guess;
            }
            guess = next;
        }
    }

    static long countPrimes(long[] arr, int size) {
        long count = 1;
        for (int i = 0; i < size; i++) {
            count += arr[i];
        }
        return count;
    }

    static long runSieve(int limit) {
        int size = (limit + 1) / 2;
        long[] arr = new long[size];
        initSieve(arr, 0, size);
        arr[0] = 0;

        int q = isqrt(limit, limit / 2);
        int factor = 3;

        while (factor <= q) {
            int next = findNextPrime(arr, factor / 2, q / 2);
            if (next == 0) {
                break;
            }
            int actualFactor = next * 2 + 1;
            int start = actualFactor * actualFactor / 2;
            clearMultiples(arr, actualFactor, start, size);
            factor = actualFactor + 2;
        }

        return countPrimes(arr, size);
    }

    public static void main(String[] args) {
        System.out.println("Primes found: " + runSieve(1000000));
    }
}

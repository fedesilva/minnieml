public class Matmul {

    static void fillMatrix(long[] arr, long seed) {
        long currentSeed = seed;
        for (int i = 0; i < arr.length; i++) {
            currentSeed = currentSeed * 1664525 + 1013904223;
            arr[i] = currentSeed % 100;
        }
    }

    // Naive i-j-k multiplication with strided access on B.
    static void matMul(long[] a, long[] b, long[] c, int n) {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                long acc = 0;
                for (int k = 0; k < n; k++) {
                    long valA = a[i * n + k];
                    long valB = b[k * n + j];
                    acc += valA * valB;
                }
                c[i * n + j] = acc;
            }
        }
    }

    static long trace(long[] arr, int n) {
        long acc = 0;
        for (int i = 0; i < n; i++) {
            acc += arr[i * n + i];
        }
        return acc;
    }

    public static void main(String[] args) {
        int n = 500;
        long[] a = new long[n * n];
        long[] b = new long[n * n];
        long[] c = new long[n * n];

        fillMatrix(a, 42);
        fillMatrix(b, 1337);
        matMul(a, b, c, n);

        System.out.println("Trace Checksum: " + trace(c, n));
    }
}

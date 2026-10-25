public class Matmul {

    static void fillMatrix(int[] arr, int seed) {
        int currentSeed = seed;
        for (int i = 0; i < arr.length; i++) {
            currentSeed = (currentSeed * 25173 + 13849) % 65536;
            arr[i] = (currentSeed % 100) - 50;
        }
    }

    // Naive i-j-k multiplication with strided access on B.
    static void matMul(int[] a, int[] b, int[] c, int n) {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int acc = 0;
                for (int k = 0; k < n; k++) {
                    int valA = a[i * n + k];
                    int valB = b[k * n + j];
                    acc += valA * valB;
                }
                c[i * n + j] = acc;
            }
        }
    }

    static int trace(int[] arr, int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc += arr[i * n + i];
        }
        return acc;
    }

    public static void main(String[] args) {
        int n = 500;
        int[] a = new int[n * n];
        int[] b = new int[n * n];
        int[] c = new int[n * n];

        fillMatrix(a, 42);
        fillMatrix(b, 1337);
        matMul(a, b, c, n);

        System.out.println("Trace Checksum: " + trace(c, n));
    }
}

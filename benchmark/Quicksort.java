public class Quicksort {

    static void swap(long[] arr, int a, int b) {
        long tmp = arr[a];
        arr[a] = arr[b];
        arr[b] = tmp;
    }

    static int partition(long[] arr, int low, int high) {
        long pivot = arr[high];
        int i = low - 1;

        for (int j = low; j < high; j++) {
            if (arr[j] < pivot) {
                i++;
                swap(arr, i, j);
            }
        }

        swap(arr, i + 1, high);
        return i + 1;
    }

    static void quicksort(long[] arr, int low, int high) {
        if (low < high) {
            int p = partition(arr, low, high);
            quicksort(arr, low, p - 1);
            quicksort(arr, p + 1, high);
        }
    }

    static long runSort(int size) {
        long[] arr = new long[size];
        long next = 42;

        for (int i = 0; i < size; i++) {
            next = next * 1664525 + 1013904223;
            arr[i] = next % 100000;
        }

        quicksort(arr, 0, size - 1);
        return arr[size / 2];
    }

    public static void main(String[] args) {
        System.out.println("Median checksum: " + runSort(1000000));
    }
}

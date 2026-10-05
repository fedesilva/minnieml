public class Quicksort {

    static void swap(int[] arr, int a, int b) {
        int tmp = arr[a];
        arr[a] = arr[b];
        arr[b] = tmp;
    }

    static int partition(int[] arr, int low, int high) {
        int pivot = arr[high];
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

    static void quicksort(int[] arr, int low, int high) {
        if (low < high) {
            int p = partition(arr, low, high);
            quicksort(arr, low, p - 1);
            quicksort(arr, p + 1, high);
        }
    }

    static int runSort(int size) {
        int[] arr = new int[size];
        int next = 42;

        for (int i = 0; i < size; i++) {
            next = (next * 25173 + 13849) % 65536;
            arr[i] = next;
        }

        quicksort(arr, 0, size - 1);
        return arr[size / 2];
    }

    public static void main(String[] args) {
        System.out.println("Median checksum: " + runSort(1000000));
    }
}

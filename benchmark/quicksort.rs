fn partition(arr: &mut [i64], low: i64, high: i64) -> i64 {
    let pivot = arr[high as usize];
    let mut i = low - 1;
    for j in low..high {
        if arr[j as usize] < pivot {
            i += 1;
            arr.swap(i as usize, j as usize);
        }
    }
    arr.swap((i + 1) as usize, high as usize);
    i + 1
}

fn quicksort(arr: &mut [i64], low: i64, high: i64) {
    if low < high {
        let p = partition(arr, low, high);
        quicksort(arr, low, p - 1);
        quicksort(arr, p + 1, high);
    }
}

fn run_sort(size: i64) -> i64 {
    let mut arr = vec![0i64; size as usize];
    let mut next: i64 = 42;
    for slot in &mut arr {
        next = next.wrapping_mul(1664525).wrapping_add(1013904223);
        *slot = next % 100000;
    }
    quicksort(&mut arr, 0, size - 1);
    arr[(size / 2) as usize]
}

fn main() {
    println!("Median checksum: {}", run_sort(1000000));
}

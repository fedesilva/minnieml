fn partition(arr: &mut [i32], low: i32, high: i32) -> i32 {
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

fn quicksort(arr: &mut [i32], low: i32, high: i32) {
    if low < high {
        let p = partition(arr, low, high);
        quicksort(arr, low, p - 1);
        quicksort(arr, p + 1, high);
    }
}

fn run_sort(size: i32) -> i32 {
    let mut arr = vec![0i32; size as usize];
    let mut next: i32 = 42;
    for slot in &mut arr {
        next = (next * 25173 + 13849) % 65536;
        *slot = next;
    }
    quicksort(&mut arr, 0, size - 1);
    arr[(size / 2) as usize]
}

fn main() {
    println!("Median checksum: {}", run_sort(1000000));
}

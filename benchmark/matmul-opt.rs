fn fill_matrix(arr: &mut [i64], seed: i64) {
    let mut current_seed = seed;
    for slot in arr.iter_mut() {
        current_seed = current_seed.wrapping_mul(1664525).wrapping_add(1013904223);
        *slot = current_seed % 100;
    }
}

// Loop interchange (i-k-j): sequential access to B and C in the inner loop.
// Rust slices are non-aliasing, so this matches matmul-restricted.c's restrict
// guarantee without an explicit keyword. C starts zeroed from `vec![0i64; size]`.
fn mat_mul(a: &[i64], b: &[i64], c: &mut [i64], n: i64) {
    for i in 0..n {
        for k in 0..n {
            let val_a = a[(i * n + k) as usize];
            for j in 0..n {
                c[(i * n + j) as usize] += val_a * b[(k * n + j) as usize];
            }
        }
    }
}

fn trace(arr: &[i64], n: i64) -> i64 {
    let mut acc: i64 = 0;
    for i in 0..n {
        acc += arr[(i * n + i) as usize];
    }
    acc
}

fn main() {
    let n: i64 = 500;
    let size = (n * n) as usize;
    let mut a = vec![0i64; size];
    let mut b = vec![0i64; size];
    let mut c = vec![0i64; size];

    fill_matrix(&mut a, 42);
    fill_matrix(&mut b, 1337);

    mat_mul(&a, &b, &mut c, n);

    println!("Trace Checksum: {}", trace(&c, n));
}

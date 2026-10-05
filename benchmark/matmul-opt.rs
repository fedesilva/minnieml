fn fill_matrix(arr: &mut [i32], seed: i32) {
    let mut current_seed = seed;
    for slot in arr.iter_mut() {
        current_seed = (current_seed * 25173 + 13849) % 65536;
        *slot = (current_seed % 100) - 50;
    }
}

// Loop interchange (i-k-j): sequential access to B and C in the inner loop.
// Rust slices are non-aliasing, so this matches matmul-restricted.c's restrict
// guarantee without an explicit keyword. C starts zeroed from `vec![0i32; size]`.
fn mat_mul(a: &[i32], b: &[i32], c: &mut [i32], n: i32) {
    for i in 0..n {
        for k in 0..n {
            let val_a = a[(i * n + k) as usize];
            for j in 0..n {
                c[(i * n + j) as usize] += val_a * b[(k * n + j) as usize];
            }
        }
    }
}

fn trace(arr: &[i32], n: i32) -> i32 {
    let mut acc: i32 = 0;
    for i in 0..n {
        acc += arr[(i * n + i) as usize];
    }
    acc
}

fn main() {
    let n: i32 = 500;
    let size = (n * n) as usize;
    let mut a = vec![0i32; size];
    let mut b = vec![0i32; size];
    let mut c = vec![0i32; size];

    fill_matrix(&mut a, 42);
    fill_matrix(&mut b, 1337);

    mat_mul(&a, &b, &mut c, n);

    println!("Trace Checksum: {}", trace(&c, n));
}

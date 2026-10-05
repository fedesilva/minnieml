fn init_sieve(arr: &mut [i32], mut i: i32, size: i32) {
    while i < size {
        arr[i as usize] = 1;
        i += 1;
    }
}

fn clear_multiples(arr: &mut [i32], factor: i32, mut num: i32, size: i32) {
    while num < size {
        arr[num as usize] = 0;
        num += factor;
    }
}

fn find_next_prime(arr: &[i32], mut i: i32, limit: i32) -> i32 {
    while i <= limit {
        if arr[i as usize] == 1 {
            return i;
        }
        i += 1;
    }
    0
}

fn isqrt(n: i32, mut guess: i32) -> i32 {
    loop {
        let next = (guess + n / guess) / 2;
        if next >= guess {
            return guess;
        }
        guess = next;
    }
}

fn count_primes(arr: &[i32], size: i32) -> i32 {
    let mut count: i32 = 1;
    let mut i: i32 = 0;
    while i < size {
        if arr[i as usize] == 1 {
            count += 1;
        }
        i += 1;
    }
    count
}

fn run_sieve(limit: i32) -> i32 {
    let size = (limit + 1) / 2;
    let mut arr = vec![0i32; size as usize];
    init_sieve(&mut arr, 0, size);
    arr[0] = 0;

    let q = isqrt(limit, limit / 2);

    let mut factor: i32 = 3;
    while factor <= q {
        let next = find_next_prime(&arr, factor / 2, q / 2);
        if next == 0 {
            break;
        }
        let actual_factor = next * 2 + 1;
        let start = actual_factor * actual_factor / 2;
        clear_multiples(&mut arr, actual_factor, start, size);
        factor = actual_factor + 2;
    }

    count_primes(&arr, size)
}

fn main() {
    let count = run_sieve(1000000);
    println!("Primes found: {}", count);
}

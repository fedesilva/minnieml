fn init_sieve(arr: &mut [i64], mut i: i64, size: i64) {
    while i < size {
        arr[i as usize] = 1;
        i += 1;
    }
}

fn clear_multiples(arr: &mut [i64], factor: i64, mut num: i64, size: i64) {
    while num < size {
        arr[num as usize] = 0;
        num += factor;
    }
}

fn find_next_prime(arr: &[i64], mut i: i64, limit: i64) -> i64 {
    while i <= limit {
        if arr[i as usize] == 1 {
            return i;
        }
        i += 1;
    }
    0
}

fn isqrt(n: i64, mut guess: i64) -> i64 {
    loop {
        let next = (guess + n / guess) / 2;
        if next >= guess {
            return guess;
        }
        guess = next;
    }
}

fn count_primes(arr: &[i64], size: i64) -> i64 {
    let mut count: i64 = 1;
    let mut i: i64 = 0;
    while i < size {
        if arr[i as usize] == 1 {
            count += 1;
        }
        i += 1;
    }
    count
}

fn run_sieve(limit: i64) -> i64 {
    let size = (limit + 1) / 2;
    let mut arr = vec![0i64; size as usize];
    init_sieve(&mut arr, 0, size);
    arr[0] = 0;

    let q = isqrt(limit, limit / 2);

    let mut factor: i64 = 3;
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

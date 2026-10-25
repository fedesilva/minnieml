fn egcd_loop(mut r0: i32, mut r1: i32, mut s0: i32, mut s1: i32) -> i32 {
    while r1 != 0 {
        let q = r0 / r1;
        let r2 = r0 - q * r1;
        let s2 = s0 - q * s1;
        r0 = r1;
        r1 = r2;
        s0 = s1;
        s1 = s2;
    }
    s0
}

fn mod_inverse(a: i32, m: i32) -> i32 {
    let x = egcd_loop(a, m, 1, 0);
    if x < 0 {
        x + m
    } else {
        x
    }
}

fn mod_exp(mut base: i32, mut exp: i32, m: i32) -> i32 {
    let mut result = 1;
    while exp != 0 {
        if exp % 2 == 1 {
            result = (result * base) % m;
        }
        base = (base * base) % m;
        exp /= 2;
    }
    result
}

fn rsa_bench(n: i32, p: i32) -> i32 {
    let mut sum = 0;
    for i in 2..n {
        let encrypted = mod_exp(i, 65537, p);
        sum += mod_inverse(encrypted, p);
    }
    sum
}

fn main() {
    let result = rsa_bench(10000, 10007);
    println!("Checksum: {}", result);
}

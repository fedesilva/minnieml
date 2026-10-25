use std::io::{self, BufWriter, Write};

fn fizzbuzz(n: i32, out: &mut impl Write) -> io::Result<()> {
    for i in 1..=n {
        if i % 15 == 0 {
            out.write_all(b"FizzBuzz\n")?;
        } else if i % 3 == 0 {
            out.write_all(b"Fizz\n")?;
        } else if i % 5 == 0 {
            out.write_all(b"Buzz\n")?;
        } else {
            writeln!(out, "{}", i)?;
        }
    }
    Ok(())
}

fn main() -> io::Result<()> {
    let stdout = io::stdout();
    let mut out = BufWriter::with_capacity(4096, stdout.lock());
    fizzbuzz(10000000, &mut out)?;
    out.flush()
}

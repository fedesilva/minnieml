package main

import (
	"bufio"
	"os"
	"strconv"
)

func fizzbuzz(n int, w *bufio.Writer) {
	for i := 1; i <= n; i++ {
		if i%15 == 0 {
			w.WriteString("FizzBuzz\n")
		} else if i%3 == 0 {
			w.WriteString("Fizz\n")
		} else if i%5 == 0 {
			w.WriteString("Buzz\n")
		} else {
			w.WriteString(strconv.Itoa(i))
			w.WriteByte('\n')
		}
	}
}

func main() {
	w := bufio.NewWriter(os.Stdout)
	fizzbuzz(10000000, w)
	w.Flush()
}

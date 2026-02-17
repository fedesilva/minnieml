# MinnieML Lambda syntax design draft


```mml
fn process_and_filter(list) =
  let multiplier = 10;

  list 
    |> map { item -> item * multiplier }
    |> filter { item -> item > 100 }
;
```

```
fn sum(list) =
  // fold [initial_value] [list] [lambda]
  fold 0 list { acc, item -> 
    acc + item 
  }
;
```


```
let add = { a, b -> a + b };
let add5 = add 5;

let result = add5 10; // returns 15
;
```

```
fn process(list) =
  list 
    |> map { x -> x * 2 }
    |> filter { x -> x > 10 }
;
```

```
// The _ helps partially applying any argument, not only the first.
let double_all = map _ { x -> x * 2 };

let result = double_all [1, 2, 3];
;
```


```
// A function that returns a lambda
fn multiply_by(factor) =
  { item -> item * factor }
;

fn main() =
  let triple = multiply_by 3;
  
  [1, 2, 3] |> map triple
;
```

```
list |> map { item ->
  let doubled = item * 2;
  doubled + 1
}
```

```
// trailing lambda + multi-arg call
fold 0 list { acc, x -> acc + x };

// lambda as first-class value
let f = { x -> x + 1 };
map list f;

// nested lambdas
let add = { a -> { b -> a + b } };
(add 5) 10;

// underscore binder vs placeholder
// undecided, might be useful to create fns with a specific arity
//  but where we don't care about a param.
let ignore = { _, x -> x };
map _ ignore; // placeholder outside, binder inside
```



# Simple lang

* Everything is an expression ...
* ... or a let binding
* For now we only have number literals
* Some magical abstractions are assumed like +, -, etc
* For now we don't have infix application so we do `+ 1 2`

```
    // Literal Number 
    1
    // Let Binding
    let a = 1
    let b = 2    
    // Abstraction Binding
    let times a b = + a b
    // Abstraction application
    times 1 2 
    times a b
```

### Missing Temporary Cleanup

Ran: 

❯ mmlc mml/samples/test_temporaries.mml
  ~/Workshop/mine/mml/mml on   memory-prototype !1 ?3 ·················································································································································································································································
❯ leaks --atExit -- ./build/target/testtemporaries
(see leaks output at the end of this file)

The leak count (400) perfectly matches the math for missing "Expression Temporaries."

#### The Leak Math

1.  **Anonymous Arguments (100 leaks)**
    * Code: `consume (to_string n)`
    * **Cause:** `to_string` creates a heap string. `consume` uses it. Because the string is never bound to a variable (like `let s = ...`), your compiler's existing "end-of-scope" cleanup logic never sees it. It becomes an orphan pointer.

2.  **Concat Operands (200 leaks)**
    * Code: `concat (to_string n) (to_string n)`
    * **Cause:** You generate Left (heap string) and Right (heap string). You calculate `Left + Right`.
    * **Leak:** You create the new combined string, but you forget to free `Left` and `Right` afterward. (2 strings × 100 iterations = 200 leaks).

3.  **Nested Concat (100 leaks)**
    * Code: `concat (concat p1 p2) p1`
    * **Cause:** The inner `(concat p1 p2)` creates a new heap string (let's call it `Temp`). You pass `Temp` to the outer concat.
    * **Leak:** After the outer concat finishes, `Temp` is lost.

---

### The Fix: The "Temporary" Stack

You need to handle values that exist *between* statements.

**The Rule:** Whenever an expression generates a managed resource (like a String) that is **not** immediately bound to a `let` variable, the compiler must remember to free it after the enclosing statement finishes.

#### Algorithm Concept (for your CodeGen)

You likely have a `gen_expression` function that returns an `llvm.Value`. You need to augment this.

**1. Add a "Cleanup List" to your CodeGen Context**
When compiling a statement (or a block), initialize a list: `List<llvm.Value> temporaries_to_free`.

**2. When generating an expression:**
If you generate a call (like `to_string` or `concat`) that returns a String:
* Save the result in an LLVM register (e.g., `%tmp1`).
* **Crucial Step:** Check if this expression is being assigned to a variable.
    * **If YES (e.g., `let x = ...`):** Do nothing. The variable `x` takes ownership (and your existing scope logic handles `x`).
    * **If NO (e.g., used as an arg, or operand):** Add `%tmp1` to `temporaries_to_free`.

**3. When the statement finishes:**
Right before you move to the next statement, iterate through `temporaries_to_free` and emit `call void @free(%tmp1)`.

#### Example Walkthrough: `consume (to_string n)`

**Current Behavior (Leaking):**
```llvm
%1 = call i8* @to_string(i64 %n)   ; Allocate
call void @consume(i8* %1)         ; Use
; ... proceed to next stmt ...     ; %1 is lost forever!
```

**Fixed Behavior:**
```llvm
%1 = call i8* @to_string(i64 %n)   ; Allocate
call void @consume(i8* %1)         ; Use

; Compiler inserted cleanup because %1 was an anonymous temporary:
call void @free(i8* %1)
```

#### Example Walkthrough: `concat (to_string a) (to_string b)`

**Fixed Behavior:**
```llvm
; Evaluate Left
%left = call i8* @to_string(...)

; Evaluate Right
%right = call i8* @to_string(...)

; Perform Operation
%result = call i8* @concat(%left, %right)

; Cleanup Operands (The Fix)
call void @free(%left)
call void @free(%right)

; Return result (caller takes ownership of %result)
return %result
```


### Leaks output


❯ leaks --atExit -- ./build/target/testtemporaries
testtemporaries(89995) MallocStackLogging: could not tag MSL-related memory as no_footprint, so those pages will be included in process footprint - (null)
testtemporaries(89995) MallocStackLogging: recording malloc (and VM allocation) stacks using lite mode
Testing Anonymous Arguments...
100
99
98
97
96
95
94
93
92
91
90
89
88
87
86
85
84
83
82
81
80
79
78
77
76
75
74
73
72
71
70
69
68
67
66
65
64
63
62
61
60
59
58
57
56
55
54
53
52
51
50
49
48
47
46
45
44
43
42
41
40
39
38
37
36
35
34
33
32
31
30
29
28
27
26
25
24
23
22
21
20
19
18
17
16
15
14
13
12
11
10
9
8
7
6
5
4
3
2
1
Testing Concat Temporaries (Left/Right operands)...
100100
9999
9898
9797
9696
9595
9494
9393
9292
9191
9090
8989
8888
8787
8686
8585
8484
8383
8282
8181
8080
7979
7878
7777
7676
7575
7474
7373
7272
7171
7070
6969
6868
6767
6666
6565
6464
6363
6262
6161
6060
5959
5858
5757
5656
5555
5454
5353
5252
5151
5050
4949
4848
4747
4646
4545
4444
4343
4242
4141
4040
3939
3838
3737
3636
3535
3434
3333
3232
3131
3030
2929
2828
2727
2626
2525
2424
2323
2222
2121
2020
1919
1818
1717
1616
1515
1414
1313
1212
1111
1010
99
88
77
66
55
44
33
22
11
Testing Nested Concat (Intermediate results)...
100100100
999999
989898
979797
969696
959595
949494
939393
929292
919191
909090
898989
888888
878787
868686
858585
848484
838383
828282
818181
808080
797979
787878
777777
767676
757575
747474
737373
727272
717171
707070
696969
686868
676767
666666
656565
646464
636363
626262
616161
606060
595959
585858
575757
565656
555555
545454
535353
525252
515151
505050
494949
484848
474747
464646
454545
444444
434343
424242
414141
404040
393939
383838
373737
363636
353535
343434
333333
323232
313131
303030
292929
282828
272727
262626
252525
242424
232323
222222
212121
202020
191919
181818
171717
161616
151515
141414
131313
121212
111111
101010
999
888
777
666
555
444
333
222
111
All temporary tests passed!
Process:         testtemporaries [89995]
Path:            /Users/USER/*/testtemporaries
Load Address:    0x103051000
Identifier:      testtemporaries
Version:         0
Code Type:       X86-64
Platform:        macOS
Parent Process:  leaks [89994]
Target Type:     live task

Date/Time:       2026-02-02 11:52:36.193 -0800
Launch Time:     2026-02-02 11:52:35.800 -0800
OS Version:      macOS 15.7.3 (24G419)
Report Version:  7
Analysis Tool:   /usr/bin/leaks

Physical footprint:         1076K
Physical footprint (peak):  1076K
Idle exit:                  untracked
----

leaks Report Version: 4.0, multi-line stacks
Process 89995: 585 nodes malloced for 25 KB
Process 89995: 400 leaks for 6400 total leaked bytes.

STACK OF 100 INSTANCES OF 'ROOT LEAK: <malloc in testtemporaries_test_concat_temps>':
3   testtemporaries                       0x103052676 main + 6
2   testtemporaries                       0x1030525b8 testtemporaries_main + 392
1   testtemporaries                       0x103051af0 testtemporaries_test_concat_temps + 96
0   libsystem_malloc.dylib             0x7ff804f879d9 _malloc_zone_malloc_instrumented_or_legacy + 115
====
    100 (1.56K) << TOTAL >>
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004760> [16]  length: 3  "100"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004780> [16]  length: 2  "99"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180047a0> [16]  length: 2  "98"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180047c0> [16]  length: 2  "97"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180047e0> [16]  length: 2  "96"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004800> [16]  length: 2  "95"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004820> [16]  length: 2  "94"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004840> [16]  length: 2  "93"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004860> [16]  length: 2  "92"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004880> [16]  length: 2  "91"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180048a0> [16]  length: 2  "90"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180048c0> [16]  length: 2  "89"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180048e0> [16]  length: 2  "88"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004900> [16]  length: 2  "87"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004920> [16]  length: 2  "86"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004940> [16]  length: 2  "85"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004960> [16]  length: 2  "84"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004980> [16]  length: 2  "83"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180049a0> [16]  length: 2  "82"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180049c0> [16]  length: 2  "81"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180049e0> [16]  length: 2  "80"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a00> [16]  length: 2  "79"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a20> [16]  length: 2  "78"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a40> [16]  length: 2  "77"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a60> [16]  length: 2  "76"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a80> [16]  length: 2  "75"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004aa0> [16]  length: 2  "74"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ac0> [16]  length: 2  "73"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ae0> [16]  length: 2  "72"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b00> [16]  length: 2  "71"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b20> [16]  length: 2  "70"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b40> [16]  length: 2  "69"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b60> [16]  length: 2  "68"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b80> [16]  length: 2  "67"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ba0> [16]  length: 2  "66"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004bc0> [16]  length: 2  "65"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004be0> [16]  length: 2  "64"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c00> [16]  length: 2  "63"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c20> [16]  length: 2  "62"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c40> [16]  length: 2  "61"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c60> [16]  length: 2  "60"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c80> [16]  length: 2  "59"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ca0> [16]  length: 2  "58"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004cc0> [16]  length: 2  "57"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ce0> [16]  length: 2  "56"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d00> [16]  length: 2  "55"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d20> [16]  length: 2  "54"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d40> [16]  length: 2  "53"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d60> [16]  length: 2  "52"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d80> [16]  length: 2  "51"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004da0> [16]  length: 2  "50"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004dc0> [16]  length: 2  "49"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004de0> [16]  length: 2  "48"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e00> [16]  length: 2  "47"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e20> [16]  length: 2  "46"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e40> [16]  length: 2  "45"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e60> [16]  length: 2  "44"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e80> [16]  length: 2  "43"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ea0> [16]  length: 2  "42"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ec0> [16]  length: 2  "41"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ee0> [16]  length: 2  "40"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f00> [16]  length: 2  "39"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f20> [16]  length: 2  "38"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f40> [16]  length: 2  "37"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f60> [16]  length: 2  "36"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f80> [16]  length: 2  "35"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004fa0> [16]  length: 2  "34"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004fc0> [16]  length: 2  "33"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004fe0> [16]  length: 2  "32"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005000> [16]  length: 2  "31"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005020> [16]  length: 2  "30"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005040> [16]  length: 2  "29"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005060> [16]  length: 2  "28"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005080> [16]  length: 2  "27"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180050a0> [16]  length: 2  "26"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180050c0> [16]  length: 2  "25"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180050e0> [16]  length: 2  "24"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005100> [16]  length: 2  "23"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005120> [16]  length: 2  "22"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005140> [16]  length: 2  "21"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005160> [16]  length: 2  "20"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005180> [16]  length: 2  "19"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180051a0> [16]  length: 2  "18"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180051c0> [16]  length: 2  "17"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180051e0> [16]  length: 2  "16"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005200> [16]  length: 2  "15"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005220> [16]  length: 2  "14"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005240> [16]  length: 2  "13"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005260> [16]  length: 2  "12"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005280> [16]  length: 2  "11"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180052a0> [16]  length: 2  "10"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180052c0> [16]  length: 1  "9"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180052e0> [16]  length: 1  "8"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005300> [16]  length: 1  "7"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005320> [16]  length: 1  "6"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005340> [16]  length: 1  "5"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005360> [16]  length: 1  "4"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005380> [16]  length: 1  "3"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180053a0> [16]  length: 1  "2"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180053c0> [16]  length: 1  "1"

STACK OF 100 INSTANCES OF 'ROOT LEAK: <malloc in testtemporaries_main>':
2   testtemporaries                       0x103052676 main + 6
1   testtemporaries                       0x10305250b testtemporaries_main + 219
0   libsystem_malloc.dylib             0x7ff804f879d9 _malloc_zone_malloc_instrumented_or_legacy + 115
====
    100 (1.56K) << TOTAL >>
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004120> [16]  length: 3  "100"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004130> [16]  length: 2  "99"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004140> [16]  length: 2  "98"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004150> [16]  length: 2  "97"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004160> [16]  length: 2  "96"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004170> [16]  length: 2  "95"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004180> [16]  length: 2  "94"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004190> [16]  length: 2  "93"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180041a0> [16]  length: 2  "92"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180041b0> [16]  length: 2  "91"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180041c0> [16]  length: 2  "90"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180041d0> [16]  length: 2  "89"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180041e0> [16]  length: 2  "88"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180041f0> [16]  length: 2  "87"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004200> [16]  length: 2  "86"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004210> [16]  length: 2  "85"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004220> [16]  length: 2  "84"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004230> [16]  length: 2  "83"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004240> [16]  length: 2  "82"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004250> [16]  length: 2  "81"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004260> [16]  length: 2  "80"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004270> [16]  length: 2  "79"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004280> [16]  length: 2  "78"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004290> [16]  length: 2  "77"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180042a0> [16]  length: 2  "76"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180042b0> [16]  length: 2  "75"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180042c0> [16]  length: 2  "74"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180042d0> [16]  length: 2  "73"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180042e0> [16]  length: 2  "72"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180042f0> [16]  length: 2  "71"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004300> [16]  length: 2  "70"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004310> [16]  length: 2  "69"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004320> [16]  length: 2  "68"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004330> [16]  length: 2  "67"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004340> [16]  length: 2  "66"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004350> [16]  length: 2  "65"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004360> [16]  length: 2  "64"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004370> [16]  length: 2  "63"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004380> [16]  length: 2  "62"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004390> [16]  length: 2  "61"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180043a0> [16]  length: 2  "60"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180043b0> [16]  length: 2  "59"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180043c0> [16]  length: 2  "58"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180043d0> [16]  length: 2  "57"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180043e0> [16]  length: 2  "56"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180043f0> [16]  length: 2  "55"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004400> [16]  length: 2  "54"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004410> [16]  length: 2  "53"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004420> [16]  length: 2  "52"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004430> [16]  length: 2  "51"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004440> [16]  length: 2  "50"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004450> [16]  length: 2  "49"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004460> [16]  length: 2  "48"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004470> [16]  length: 2  "47"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004480> [16]  length: 2  "46"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004490> [16]  length: 2  "45"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180044a0> [16]  length: 2  "44"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180044b0> [16]  length: 2  "43"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180044c0> [16]  length: 2  "42"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180044d0> [16]  length: 2  "41"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180044e0> [16]  length: 2  "40"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180044f0> [16]  length: 2  "39"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004500> [16]  length: 2  "38"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004510> [16]  length: 2  "37"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004520> [16]  length: 2  "36"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004530> [16]  length: 2  "35"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004540> [16]  length: 2  "34"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004550> [16]  length: 2  "33"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004560> [16]  length: 2  "32"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004570> [16]  length: 2  "31"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004580> [16]  length: 2  "30"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004590> [16]  length: 2  "29"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180045a0> [16]  length: 2  "28"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180045b0> [16]  length: 2  "27"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180045c0> [16]  length: 2  "26"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180045d0> [16]  length: 2  "25"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180045e0> [16]  length: 2  "24"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180045f0> [16]  length: 2  "23"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004600> [16]  length: 2  "22"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004610> [16]  length: 2  "21"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004620> [16]  length: 2  "20"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004630> [16]  length: 2  "19"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004640> [16]  length: 2  "18"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004650> [16]  length: 2  "17"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004660> [16]  length: 2  "16"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004670> [16]  length: 2  "15"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004680> [16]  length: 2  "14"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004690> [16]  length: 2  "13"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180046a0> [16]  length: 2  "12"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180046b0> [16]  length: 2  "11"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180046c0> [16]  length: 2  "10"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180046d0> [16]  length: 1  "9"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180046e0> [16]  length: 1  "8"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad180046f0> [16]  length: 1  "7"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004700> [16]  length: 1  "6"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004710> [16]  length: 1  "5"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004720> [16]  length: 1  "4"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004730> [16]  length: 1  "3"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004740> [16]  length: 1  "2"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_main 0x7fad18004750> [16]  length: 1  "1"

STACK OF 100 INSTANCES OF 'ROOT LEAK: <malloc in testtemporaries_test_concat_temps>':
3   testtemporaries                       0x103052676 main + 6
2   testtemporaries                       0x1030525b8 testtemporaries_main + 392
1   testtemporaries                       0x103051bb5 testtemporaries_test_concat_temps + 293
0   libsystem_malloc.dylib             0x7ff804f879d9 _malloc_zone_malloc_instrumented_or_legacy + 115
====
    100 (1.56K) << TOTAL >>
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004770> [16]  length: 3  "100"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004790> [16]  length: 2  "99"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180047b0> [16]  length: 2  "98"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180047d0> [16]  length: 2  "97"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180047f0> [16]  length: 2  "96"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004810> [16]  length: 2  "95"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004830> [16]  length: 2  "94"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004850> [16]  length: 2  "93"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004870> [16]  length: 2  "92"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004890> [16]  length: 2  "91"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180048b0> [16]  length: 2  "90"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180048d0> [16]  length: 2  "89"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180048f0> [16]  length: 2  "88"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004910> [16]  length: 2  "87"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004930> [16]  length: 2  "86"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004950> [16]  length: 2  "85"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004970> [16]  length: 2  "84"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004990> [16]  length: 2  "83"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180049b0> [16]  length: 2  "82"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180049d0> [16]  length: 2  "81"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180049f0> [16]  length: 2  "80"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a10> [16]  length: 2  "79"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a30> [16]  length: 2  "78"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a50> [16]  length: 2  "77"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a70> [16]  length: 2  "76"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004a90> [16]  length: 2  "75"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ab0> [16]  length: 2  "74"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ad0> [16]  length: 2  "73"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004af0> [16]  length: 2  "72"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b10> [16]  length: 2  "71"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b30> [16]  length: 2  "70"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b50> [16]  length: 2  "69"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b70> [16]  length: 2  "68"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004b90> [16]  length: 2  "67"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004bb0> [16]  length: 2  "66"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004bd0> [16]  length: 2  "65"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004bf0> [16]  length: 2  "64"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c10> [16]  length: 2  "63"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c30> [16]  length: 2  "62"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c50> [16]  length: 2  "61"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c70> [16]  length: 2  "60"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004c90> [16]  length: 2  "59"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004cb0> [16]  length: 2  "58"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004cd0> [16]  length: 2  "57"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004cf0> [16]  length: 2  "56"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d10> [16]  length: 2  "55"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d30> [16]  length: 2  "54"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d50> [16]  length: 2  "53"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d70> [16]  length: 2  "52"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004d90> [16]  length: 2  "51"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004db0> [16]  length: 2  "50"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004dd0> [16]  length: 2  "49"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004df0> [16]  length: 2  "48"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e10> [16]  length: 2  "47"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e30> [16]  length: 2  "46"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e50> [16]  length: 2  "45"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e70> [16]  length: 2  "44"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004e90> [16]  length: 2  "43"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004eb0> [16]  length: 2  "42"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ed0> [16]  length: 2  "41"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ef0> [16]  length: 2  "40"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f10> [16]  length: 2  "39"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f30> [16]  length: 2  "38"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f50> [16]  length: 2  "37"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f70> [16]  length: 2  "36"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004f90> [16]  length: 2  "35"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004fb0> [16]  length: 2  "34"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004fd0> [16]  length: 2  "33"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18004ff0> [16]  length: 2  "32"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005010> [16]  length: 2  "31"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005030> [16]  length: 2  "30"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005050> [16]  length: 2  "29"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005070> [16]  length: 2  "28"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005090> [16]  length: 2  "27"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180050b0> [16]  length: 2  "26"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180050d0> [16]  length: 2  "25"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180050f0> [16]  length: 2  "24"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005110> [16]  length: 2  "23"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005130> [16]  length: 2  "22"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005150> [16]  length: 2  "21"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005170> [16]  length: 2  "20"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005190> [16]  length: 2  "19"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180051b0> [16]  length: 2  "18"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180051d0> [16]  length: 2  "17"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180051f0> [16]  length: 2  "16"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005210> [16]  length: 2  "15"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005230> [16]  length: 2  "14"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005250> [16]  length: 2  "13"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005270> [16]  length: 2  "12"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005290> [16]  length: 2  "11"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180052b0> [16]  length: 2  "10"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180052d0> [16]  length: 1  "9"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180052f0> [16]  length: 1  "8"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005310> [16]  length: 1  "7"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005330> [16]  length: 1  "6"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005350> [16]  length: 1  "5"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005370> [16]  length: 1  "4"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad18005390> [16]  length: 1  "3"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180053b0> [16]  length: 1  "2"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_concat_temps 0x7fad180053d0> [16]  length: 1  "1"

STACK OF 100 INSTANCES OF 'ROOT LEAK: <malloc in testtemporaries_test_nested_concat>':
3   testtemporaries                       0x103052676 main + 6
2   testtemporaries                       0x103052618 testtemporaries_main + 488
1   testtemporaries                       0x10305200e testtemporaries_test_nested_concat + 590
0   libsystem_malloc.dylib             0x7ff804f879d9 _malloc_zone_malloc_instrumented_or_legacy + 115
====
    100 (1.56K) << TOTAL >>
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180053f0> [16]  length: 4  "9898"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005400> [16]  length: 6  "100100"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005410> [16]  length: 4  "9696"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005420> [16]  length: 4  "9595"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005430> [16]  length: 4  "9999"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005440> [16]  length: 4  "9797"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005450> [16]  length: 4  "9494"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005460> [16]  length: 4  "9393"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005470> [16]  length: 4  "9292"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005480> [16]  length: 4  "9191"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005490> [16]  length: 4  "9090"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180054a0> [16]  length: 4  "8989"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180054b0> [16]  length: 4  "8888"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180054c0> [16]  length: 4  "8787"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180054d0> [16]  length: 4  "8686"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180054e0> [16]  length: 4  "8585"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180054f0> [16]  length: 4  "8484"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005500> [16]  length: 4  "8383"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005510> [16]  length: 4  "8282"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005520> [16]  length: 4  "8181"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005530> [16]  length: 4  "8080"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005540> [16]  length: 4  "7979"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005550> [16]  length: 4  "7878"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005560> [16]  length: 4  "7777"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005570> [16]  length: 4  "7676"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005580> [16]  length: 4  "7575"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005590> [16]  length: 4  "7474"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180055a0> [16]  length: 4  "7373"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180055b0> [16]  length: 4  "7272"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180055c0> [16]  length: 4  "7171"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180055d0> [16]  length: 4  "7070"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180055e0> [16]  length: 4  "6969"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180055f0> [16]  length: 4  "6868"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005600> [16]  length: 4  "6767"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005610> [16]  length: 4  "6666"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005620> [16]  length: 4  "6565"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005630> [16]  length: 4  "6464"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005640> [16]  length: 4  "6363"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005650> [16]  length: 4  "6262"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005660> [16]  length: 4  "6161"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005670> [16]  length: 4  "6060"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005680> [16]  length: 4  "5959"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005690> [16]  length: 4  "5858"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180056a0> [16]  length: 4  "5757"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180056b0> [16]  length: 4  "5656"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180056c0> [16]  length: 4  "5555"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180056d0> [16]  length: 4  "5454"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180056e0> [16]  length: 4  "5353"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180056f0> [16]  length: 4  "5252"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005700> [16]  length: 4  "5151"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005710> [16]  length: 4  "5050"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005720> [16]  length: 4  "4949"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005730> [16]  length: 4  "4848"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005740> [16]  length: 4  "4747"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005750> [16]  length: 4  "4646"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005760> [16]  length: 4  "4545"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005770> [16]  length: 4  "4444"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005780> [16]  length: 4  "4343"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005790> [16]  length: 4  "4242"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180057a0> [16]  length: 4  "4141"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180057b0> [16]  length: 4  "4040"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180057c0> [16]  length: 4  "3939"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180057d0> [16]  length: 4  "3838"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180057e0> [16]  length: 4  "3737"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180057f0> [16]  length: 4  "3636"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005800> [16]  length: 4  "3535"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005810> [16]  length: 4  "3434"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005820> [16]  length: 4  "3333"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005830> [16]  length: 4  "3232"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005840> [16]  length: 4  "3131"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005850> [16]  length: 4  "3030"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005860> [16]  length: 4  "2929"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005870> [16]  length: 4  "2828"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005880> [16]  length: 4  "2727"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005890> [16]  length: 4  "2626"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180058a0> [16]  length: 4  "2525"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180058b0> [16]  length: 4  "2424"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180058c0> [16]  length: 4  "2323"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180058d0> [16]  length: 4  "2222"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180058e0> [16]  length: 4  "2121"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180058f0> [16]  length: 4  "2020"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005900> [16]  length: 4  "1919"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005910> [16]  length: 4  "1818"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005920> [16]  length: 4  "1717"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005930> [16]  length: 4  "1616"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005940> [16]  length: 4  "1515"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005950> [16]  length: 4  "1414"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005960> [16]  length: 4  "1313"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005970> [16]  length: 4  "1212"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005980> [16]  length: 4  "1111"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005990> [16]  length: 4  "1010"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180059a0> [16]  length: 2  "99"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180059b0> [16]  length: 2  "88"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180059c0> [16]  length: 2  "77"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180059d0> [16]  length: 2  "66"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180059e0> [16]  length: 2  "55"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad180059f0> [16]  length: 2  "44"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005a00> [16]  length: 2  "33"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005a10> [16]  length: 2  "22"
      1 (16 bytes) ROOT LEAK: <malloc in testtemporaries_test_nested_concat 0x7fad18005a20> [16]  length: 2  "11"



Binary Images:
       0x103051000 -        0x10305415b +testtemporaries (0) <95E4C1BE-9256-3BAD-86EE-2A290027541E> /Users/*/testtemporaries
       0x103076000 -        0x103076947  libLeaksAtExit.dylib (64570.34.1) <A1DEDB9B-B434-3380-98F0-5A2279067BFA> /usr/lib/libLeaksAtExit.dylib
    0x7ff804d5a000 -     0x7ff804d9f05b  libobjc.A.dylib (940.4) <087DB838-675B-3BC5-B554-DAD42196C77A> /usr/lib/libobjc.A.dylib
    0x7ff804da0000 -     0x7ff804e3a6c7  dyld (1.0.0 - 1286.10) <1A622D74-549F-3F53-BD70-412BD11345E8> /usr/lib/dyld
    0x7ff804e3b000 -     0x7ff804e3e6d8  libsystem_blocks.dylib (96) <17F8CB93-59F9-324C-93ED-A5A03A0A7C58> /usr/lib/system/libsystem_blocks.dylib
    0x7ff804e3f000 -     0x7ff804e85bd5  libxpc.dylib (2894.140.12.700.1) <C2D833C5-89AF-3DAE-AE71-AF61769CAB0C> /usr/lib/system/libxpc.dylib
    0x7ff804e86000 -     0x7ff804ea0805  libsystem_trace.dylib (1643.120.5) <8775DCD9-CE39-3A08-9631-0A4C93E335D9> /usr/lib/system/libsystem_trace.dylib
    0x7ff804ea1000 -     0x7ff804f5314f  libcorecrypto.dylib (1736.140.2) <55A104DF-190B-30D9-9481-016D3497FBA2> /usr/lib/system/libcorecrypto.dylib
    0x7ff804f54000 -     0x7ff804f9e89f  libsystem_malloc.dylib (715.140.5) <B26A86B5-A181-367C-89EA-D1ED5545EF77> /usr/lib/system/libsystem_malloc.dylib
    0x7ff804f9f000 -     0x7ff804fe6339  libdispatch.dylib (1521.140.2) <6CA6112A-70C8-39A2-8CFA-DA2139709D3C> /usr/lib/system/libdispatch.dylib
    0x7ff804fe7000 -     0x7ff804fe9b0f  libsystem_featureflags.dylib (97) <E0A31BA9-C76D-3218-8B70-5D6B4028082B> /usr/lib/system/libsystem_featureflags.dylib
    0x7ff804fea000 -     0x7ff805072b27  libsystem_c.dylib (1698.140.3.700.2) <0251DD0C-67BC-3A58-A477-5E34D4D54F3E> /usr/lib/system/libsystem_c.dylib
    0x7ff805073000 -     0x7ff8050f1fff  libc++.1.dylib (1900.181) <1BA8415D-93C3-301F-80AA-C2D396C32DD7> /usr/lib/libc++.1.dylib
    0x7ff8050f2000 -     0x7ff805108fff  libc++abi.dylib (1900.181) <67A06E4F-032A-344F-8F4F-D77A2B8C7EEA> /usr/lib/libc++abi.dylib
    0x7ff805109000 -     0x7ff805145b6f  libsystem_kernel.dylib (11417.140.69.705.2) <DF27EB28-275E-3046-A421-655EA736BE57> /usr/lib/system/libsystem_kernel.dylib
    0x7ff805146000 -     0x7ff805151fd7  libsystem_pthread.dylib (536.0.0.701.1) <A031F066-4DAA-3801-9155-88106D874835> /usr/lib/system/libsystem_pthread.dylib
    0x7ff805152000 -     0x7ff8051807c7  libdyld.dylib (1286.10) <C68A8513-2993-3F6F-A271-4074E26FF392> /usr/lib/system/libdyld.dylib
    0x7ff805181000 -     0x7ff80518a93f  libsystem_platform.dylib (349.140.6) <78C9DCC0-2FBA-3BE4-A1DD-DA618362AE68> /usr/lib/system/libsystem_platform.dylib
    0x7ff80518b000 -     0x7ff8051b9477  libsystem_info.dylib (597.0.0.701.2) <CD7C41C5-CE28-3129-9B0E-B8D126B2A0C5> /usr/lib/system/libsystem_info.dylib
    0x7ff8087af000 -     0x7ff8087b971f  libsystem_darwin.dylib (1698.140.3.700.2) <96D36E47-C1BD-3DA0-BAF4-FAD8DF276A08> /usr/lib/system/libsystem_darwin.dylib
    0x7ff808bec000 -     0x7ff808bfd0db  libsystem_notify.dylib (342) <4244B485-A96B-350F-94BE-4DFFE7C97F93> /usr/lib/system/libsystem_notify.dylib
    0x7ff80acef000 -     0x7ff80ad0836f  libsystem_networkextension.dylib (2063.140.6) <C4D49367-9833-30AD-95FF-C6F28811A6EA> /usr/lib/system/libsystem_networkextension.dylib
    0x7ff80ad7f000 -     0x7ff80ad9613b  libsystem_asl.dylib (402) <C9CEFBC7-AB24-3D71-8CC3-4F6C103ACD63> /usr/lib/system/libsystem_asl.dylib
    0x7ff80cb2f000 -     0x7ff80cb36c57  libsystem_symptoms.dylib (2022.140.4) <5058A6B6-664F-38C7-B224-FF58D757CCA1> /usr/lib/system/libsystem_symptoms.dylib
    0x7ff81056a000 -     0x7ff810598a33  libsystem_containermanager.dylib (689.100.6.700.1) <58031C10-98CA-3CB1-82B5-52172EB003CF> /usr/lib/system/libsystem_containermanager.dylib
    0x7ff81181a000 -     0x7ff81181d8df  libsystem_configuration.dylib (1351.140.5.700.1) <C9A0E3A0-375D-3D16-81EB-C5F9B4789F22> /usr/lib/system/libsystem_configuration.dylib
    0x7ff81181e000 -     0x7ff811824697  libsystem_sandbox.dylib (2401.140.15.700.3) <23238A76-3554-3EF9-93A5-D2DE566C6CAC> /usr/lib/system/libsystem_sandbox.dylib
    0x7ff812950000 -     0x7ff812952bfb  libquarantine.dylib (181.120.2) <5C677B12-B313-3281-A65F-16FE27EDFB8B> /usr/lib/system/libquarantine.dylib
    0x7ff8131e6000 -     0x7ff8131ec8df  libsystem_coreservices.dylib (178.6.6) <3C717B49-880D-3348-83AF-44A3E4D2991B> /usr/lib/system/libsystem_coreservices.dylib
    0x7ff8135b1000 -     0x7ff81361e43f  libsystem_m.dylib (3291.100.4) <C9D04FC0-05AA-3F79-B684-E1429049BF9B> /usr/lib/system/libsystem_m.dylib
    0x7ff813620000 -     0x7ff8136235c7  libmacho.dylib (1024.3) <DC79A644-E79E-3F74-AF02-E4EC6970ECA3> /usr/lib/system/libmacho.dylib
    0x7ff81363b000 -     0x7ff81364733f  libcommonCrypto.dylib (600035) <4DC97572-8957-3E35-91D6-B871A2D97B18> /usr/lib/system/libcommonCrypto.dylib
    0x7ff813648000 -     0x7ff8136506ef  libunwind.dylib (1900.125) <BC907228-2952-3EE1-98FA-55191C0B9DA7> /usr/lib/system/libunwind.dylib
    0x7ff813659000 -     0x7ff8136644af  libcopyfile.dylib (224) <5887775F-4C7E-35EF-B0E3-604B313D46F3> /usr/lib/system/libcopyfile.dylib
    0x7ff813665000 -     0x7ff81366c9cf  libcompiler_rt.dylib (103.3) <D2ED00EF-50D8-3C85-9F87-657F2F40C72E> /usr/lib/system/libcompiler_rt.dylib
    0x7ff81366d000 -     0x7ff81367176b  libsystem_collections.dylib (1698.140.3.700.2) <FBC61848-54B1-3855-A8D4-870EDD772AFE> /usr/lib/system/libsystem_collections.dylib
    0x7ff813672000 -     0x7ff813674ef7  libsystem_secinit.dylib (153.140.2) <44080C2A-259C-3E2E-B260-BBFAA381A9CB> /usr/lib/system/libsystem_secinit.dylib
    0x7ff813675000 -     0x7ff813677727  libremovefile.dylib (81) <B97DBF02-2EA5-36FC-8891-424EC4D8BA67> /usr/lib/system/libremovefile.dylib
    0x7ff813678000 -     0x7ff813678b47  libkeymgr.dylib (31) <D324A0E6-D224-35C7-87CD-D5DAF27170D5> /usr/lib/system/libkeymgr.dylib
    0x7ff813679000 -     0x7ff813681557  libsystem_dnssd.dylib (2600.140.3) <726339C0-DE67-327E-825A-89F33EEAB679> /usr/lib/system/libsystem_dnssd.dylib
    0x7ff813682000 -     0x7ff813687003  libcache.dylib (95) <AD3F4A9E-4CF4-33CC-9D6B-093E3B4BAC01> /usr/lib/system/libcache.dylib
    0x7ff813688000 -     0x7ff813689a8b  libSystem.B.dylib (1351) <38338C6F-32BA-3913-99EF-3276EB69F2B4> /usr/lib/libSystem.B.dylib
    0x7ff90da20000 -     0x7ff90da2c11f  com.apple.MallocStackLogging (1.0 - 64570.58.1) <88BD90BE-8F0A-3C64-90B5-0347B7E130AD> /System/Library/PrivateFrameworks/MallocStackLogging.framework/Versions/A/MallocStackLogging
    0x7ffd12f5a000 -     0x7ffd12f61707  libRosetta.dylib (349) <A2D5AB2A-02FB-3E33-8433-D16A5CF209CA> /usr/lib/libRosetta.dylib
    0x7ffd14500000 -     0x7ffd14504023  libsystem_darwindirectory.dylib (122) <F4366BE9-16AB-31D4-BE97-084DFDB6E361> /usr/lib/system/libsystem_darwindirectory.dylib
    0x7ffd14505000 -     0x7ffd1450c323  libsystem_eligibility.dylib (181.140.5) <42555080-4741-388A-B027-929D18CFB59F> /usr/lib/system/libsystem_eligibility.dylib
    0x7ffd1450d000 -     0x7ffd1451227f  libsystem_sanitizers.dylib (19) <BAD83526-183F-36D0-8A0A-FFCE14D32BB6> /usr/lib/system/libsystem_sanitizers.dylib
  ~/Workshop/mine/mml/mml on   memory-prototype !1 ?3 ·················································································································································································································································
❯


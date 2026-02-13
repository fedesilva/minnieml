# No-`end` Syntax Proposal

## Goal

Remove `end` from expression syntax.
Use semicolons only:

- `;` terminates the current expression/parser frame.
- Recursive parsers consume one `;` each as they return.

## Core Rules

1. Every expression must end with `;`.
2. Open blocks are introduced by:
- function body (`fn ... =`)
- `then` branch
- `else` branch
3. `if` has no `end`.
4. `else` is valid only after a completed `then` branch of the same `if`.
5. Top-level declarations still end with `;`.
6. There is no semicolon counting rule. Multiple `;` are consumed one-by-one by recursive
   parser frames as control returns to outer contexts.

## Canonical Forms

### Function with expression sequence

```mml
fn summat() =
  let a = 1;
  let b = 2;
  a + b;
;
```

### `if/else` expression

```mml
fn abs(x: Int): Int =
  if x < 0 then
    0 - x;
  else
    x;
  ;
;
```

### Single-branch `if`

```mml
fn print_if_pos(x: Int): Unit =
  if x > 0 then
    println "positive";
  ;
  println "done";
;
```

### Nested `if`

```mml
fn classify(n: Int): Int =
  if n > 0 then
    if n > 10 then
      2;
    else
      1;
    ;
  else
    0;
  ;
;
```

## Parsing Contract

1. Parse `Expr`.
2. Require one terminating `;` for that parser frame.
3. Return to caller after consuming that `;`.
4. Caller may then consume its own terminating `;`.
5. When a `then` branch parser returns, parser expects either:
- `else` for the same `if`, or
- branch omission policy (if allowed by grammar).
6. When an `else` branch parser returns, its `if` parser consumes its terminator and returns.

## Error Rules

1. Missing semicolon after expression:
- `expected ';' after expression`
2. `else` without matching open `if`:
- `unexpected 'else'`
3. Block under-close at EOF:
- `unclosed block: expected ';'`
4. Extra semicolon at top-level:
- `unexpected ';'`

## Valid Examples

```mml
fn f() = 1;
;

fn g(a: Int): Unit =
  if a > 1 then
    println "gt1";
  else
    println "le1";
  ;
  println "done";
;

fn h(n: Int): Int =
  if n == 0 then
    0;
  else
    n + 1;
  ;
;
```

## Invalid Examples

```mml
fn bad1() =
  let a = 1
  a + 1;
;
// invalid: missing ';' after let expression
```

```mml
fn bad2(a: Int): Int =
  else
    a;
;
// invalid: unexpected else
```

```mml
fn bad3(): Int =
  if true then
    1;
  else
    2;
;
// invalid: if parser frame did not consume its own terminator
```

```mml
fn bad4(): Int = 1;
;;

// invalid: extra ';' after declaration close
```

## Rewritten Examples

### nqueens (no `end`)

```mml
// N-Queens solver using backtracking
// Board represented as array where board[row] = column of queen in that row

fn abs(x: Int): Int =
  if x < 0 then
    0 - x;
  else
    x;
  ;
;

// Check if placing queen at (row, col) is safe
fn is_safe_loop(board: IntArray, row: Int, col: Int, check_row: Int): Bool =
  if check_row >= row then
    true;
  else
    let queen_col = unsafe_ar_int_get board check_row;
    let same_col = queen_col == col;
    let row_diff = abs (row - check_row);
    let col_diff = abs (col - queen_col);
    let same_diag = row_diff == col_diff;

    if same_col then
      false;
    else
      if same_diag then
        false;
      else
        is_safe_loop board row col (check_row + 1);
      ;
    ;
  ;
;

fn is_safe(board: IntArray, row: Int, col: Int): Bool =
  is_safe_loop board row col 0;
;

// Try to place queens starting from given row
fn solve_loop(board: IntArray, row: Int, n: Int, col: Int, count: Int): Int =
  if col >= n then
    count;
  else
    let safe = is_safe board row col;
    if safe then
      unsafe_ar_int_set board row col;
      let new_count = if row == (n - 1) then
        count + 1;
      else
        count + (solve board (row + 1) n 0);
      ;
      solve_loop board row n (col + 1) new_count;
    else
      solve_loop board row n (col + 1) count;
    ;
  ;
;

fn solve(board: IntArray, row: Int, n: Int, count: Int): Int =
  if row >= n then
    count;
  else
    solve_loop board row n 0 count;
  ;
;

fn main(): Unit =
  let n = 12;
  let board = ar_int_new n;

  let solutions = solve board 0 n 0;
  println ("Solutions for " ++ (int_to_str n) ++ "-queens: " ++ (int_to_str solutions));
;
```

### astar (no `end`)

```mml
// astar.mml

struct MinHeap {
  indices: IntArray,
  scores:  IntArray,
  capacity: Int
};

fn heap_new (cap: Int): MinHeap =
  MinHeap
    (ar_int_new cap)
    (ar_int_new cap)
    cap
;

struct PopResult {
  size: Int,
  idx: Int
};

fn heap_swap (h: MinHeap, i: Int, j: Int): Unit =
  let idx_i = unsafe_ar_int_get (h.indices) i;
  let scr_i = unsafe_ar_int_get (h.scores) i;
  let idx_j = unsafe_ar_int_get (h.indices) j;
  let scr_j = unsafe_ar_int_get (h.scores) j;

  unsafe_ar_int_set (h.indices) i idx_j;
  unsafe_ar_int_set (h.scores) i scr_j;
  unsafe_ar_int_set (h.indices) j idx_i;
  unsafe_ar_int_set (h.scores) j scr_i
;

fn sift_up (h: MinHeap, idx: Int): Unit =
  if idx > 0 then
    let parent = (idx - 1) / 2;
    let p_score = unsafe_ar_int_get (h.scores) parent;
    let c_score = unsafe_ar_int_get (h.scores) idx;

    if c_score < p_score then
      heap_swap h idx parent;
      sift_up h parent;
    ;
  ;
;

fn sift_down (h: MinHeap, size: Int, idx: Int): Unit =
  let left = 2 * idx + 1;
  let right = 2 * idx + 2;

  let s_score = unsafe_ar_int_get (h.scores) idx;

  let smallest =
    if left < size then
      if (unsafe_ar_int_get (h.scores) left) < s_score then
        left;
      else
        idx;
      ;
    else
      idx;
    ;

  let smallest =
    if right < size then
      if (unsafe_ar_int_get (h.scores) right) < (unsafe_ar_int_get (h.scores) smallest) then
        right;
      else
        smallest;
      ;
    else
      smallest;
    ;

  if smallest != idx then
    heap_swap h idx smallest;
    sift_down h size smallest;
  ;
;

fn heap_push (h: MinHeap, size: Int, grid_idx: Int, f_score: Int): Int =
  unsafe_ar_int_set (h.indices) size grid_idx;
  unsafe_ar_int_set (h.scores) size f_score;
  sift_up h size;
  size + 1
;

fn heap_pop (h: MinHeap, size: Int): PopResult =
  if size == 0 then
    PopResult 0 (0 - 1);
  else
    let root = unsafe_ar_int_get (h.indices) 0;
    let last = size - 1;

    let last_idx = unsafe_ar_int_get (h.indices) last;
    let last_scr = unsafe_ar_int_get (h.scores) last;

    unsafe_ar_int_set (h.indices) 0 last_idx;
    unsafe_ar_int_set (h.scores) 0 last_scr;

    sift_down h last 0;
    PopResult last root;
  ;
;

fn abs (x: Int): Int =
  if x < 0 then
    0 - x;
  else
    x;
  ;
;

fn heuristic (idx_a: Int, idx_b: Int, width: Int): Int =
  let x1 = idx_a % width;
  let y1 = idx_a / width;
  let x2 = idx_b % width;
  let y2 = idx_b / width;
  (abs (x1 - x2)) + (abs (y1 - y2))
;

fn init_g (g_score: IntArray, inf: Int, total: Int, i: Int): Unit =
  if i < total then
    unsafe_ar_int_set g_score i inf;
    init_g g_score inf total (i + 1);
  ;
;

fn visit_neighbors (
  open_set: MinHeap,
  g_score: IntArray,
  walls: IntArray,
  goal_idx: Int,
  width: Int,
  height: Int,
  current: Int,
  cx: Int,
  cy: Int,
  dir: Int,
  heap_sz: Int
): Int =
  if dir == 4 then
    solve open_set g_score walls goal_idx width height heap_sz;
  else
    let neighbor_idx =
      if dir == 0 then
        current + 1;
      elif dir == 1 then
        current - 1;
      elif dir == 2 then
        current + width;
      else
        current - width;
      ;

    let valid =
      if dir == 0 then
        cx < (width - 1);
      elif dir == 1 then
        cx > 0;
      elif dir == 2 then
        cy < (height - 1);
      else
        cy > 0;
      ;

    if valid then
      if (unsafe_ar_int_get walls neighbor_idx) == 1 then
        visit_neighbors open_set g_score walls goal_idx width height
          current cx cy (dir + 1) heap_sz;
      else
        let tentative_g = (unsafe_ar_int_get g_score current) + 1;

        if tentative_g < (unsafe_ar_int_get g_score neighbor_idx) then
          unsafe_ar_int_set g_score neighbor_idx tentative_g;
          let f = tentative_g + (heuristic neighbor_idx goal_idx width);
          let new_sz = heap_push open_set heap_sz neighbor_idx f;
          visit_neighbors open_set g_score walls goal_idx width height
            current cx cy (dir + 1) new_sz;
        else
          visit_neighbors open_set g_score walls goal_idx width height
            current cx cy (dir + 1) heap_sz;
        ;
      ;
    else
      visit_neighbors open_set g_score walls goal_idx width height
        current cx cy (dir + 1) heap_sz;
    ;
  ;
;

fn solve (
  open_set: MinHeap,
  g_score: IntArray,
  walls: IntArray,
  goal_idx: Int,
  width: Int,
  height: Int,
  h_size: Int
): Int =
  if h_size == 0 then
    (0 - 1);
  else
    let pop_res = heap_pop open_set h_size;
    let current = pop_res.idx;
    let current_h_size = pop_res.size;

    if current == goal_idx then
      unsafe_ar_int_get g_score goal_idx;
    else
      let cx = current % width;
      let cy = current / width;
      visit_neighbors open_set g_score walls goal_idx width height
        current cx cy 0 current_h_size;
    ;
  ;
;

fn astar (width: Int, height: Int, start_idx: Int, goal_idx: Int, walls: IntArray): Int =
  let size = width * height;
  let inf = 999999999;
  let g_score = ar_int_new size;

  init_g g_score inf size 0;
  unsafe_ar_int_set g_score start_idx 0;

  let open_set = heap_new size;
  let initial_h = heuristic start_idx goal_idx width;
  let init_heap_size = heap_push open_set 0 start_idx initial_h;

  solve open_set g_score walls goal_idx width height init_heap_size
;

fn build_wall (walls: IntArray, w: Int, i: Int): Unit =
  if i < 50 then
    let idx = 50 * w + (25 + i);
    unsafe_ar_int_set walls idx 1;
    build_wall walls w (i + 1);
  ;
;

fn main (): Unit =
  let w = 100;
  let h = 100;
  let size = w * h;
  let walls = ar_int_new size;

  build_wall walls w 0;

  let start = 0;
  let goal = size - 1;

  let cost = astar w h start goal walls;

  println (int_to_str cost)
;
```

### quicksort (no `end`)

```mml
// Helper to swap two elements
fn swap(arr: IntArray, a: Int, b: Int): Unit =
  let tmp = unsafe_ar_int_get arr a;
  unsafe_ar_int_set arr a (unsafe_ar_int_get arr b);
  unsafe_ar_int_set arr b tmp
;

// The inner loop of partition: for (j = low; j < high; j++)
// Returns the final value of 'i' (the pivot index)
fn partition_loop(arr: IntArray, j: Int, high: Int, pivot: Int, i: Int): Int =
  if j < high then
    let val = unsafe_ar_int_get arr j;
    if val < pivot then
      let new_i = i + 1;
      swap arr new_i j;
      partition_loop arr (j + 1) high pivot new_i;
    else
      partition_loop arr (j + 1) high pivot i;
    ;
  else
    i;
  ;
;

fn partition(arr: IntArray, low: Int, high: Int): Int =
  let pivot = unsafe_ar_int_get arr high;
  let i_start = low - 1;
  let final_i = partition_loop arr low high pivot i_start;
  swap arr (final_i + 1) high;
  final_i + 1
;

fn quicksort(arr: IntArray, low: Int, high: Int): Unit =
  if low < high then
    let p = partition arr low high;
    quicksort arr low (p - 1);
    quicksort arr (p + 1) high;
  ;
;

// Standard Linear Congruential Generator for deterministic random numbers
fn fill_random(arr: IntArray, seed: Int, i: Int, size: Int): Unit =
  if i < size then
    let next = (seed * 1664525) + 1013904223;
    let val = next % 100000;
    unsafe_ar_int_set arr i val;
    fill_random arr next (i + 1) size;
  ;
;

fn run_sort(size: Int): Int =
  let arr = ar_int_new size;
  fill_random arr 42 0 size;
  quicksort arr 0 (size - 1);
  unsafe_ar_int_get arr (size / 2)
;

fn main(): Unit =
  let result = run_sort 1000000;
  println ("Median checksum: " ++ (int_to_str result))
;
```

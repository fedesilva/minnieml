fn is_safe(board: &[i32], row: i32, col: i32) -> bool {
    for check_row in 0..row {
        let queen_col = board[check_row as usize];
        if queen_col == col || (row - check_row).abs() == (col - queen_col).abs() {
            return false;
        }
    }
    true
}

fn solve_col(board: &mut [i32], row: i32, n: i32, col: i32) -> i32 {
    if col >= n {
        return 0;
    }

    if is_safe(board, row, col) {
        board[row as usize] = col;
        let sub_solutions = if row == n - 1 {
            1
        } else {
            solve_row(board, row + 1, n)
        };
        sub_solutions + solve_col(board, row, n, col + 1)
    } else {
        solve_col(board, row, n, col + 1)
    }
}

fn solve_row(board: &mut [i32], row: i32, n: i32) -> i32 {
    solve_col(board, row, n, 0)
}

fn main() {
    let n = 12;
    let mut board = vec![0i32; n as usize];
    let solutions = solve_row(&mut board, 0, n);
    println!("Solutions for {}-queens: {}", n, solutions);
}

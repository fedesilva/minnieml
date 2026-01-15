#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

int64_t abs_int(int64_t x) {
    return (x < 0) ? -x : x;
}

// Check if placing queen at (row, col) conflicts with queen at check_row
int conflicts(int64_t *board, int64_t row, int64_t col, int64_t check_row) {
    int64_t queen_col = board[check_row];
    if (queen_col == col) {
        return 1;
    }
    int64_t row_diff = abs_int(row - check_row);
    int64_t col_diff = abs_int(col - queen_col);
    return row_diff == col_diff;
}

// Check if placing queen at (row, col) is safe
int is_safe_loop(int64_t *board, int64_t row, int64_t col, int64_t check_row) {
    while (check_row < row) {
        if (conflicts(board, row, col, check_row)) {
            return 0;
        }
        check_row++;
    }
    return 1;
}

// Forward declaration
int64_t solve_row(int64_t *board, int64_t row, int64_t n);

// Solve from given row, trying each column
int64_t solve_col(int64_t *board, int64_t row, int64_t n, int64_t col) {
    if (col >= n) {
        return 0;
    }
    
    if (is_safe_loop(board, row, col, 0)) {
        board[row] = col;
        int64_t sub_solutions;
        if (row == (n - 1)) {
            sub_solutions = 1;
        } else {
            sub_solutions = solve_row(board, row + 1, n);
        }
        int64_t rest = solve_col(board, row, n, col + 1);
        return sub_solutions + rest;
    } else {
        return solve_col(board, row, n, col + 1);
    }
}

int64_t solve_row(int64_t *board, int64_t row, int64_t n) {
    return solve_col(board, row, n, 0);
}

int main(void) {
    int64_t n = 12;
    int64_t *board = (int64_t *)malloc(n * sizeof(int64_t));
    
    int64_t solutions = solve_row(board, 0, n);
    printf("Solutions: %lld\n", solutions);
    
    free(board);
    return 0;
}


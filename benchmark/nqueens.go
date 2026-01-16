package main

import (
	"fmt"
)

func absInt(x int64) int64 {
	if x < 0 {
		return -x
	}
	return x
}

// Check if placing queen at (row, col) conflicts with queen at check_row
func conflicts(board []int64, row, col, checkRow int64) bool {
	queenCol := board[checkRow]
	if queenCol == col {
		return true
	}
	rowDiff := absInt(row - checkRow)
	colDiff := absInt(col - queenCol)
	return rowDiff == colDiff
}

// Check if placing queen at (row, col) is safe
func isSafeLoop(board []int64, row, col int64) bool {
	for checkRow := int64(0); checkRow < row; checkRow++ {
		if conflicts(board, row, col, checkRow) {
			return false
		}
	}
	return true
}

// Solve from given row, trying each column
func solveCol(board []int64, row, n, col int64) int64 {
	if col >= n {
		return 0
	}

	if isSafeLoop(board, row, col) {
		board[row] = col
		var subSolutions int64
		if row == (n - 1) {
			subSolutions = 1
		} else {
			subSolutions = solveRow(board, row+1, n)
		}
		rest := solveCol(board, row, n, col+1)
		return subSolutions + rest
	}
	return solveCol(board, row, n, col+1)
}

func solveRow(board []int64, row, n int64) int64 {
	return solveCol(board, row, n, 0)
}

func main() {
	n := int64(12)
	board := make([]int64, n)

	solutions := solveRow(board, 0, n)
	fmt.Printf("Solutions for %d-queens: %d\n", n, solutions)
}

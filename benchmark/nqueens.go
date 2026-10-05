package main

import (
	"fmt"
)

func absInt(x int32) int32 {
	if x < 0 {
		return -x
	}
	return x
}

// Check if placing queen at (row, col) conflicts with queen at check_row
func conflicts(board []int32, row, col, checkRow int32) bool {
	queenCol := board[checkRow]
	if queenCol == col {
		return true
	}
	rowDiff := absInt(row - checkRow)
	colDiff := absInt(col - queenCol)
	return rowDiff == colDiff
}

// Check if placing queen at (row, col) is safe
func isSafeLoop(board []int32, row, col int32) bool {
	for checkRow := int32(0); checkRow < row; checkRow++ {
		if conflicts(board, row, col, checkRow) {
			return false
		}
	}
	return true
}

// Solve from given row, trying each column
func solveCol(board []int32, row, n, col int32) int32 {
	if col >= n {
		return 0
	}

	if isSafeLoop(board, row, col) {
		board[row] = col
		var subSolutions int32
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

func solveRow(board []int32, row, n int32) int32 {
	return solveCol(board, row, n, 0)
}

func main() {
	n := int32(12)
	board := make([]int32, n)

	solutions := solveRow(board, 0, n)
	fmt.Printf("Solutions for %d-queens: %d\n", n, solutions)
}

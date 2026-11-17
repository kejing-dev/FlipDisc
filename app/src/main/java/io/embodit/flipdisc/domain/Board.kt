package io.embodit.flipdisc.domain

/**
 * The 8 x 8 board as two bitboards. A square is an index 0..63 = row * 8 + col; col 0..7 is a..h (left to right as
 * the player sits), row 0..7 is rank 1..8 (row 0 = rank 1 on the FAR edge, rank 8 on the near edge; design
 * spatialLayout.boardRoot: a1 far-left, h8 near-right). Bit i of a Long is square i.
 */
object Board {
    const val N = 8
    const val SIZE = 64
    const val EMPTY = 0
    const val BLACK = 1
    const val WHITE = 2

    private const val NOT_A = -0x101010101010102L // ~0x0101010101010101 (col 0 cleared)
    private const val NOT_H = 0x7F7F7F7F7F7F7F7FL // col 7 cleared

    /** The eight directions as (dCol, dRow). */
    val DIRS: Array<IntArray> = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
        intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(-1, -1),
    )

    val CORNERS: List<Int> = listOf(0, 7, 56, 63)

    fun at(col: Int, row: Int): Int = row * N + col
    fun col(sq: Int): Int = sq % N
    fun row(sq: Int): Int = sq / N
    fun inside(col: Int, row: Int): Boolean = col in 0 until N && row in 0 until N
    fun other(color: Int): Int = 3 - color
    fun bit(sq: Int): Long = 1L shl sq

    /** "f5" style name: column letter a..h, rank 1..8. */
    fun name(sq: Int): String = "${'a' + col(sq)}${row(sq) + 1}"

    fun parse(text: String): Int? {
        val t = text.trim().lowercase()
        if (t.length != 2) return null
        val c = t[0] - 'a'
        val r = t[1] - '1'
        return if (inside(c, r)) at(c, r) else null
    }

    /** Shift one step in direction [d] (index into [DIRS]), dropping bits that wrap around a file edge. */
    fun shift(b: Long, d: Int): Long = when (d) {
        0 -> (b shl 1) and NOT_A
        1 -> (b ushr 1) and NOT_H
        2 -> b shl 8
        3 -> b ushr 8
        4 -> (b shl 9) and NOT_A
        5 -> (b shl 7) and NOT_H
        6 -> (b ushr 7) and NOT_A
        else -> (b ushr 9) and NOT_H
    }
}

/** A position: [black] / [white] discs and whose turn it is. Immutable; [play] returns the next one. */
data class Position(val black: Long, val white: Long, val toMove: Int) {
    val empty: Long get() = (black or white).inv()
    val empties: Int get() = java.lang.Long.bitCount(empty)
    fun mine(color: Int = toMove): Long = if (color == Board.BLACK) black else white
    fun theirs(color: Int = toMove): Long = if (color == Board.BLACK) white else black
    fun count(color: Int): Int = java.lang.Long.bitCount(mine(color))
    fun at(sq: Int): Int = when {
        black and Board.bit(sq) != 0L -> Board.BLACK
        white and Board.bit(sq) != 0L -> Board.WHITE
        else -> Board.EMPTY
    }

    companion object {
        /** d4 white, e4 black, d5 black, e5 white; Black to move (design gameSpec.pieces). */
        val START = Position(
            black = Board.bit(Board.at(4, 3)) or Board.bit(Board.at(3, 4)),
            white = Board.bit(Board.at(3, 3)) or Board.bit(Board.at(4, 4)),
            toMove = Board.BLACK,
        )
    }
}

package io.embodit.flipdisc.domain

import java.lang.Long.bitCount

enum class EndReason { FULL, NO_MOVES }

/** [winner] is Board.BLACK / WHITE, or 0 for a draw. Only real discs count (design stateRules.score). */
data class GameResult(val winner: Int, val black: Int, val white: Int, val reason: EndReason)

/** One disc that turns over, [distance] squares from the placed disc along direction [dir] (index into Board.DIRS). */
data class Flip(val square: Int, val distance: Int, val dir: Int)

/**
 * Reversi rules on bitboards (public-domain rules, no engine code from anywhere). Pure functions only; the flow, the AI
 * and the scene all go through here.
 */
object Rules {
    /** Legal squares for the side owning [p] against [o]. */
    fun moves(p: Long, o: Long): Long {
        val empty = (p or o).inv()
        var m = 0L
        for (d in 0 until 8) {
            var x = Board.shift(p, d) and o
            x = x or (Board.shift(x, d) and o)
            x = x or (Board.shift(x, d) and o)
            x = x or (Board.shift(x, d) and o)
            x = x or (Board.shift(x, d) and o)
            x = x or (Board.shift(x, d) and o)
            m = m or (Board.shift(x, d) and empty)
        }
        return m
    }

    fun moves(pos: Position): Long = moves(pos.mine(), pos.theirs())

    /** Discs flipped by [p] playing [sq] (0 = not a legal move). */
    fun flips(p: Long, o: Long, sq: Int): Long {
        val m = Board.bit(sq)
        if ((p or o) and m != 0L) return 0L
        var all = 0L
        for (d in 0 until 8) {
            var f = 0L
            var x = Board.shift(m, d)
            while (x != 0L && x and o != 0L) { f = f or x; x = Board.shift(x, d) }
            if (x and p != 0L) all = all or f
        }
        return all
    }

    fun isLegal(pos: Position, sq: Int): Boolean = flips(pos.mine(), pos.theirs(), sq) != 0L

    /** Every flipped disc with its distance and direction, nearest first per direction (the domino order, design animation.flip). */
    fun flipList(pos: Position, sq: Int): List<Flip> {
        val p = pos.mine(); val o = pos.theirs()
        val out = ArrayList<Flip>()
        if ((p or o) and Board.bit(sq) != 0L) return out
        for ((d, v) in Board.DIRS.withIndex()) {
            val line = ArrayList<Int>()
            var c = Board.col(sq) + v[0]; var r = Board.row(sq) + v[1]
            while (Board.inside(c, r) && o and Board.bit(Board.at(c, r)) != 0L) { line += Board.at(c, r); c += v[0]; r += v[1] }
            if (line.isNotEmpty() && Board.inside(c, r) && p and Board.bit(Board.at(c, r)) != 0L) {
                line.forEachIndexed { i, s -> out += Flip(s, i + 1, d) }
            }
        }
        return out
    }

    /** [pos] after the side to move plays [sq]; the turn passes to the other side (no pass handling here). */
    fun play(pos: Position, sq: Int): Position {
        val p = pos.mine(); val o = pos.theirs()
        val f = flips(p, o, sq)
        require(f != 0L) { "illegal move ${Board.name(sq)}" }
        val np = p or f or Board.bit(sq)
        val no = o and f.inv()
        return if (pos.toMove == Board.BLACK) Position(np, no, Board.WHITE) else Position(no, np, Board.BLACK)
    }

    fun passed(pos: Position): Position = pos.copy(toMove = Board.other(pos.toMove))

    /** The game is over when neither side can move (a full board is a special case of that). */
    fun outcome(pos: Position): GameResult? {
        if (moves(pos.black, pos.white) != 0L || moves(pos.white, pos.black) != 0L) return null
        val b = bitCount(pos.black); val w = bitCount(pos.white)
        val winner = when { b > w -> Board.BLACK; w > b -> Board.WHITE; else -> 0 }
        return GameResult(winner, b, w, if (pos.empty == 0L) EndReason.FULL else EndReason.NO_MOVES)
    }

    /**
     * Normalises a position so the side to move can move (passes are implicit, design dataModel): if it has no move and
     * the other side has one, the turn passes. Returns the position and whether a pass happened.
     */
    fun settle(pos: Position): Pair<Position, Boolean> {
        if (moves(pos) != 0L) return pos to false
        val q = passed(pos)
        return if (moves(q) != 0L) q to true else pos to false
    }

    /** Replays [moves] from the start, inserting passes; returns null at the first illegal move. */
    fun replay(moves: List<Int>, start: Position = Position.START): Position? {
        var pos = start
        for (sq in moves) {
            pos = settle(pos).first
            if (!isLegal(pos, sq)) return null
            pos = play(pos, sq)
        }
        return settle(pos).first
    }

    /** Leaf count to [depth] plies; a forced pass counts as a ply (design acceptance: "计 pass"). */
    fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1
        val m = moves(pos)
        if (m == 0L) {
            val q = passed(pos)
            return if (moves(q) == 0L) 1 else perft(q, depth - 1)
        }
        var n = 0L
        var bits = m
        while (bits != 0L) {
            val sq = java.lang.Long.numberOfTrailingZeros(bits)
            bits = bits and (bits - 1)
            n += perft(play(pos, sq), depth - 1)
        }
        return n
    }

    fun squares(bits: Long): List<Int> {
        val out = ArrayList<Int>(bitCount(bits))
        var b = bits
        while (b != 0L) { out += java.lang.Long.numberOfTrailingZeros(b); b = b and (b - 1) }
        return out
    }
}

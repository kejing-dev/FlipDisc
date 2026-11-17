package io.embodit.flipdisc.domain

import java.lang.Long.bitCount
import java.lang.Long.numberOfTrailingZeros
import kotlin.random.Random

/** One level of design gameSpec.ai.levels. [randomTop] / [randomAllPct] make the low levels human; [openingSpread] varies the strong ones. */
data class Level(
    val n: Int,
    val depth: Int,
    val timeMs: Long,
    val exactEmpties: Int,
    val randomAllPct: Int = 0,
    val randomTop: Int = 1,
    val openingSpread: Int = 0,
) {
    companion object {
        val ALL = listOf(
            Level(1, depth = 1, timeMs = 200, exactEmpties = 0, randomAllPct = 50, randomTop = 5),
            Level(2, depth = 2, timeMs = 250, exactEmpties = 0, randomTop = 3),
            Level(3, depth = 3, timeMs = 300, exactEmpties = 8, openingSpread = 15),
            Level(4, depth = 4, timeMs = 800, exactEmpties = 10, openingSpread = 15),
            Level(5, depth = 6, timeMs = 1500, exactEmpties = 12, openingSpread = 15),
            Level(6, depth = 8, timeMs = 2500, exactEmpties = 14, openingSpread = 15),
        )
        fun of(n: Int): Level = ALL[(n - 1).coerceIn(0, ALL.lastIndex)]
        /** Hard cap for any search (design acceptance: master longest <= 3 s). */
        const val HARD_CAP_MS = 2900L
        /** Opening randomness applies to the first plies of the game. */
        const val OPENING_PLIES = 6
    }
}

/** What the hint button shows (design gameSpec.hint.states). [diff] = final disc difference for the side to move (exact only). */
enum class HintKind { SURE, UNSURE, LOST }

data class Hint(val square: Int, val kind: HintKind, val diff: Int)

/**
 * negamax α-β + iterative deepening + time limit + random among the top N, exact endgame solve (design gameSpec.ai).
 * The evaluation is the general position-weight / mobility / stability heuristic (public-domain ideas). Not thread-safe:
 * make one per search. [cancelled] is polled every few thousand nodes.
 */
class Engine(
    private val random: Random = Random(1),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val cancelled: () -> Boolean = { false },
) {
    private class Abort : RuntimeException(null, null, false, false)

    private var deadline = Long.MAX_VALUE
    var nodes = 0L
        private set

    private fun tick() {
        nodes++
        if (nodes and 2047L == 0L && (clock() > deadline || cancelled())) throw Abort()
    }

    // ---------------------------------------------------------------- evaluation

    private val weights = intArrayOf(
        100, -20, 10, 5, 5, 10, -20, 100,
        -20, -50, -2, -2, -2, -2, -50, -20,
        10, -2, 3, 1, 1, 3, -2, 10,
        5, -2, 1, 2, 2, 1, -2, 5,
        5, -2, 1, 2, 2, 1, -2, 5,
        10, -2, 3, 1, 1, 3, -2, 10,
        -20, -50, -2, -2, -2, -2, -50, -20,
        100, -20, 10, 5, 5, 10, -20, 100,
    )

    /** X / C squares only hurt while their corner is empty (design gameSpec.ai.eval). */
    private val cornerOf = IntArray(64) { -1 }.also { c ->
        fun link(corner: Int, vararg sqs: Int) = sqs.forEach { c[it] = corner }
        link(0, 1, 8, 9); link(7, 6, 15, 14); link(56, 48, 57, 49); link(63, 55, 62, 54)
    }

    private fun positional(p: Long, o: Long): Int {
        var s = 0
        var b = p
        while (b != 0L) { val sq = numberOfTrailingZeros(b); b = b and (b - 1); s += weightAt(sq, p, o) }
        b = o
        while (b != 0L) { val sq = numberOfTrailingZeros(b); b = b and (b - 1); s -= weightAt(sq, p, o) }
        return s
    }

    private fun weightAt(sq: Int, p: Long, o: Long): Int {
        val w = weights[sq]
        val c = cornerOf[sq]
        return if (w < 0 && c >= 0 && (p or o) and Board.bit(c) != 0L) 2 else w
    }

    /** Discs on the edges anchored to an owned corner (a cheap lower bound of stable discs). */
    private fun stable(p: Long): Int {
        var st = 0L
        fun walk(corner: Int, step: Int, count: Int) {
            var sq = corner
            for (i in 0 until count) {
                if (p and Board.bit(sq) == 0L) return
                st = st or Board.bit(sq)
                sq += step
            }
        }
        if (p and Board.bit(0) != 0L) { walk(0, 1, 8); walk(0, 8, 8) }
        if (p and Board.bit(7) != 0L) { walk(7, -1, 8); walk(7, 8, 8) }
        if (p and Board.bit(56) != 0L) { walk(56, 1, 8); walk(56, -8, 8) }
        if (p and Board.bit(63) != 0L) { walk(63, -1, 8); walk(63, -8, 8) }
        return bitCount(st)
    }

    /** Empty squares next to a disc of [p]: discs on the frontier give the opponent moves. */
    private fun frontier(p: Long, empty: Long): Int {
        var adj = 0L
        for (d in 0 until 8) adj = adj or Board.shift(empty, d)
        return bitCount(p and adj)
    }

    private fun potentialMobility(p: Long, o: Long): Int {
        val empty = (p or o).inv()
        var adj = 0L
        for (d in 0 until 8) adj = adj or Board.shift(o, d)
        return bitCount(adj and empty)
    }

    /** Static evaluation for the side owning [p]. */
    fun evaluate(p: Long, o: Long): Int {
        val empty = (p or o).inv()
        val empties = bitCount(empty)
        val myMob = bitCount(Rules.moves(p, o))
        val opMob = bitCount(Rules.moves(o, p))
        if (myMob == 0 && opMob == 0) return exactScore(p, o) * 1000
        val pos = positional(p, o)
        return if (empties > 44) {
            (myMob - opMob) * 10 + (potentialMobility(p, o) - potentialMobility(o, p)) * 3 + pos
        } else {
            val parity = if (empties % 2 == 1) 4 else -4
            pos + (myMob - opMob) * 6 + (stable(p) - stable(o)) * 15 - (frontier(p, empty) - frontier(o, empty)) * 2 + parity
        }
    }

    private fun exactScore(p: Long, o: Long): Int = bitCount(p) - bitCount(o)

    // ---------------------------------------------------------------- midgame search

    private fun negamax(p: Long, o: Long, depth: Int, alpha0: Int, beta: Int, passed: Boolean): Int {
        tick()
        if (depth == 0) return evaluate(p, o)
        val m = Rules.moves(p, o)
        if (m == 0L) {
            if (passed) return exactScore(p, o) * 1000
            return -negamax(o, p, depth - 1, -beta, -alpha0, true)
        }
        var alpha = alpha0
        var best = Int.MIN_VALUE + 1
        for (sq in ordered(p, o, m)) {
            val f = Rules.flips(p, o, sq)
            val np = p or f or Board.bit(sq)
            val no = o and f.inv()
            val v = -negamax(no, np, depth - 1, -beta, -alpha, false)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    /** Corners first, X squares last, then by the static weight: cheap ordering that keeps α-β efficient. */
    private fun ordered(p: Long, o: Long, m: Long): IntArray {
        val sqs = Rules.squares(m).toIntArray()
        val keys = IntArray(sqs.size) { weightAt(sqs[it], p, o) }
        // insertion sort by key, descending
        for (i in 1 until sqs.size) {
            val s = sqs[i]; val k = keys[i]; var j = i - 1
            while (j >= 0 && keys[j] < k) { sqs[j + 1] = sqs[j]; keys[j + 1] = keys[j]; j-- }
            sqs[j + 1] = s; keys[j + 1] = k
        }
        return sqs
    }

    // ---------------------------------------------------------------- endgame

    /** Exact final disc difference for the side owning [p] with perfect play. */
    private fun solve(p: Long, o: Long, alpha0: Int, beta: Int, passed: Boolean): Int {
        tick()
        val m = Rules.moves(p, o)
        if (m == 0L) {
            if (passed) return exactScore(p, o)
            return -solve(o, p, -beta, -alpha0, true)
        }
        var alpha = alpha0
        var best = -65
        val empties = bitCount((p or o).inv())
        val order = if (empties > 6) fastestFirst(p, o, m) else Rules.squares(m).toIntArray()
        for (sq in order) {
            val f = Rules.flips(p, o, sq)
            val np = p or f or Board.bit(sq)
            val no = o and f.inv()
            val v = -solve(no, np, -beta, -alpha, false)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    /** Moves that leave the opponent fewest replies first (classic endgame ordering), corners breaking ties. */
    private fun fastestFirst(p: Long, o: Long, m: Long): IntArray {
        val sqs = Rules.squares(m).toIntArray()
        val keys = IntArray(sqs.size) { i ->
            val sq = sqs[i]
            val f = Rules.flips(p, o, sq)
            val np = p or f or Board.bit(sq)
            val no = o and f.inv()
            bitCount(Rules.moves(no, np)) * 4 - (if (sq in Board.CORNERS) 2 else 0)
        }
        for (i in 1 until sqs.size) {
            val s = sqs[i]; val k = keys[i]; var j = i - 1
            while (j >= 0 && keys[j] > k) { sqs[j + 1] = sqs[j]; keys[j + 1] = keys[j]; j-- }
            sqs[j + 1] = s; keys[j + 1] = k
        }
        return sqs
    }

    /** Best exact move and its score (the α-β window narrows as we go). Null if the time ran out. */
    fun solveBest(pos: Position, timeMs: Long): Pair<Int, Int>? {
        deadline = clock() + timeMs
        val p = pos.mine(); val o = pos.theirs()
        return try {
            var best = -1; var bestV = -65
            for (sq in fastestFirst(p, o, Rules.moves(p, o))) {
                val f = Rules.flips(p, o, sq)
                val v = -solve(o and f.inv(), p or f or Board.bit(sq), -65, -bestV, false)
                if (best < 0 || v > bestV) { best = sq; bestV = v }
            }
            if (best < 0) null else best to bestV
        } catch (_: Abort) { null } finally { deadline = Long.MAX_VALUE }
    }

    // ---------------------------------------------------------------- root

    /**
     * Scores of the root moves at [depth], searched in [order]. [spread] null = full window for every move (the random-top
     * levels compare them all); otherwise later moves are searched with alpha = best - spread - 1, so every move within
     * [spread] of the best gets its exact score and the rest only an upper bound below that line.
     */
    private fun scoreAll(p: Long, o: Long, depth: Int, order: IntArray, spread: Int?): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(order.size)
        var best = Int.MIN_VALUE / 2
        for (sq in order) {
            val f = Rules.flips(p, o, sq)
            val alpha = if (spread == null || out.isEmpty()) -1_000_000 else best - spread - 1
            val v = -negamax(o and f.inv(), p or f or Board.bit(sq), depth - 1, -1_000_000, -alpha, false)
            out += sq to v
            if (v > best) best = v
        }
        return out
    }

    /**
     * The move for [level] in [pos] ([ply] = moves played so far, for the opening spread). -1 when there is no move.
     * Never longer than [Level.HARD_CAP_MS].
     */
    fun bestMove(pos: Position, level: Level, ply: Int): Int {
        val p = pos.mine(); val o = pos.theirs()
        val m = Rules.moves(p, o)
        if (m == 0L) return -1
        val legal = Rules.squares(m)
        if (legal.size == 1) return legal[0]
        val start = clock()
        val budget = minOf(level.timeMs, Level.HARD_CAP_MS)

        if (level.randomAllPct > 0 && random.nextInt(100) < level.randomAllPct) return legal[random.nextInt(legal.size)]

        if (level.exactEmpties > 0 && pos.empties <= level.exactEmpties) {
            solveBest(pos, budget)?.let { return it.first }
        }

        val first = ordered(p, o, m)
        if (level.randomTop > 1) {
            deadline = start + budget
            val scored = try { scoreAll(p, o, level.depth, first, null) } catch (_: Abort) { scoreAll(p, o, 1, first, null) } finally { deadline = Long.MAX_VALUE }
            val top = scored.sortedByDescending { it.second }.take(level.randomTop)
            return top[random.nextInt(top.size)].first
        }

        // iterative deepening with the time limit; keep the last finished depth and search its best moves first
        val spread = if (level.openingSpread > 0 && ply < Level.OPENING_PLIES) level.openingSpread else 0
        var best: List<Pair<Int, Int>> = scoreAll(p, o, 1, first, null).sortedByDescending { it.second }
        deadline = start + budget
        try {
            for (d in 2..level.depth) {
                if (clock() - start > budget / 2) break
                best = scoreAll(p, o, d, best.map { it.first }.toIntArray(), spread).sortedByDescending { it.second }
            }
        } catch (_: Abort) {
        } finally { deadline = Long.MAX_VALUE }
        if (spread > 0) {
            val near = best.filter { best[0].second - it.second <= spread }
            return near[random.nextInt(near.size)].first
        }
        return best[0].first
    }

    /** The hint (design gameSpec.hint): exact to the end at <= 12 empties, else a 300 ms level-5 search. */
    fun hint(pos: Position): Hint? {
        val m = Rules.moves(pos)
        if (m == 0L) return null
        if (pos.empties <= 12) {
            solveBest(pos, 1500)?.let { (sq, v) -> return Hint(sq, if (v >= 0) HintKind.SURE else HintKind.LOST, v) }
        }
        val sq = bestMove(pos, Level.of(5).copy(timeMs = 300, exactEmpties = 0, openingSpread = 0), 99)
        return Hint(sq, HintKind.UNSURE, 0)
    }
}

package io.embodit.flipdisc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.Long.bitCount
import kotlin.random.Random

class EngineTest {
    /** A position reached by [plies] random moves (seeded). */
    private fun randomPosition(seed: Int, plies: Int): Pair<Position, List<Int>> {
        val rnd = Random(seed)
        var pos = Position.START
        val moves = ArrayList<Int>()
        while (moves.size < plies && Rules.outcome(pos) == null) {
            pos = Rules.settle(pos).first
            val legal = Rules.squares(Rules.moves(pos))
            val m = legal[rnd.nextInt(legal.size)]
            moves += m; pos = Rules.play(pos, m)
        }
        return Rules.settle(pos).first to moves
    }

    /** Plain minimax to the end (no pruning), to check the solver on small endgames. */
    private fun brute(p: Long, o: Long, passed: Boolean): Int {
        val m = Rules.moves(p, o)
        if (m == 0L) return if (passed) bitCount(p) - bitCount(o) else -brute(o, p, true)
        var best = -65
        for (sq in Rules.squares(m)) {
            val f = Rules.flips(p, o, sq)
            best = maxOf(best, -brute(o and f.inv(), p or f or Board.bit(sq), false))
        }
        return best
    }

    @Test fun solverMatchesBruteForce() {
        var checked = 0
        for (seed in 1..60) {
            val (pos, _) = randomPosition(seed, 52)
            if (Rules.outcome(pos) != null || pos.empties > 9) continue
            val (sq, v) = Engine().solveBest(pos, 10_000)!!
            assertEquals("seed $seed", brute(pos.mine(), pos.theirs(), false), v)
            assertTrue(Rules.isLegal(pos, sq))
            checked++
        }
        assertTrue("checked $checked", checked >= 20)
    }

    @Test fun hintKinds() {
        // endgame: exact, SURE when the best line wins / draws, LOST otherwise; midgame: UNSURE
        var sure = 0; var lost = 0
        for (seed in 1..80) {
            val (pos, _) = randomPosition(seed, 50)
            if (Rules.outcome(pos) != null || pos.empties > 12) continue
            val h = Engine().hint(pos)!!
            val exact = brute(pos.mine(), pos.theirs(), false).takeIf { pos.empties <= 10 }
            if (exact != null) assertEquals(exact, h.diff)
            if (h.kind == HintKind.SURE) { sure++; assertTrue(h.diff >= 0) } else { assertEquals(HintKind.LOST, h.kind); lost++; assertTrue(h.diff < 0) }
        }
        assertTrue("sure $sure lost $lost", sure > 0 && lost > 0)
        val (mid, _) = randomPosition(3, 20)
        assertEquals(HintKind.UNSURE, Engine().hint(mid)!!.kind)
    }

    @Test fun everyLevelPlaysALegalMove() {
        for (level in 1..6) for (seed in 1..4) {
            val (pos, moves) = randomPosition(seed * 7 + level, 10 + seed * 9)
            if (Rules.moves(pos) == 0L) continue
            val m = Engine(Random(seed)).bestMove(pos, Level.of(level), moves.size)
            assertTrue("L$level ${Board.name(m)}", Rules.isLegal(pos, m))
        }
    }

    @Test fun takesAFreeCorner() {
        // white can take a1 (flipping b2) or play elsewhere; every level >= 2 takes the corner
        val p = Position(Board.bit(Board.parse("b2")!!) or Board.bit(Board.parse("d4")!!), Board.bit(Board.parse("c3")!!) or Board.bit(Board.parse("e5")!!), Board.WHITE)
        for (level in 2..6) assertEquals("L$level", Board.parse("a1"), Engine(Random(1)).bestMove(p, Level.of(level), 30))
    }

    /** M5: master thinks <= 2.5 s on average and never past 3 s (hard cap), over midgame and 14-empty endgame positions. */
    @Test fun masterTimeCap() {
        val times = ArrayList<Long>()
        for ((seed, plies) in listOf(1 to 20, 2 to 26, 3 to 32, 4 to 38, 5 to 44, 6 to 46, 7 to 48)) {
            val (pos, moves) = randomPosition(seed, plies)
            if (Rules.moves(pos) == 0L) continue
            val t0 = System.nanoTime()
            Engine(Random(seed)).bestMove(pos, Level.of(6), moves.size)
            times += (System.nanoTime() - t0) / 1_000_000
        }
        println("MASTER ms=$times avg=${times.average()}")
        assertTrue("max ${times.max()}", times.max() <= 3000)
        assertTrue("avg ${times.average()}", times.average() <= 2500)
    }
}

/**
 * M4 (design acceptance, AI monotone): adjacent levels 20 games each from a pool of >= 50 different openings, colours
 * alternating; the higher level must score >= 65 % (win 1, draw 0.5). L1 against "random + always take a corner" over
 * 200 games wins <= 60 %. Slow (the strong levels think up to 2.5 s a move), so it runs only with
 * `-DaiStrength=true` (`./gradlew :app:testDebugUnitTest --tests '*AiStrengthTest*' -DaiStrength=true`); the result is
 * copied into apps/flip-disc/build/mustpass.md.
 */
class AiStrengthTest {
    private fun enabled() = System.getProperty("aiStrength") == "true" || System.getenv("AI_STRENGTH") == "1"

    /** 60 different openings: 4 random plies each, deduplicated. */
    private val openings: List<List<Int>> by lazy {
        val out = LinkedHashSet<List<Int>>()
        var seed = 1
        while (out.size < 60) {
            val rnd = Random(seed++)
            var pos = Position.START
            val moves = ArrayList<Int>()
            repeat(4) { val legal = Rules.squares(Rules.moves(pos)); val m = legal[rnd.nextInt(legal.size)]; moves += m; pos = Rules.settle(Rules.play(pos, m)).first }
            out += moves
        }
        out.toList()
    }

    private fun play(opening: List<Int>, black: (Position, Int) -> Int, white: (Position, Int) -> Int): Int {
        var pos = Rules.replay(opening)!!
        var ply = opening.size
        while (Rules.outcome(pos) == null) {
            pos = Rules.settle(pos).first
            val m = if (pos.toMove == Board.BLACK) black(pos, ply) else white(pos, ply)
            pos = Rules.play(pos, m); ply++
        }
        return Rules.outcome(pos)!!.winner
    }

    private fun levelPlayer(level: Int, seed: Int): (Position, Int) -> Int = { pos, ply -> Engine(Random(seed + ply)).bestMove(pos, Level.of(level), ply) }

    @Test fun adjacentLevelsAreMonotone() {
        assumeTrue(enabled())
        val report = StringBuilder()
        for (low in 1..5) {
            val high = low + 1
            var score = 0.0
            for (g in 0 until 20) {
                val opening = openings[(g * 3 + low * 7) % openings.size]
                val highIsBlack = g % 2 == 0
                val w = play(opening, if (highIsBlack) levelPlayer(high, g) else levelPlayer(low, g), if (highIsBlack) levelPlayer(low, g + 99) else levelPlayer(high, g + 99))
                score += when (w) { 0 -> 0.5; Board.BLACK -> if (highIsBlack) 1.0 else 0.0; else -> if (highIsBlack) 0.0 else 1.0 }
            }
            report.append("L$high vs L$low: ${score / 20 * 100}%\n")
            println("AI L$high vs L$low score=${score / 20}")
            assertTrue("L$high vs L$low only ${score / 20}", score / 20 >= 0.65)
        }
        println(report)
    }

    @Test fun rookieIsBeatable() {
        assumeTrue(enabled())
        var wins = 0
        for (g in 0 until 200) {
            val rnd = Random(1000 + g)
            val naive: (Position, Int) -> Int = { pos, _ ->
                val legal = Rules.squares(Rules.moves(pos))
                legal.firstOrNull { it in Board.CORNERS } ?: legal[rnd.nextInt(legal.size)]
            }
            val l1Black = g % 2 == 0
            val w = play(openings[g % openings.size], if (l1Black) levelPlayer(1, g) else naive, if (l1Black) naive else levelPlayer(1, g))
            if ((w == Board.BLACK && l1Black) || (w == Board.WHITE && !l1Black)) wins++
        }
        println("AI L1 vs random+corner wins=$wins/200")
        assertTrue("L1 won $wins / 200", wins <= 120)
    }
}

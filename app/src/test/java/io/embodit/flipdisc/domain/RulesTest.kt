package io.embodit.flipdisc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun sq(n: String) = Board.parse(n)!!
    private fun pos(black: List<String>, white: List<String>, toMove: Int = Board.BLACK) = Position(
        black.fold(0L) { b, s -> b or Board.bit(sq(s)) }, white.fold(0L) { b, s -> b or Board.bit(sq(s)) }, toMove,
    )

    @Test fun startPosition() {
        val p = Position.START
        assertEquals(Board.WHITE, p.at(sq("d4"))); assertEquals(Board.BLACK, p.at(sq("e4")))
        assertEquals(Board.BLACK, p.at(sq("d5"))); assertEquals(Board.WHITE, p.at(sq("e5")))
        assertEquals(setOf("d3", "c4", "f5", "e6"), Rules.squares(Rules.moves(p)).map(Board::name).toSet())
    }

    /**
     * M1. The standard Reversi perft from the opening (8 x 8, Black first). Source of the numbers: the long-standing
     * reference sequence 1, 4, 12, 56, 244, 1396, 8200, 55092, 390216 (OEIS A124004 "Number of possible Reversi games
     * at the end of the n-th ply"), checked against the design reviewer's list; no pass occurs before ply 9.
     */
    @Test fun perftFromStart() {
        val want = longArrayOf(1, 4, 12, 56, 244, 1396, 8200, 55092, 390216)
        for (d in 0..8) assertEquals("ply $d", want[d], Rules.perft(Position.START, d))
    }

    @Test fun flipsInAllEightDirections() {
        // white ring around d4..f6 with black anchors two steps out: e5 for black flips one disc in each direction
        val center = "e5"
        val whites = listOf("d4", "e4", "f4", "d5", "f5", "d6", "e6", "f6")
        val blacks = listOf("c3", "e3", "g3", "c5", "g5", "c7", "e7", "g7")
        val p = pos(blacks, whites)
        val flips = Rules.flipList(p, sq(center))
        assertEquals(8, flips.size)
        assertEquals(whites.toSet(), flips.map { Board.name(it.square) }.toSet())
        val after = Rules.play(p, sq(center))
        assertEquals(17, after.count(Board.BLACK)); assertEquals(0, after.count(Board.WHITE))
    }

    @Test fun noWrapAroundTheEdge() {
        // h4 white, a5 black: playing g4? a5 is not on the same row line through the wrap
        val p = pos(listOf("a5"), listOf("h4"))
        assertFalse(Rules.isLegal(p, sq("g4")))
        assertEquals(0L, Rules.moves(p))
    }

    @Test fun cornerCanNeverFlipBack() {
        // a black corner disc is never between two white discs on any line
        val p = pos(listOf("a1", "b1"), listOf("c1"), Board.WHITE)
        for (m in Rules.squares(Rules.moves(p))) assertEquals(Board.BLACK, Rules.play(p, m).at(sq("a1")))
        assertTrue(Rules.flipList(p, sq("a1")).isEmpty())
    }

    @Test fun distancesCountFromThePlacedDisc() {
        val p = pos(listOf("a1"), listOf("b1", "c1", "d1", "e1", "f1", "g1"))
        val flips = Rules.flipList(p, sq("h1"))
        assertEquals((1..6).toList(), flips.sortedBy { it.distance }.map { it.distance })
        assertEquals("g1", Board.name(flips.first { it.distance == 1 }.square))
    }

    @Test fun passWhenNoMoveButOpponentHasOne() {
        // Black c1 has white a1 b1 behind it and nothing else: Black cannot move, White can (d1): the turn passes
        val p = pos(listOf("c1"), listOf("a1", "b1"), Board.BLACK)
        assertEquals(0L, Rules.moves(p))
        val (settled, passed) = Rules.settle(p)
        assertTrue(passed); assertEquals(Board.WHITE, settled.toMove)
        assertEquals(listOf("d1"), Rules.squares(Rules.moves(settled)).map(Board::name))
        assertNull(Rules.outcome(p))
    }

    @Test fun bothStuckEndsTheGame() {
        val p = pos(listOf("a1"), listOf("h8"))
        val r = Rules.outcome(p)!!
        assertEquals(EndReason.NO_MOVES, r.reason); assertEquals(0, r.winner)
    }

    @Test fun fullBoardEnds() {
        val all = (0 until 64).map { Board.name(it) }
        val p = pos(all.take(33), all.drop(33))
        val r = Rules.outcome(p)!!
        assertEquals(EndReason.FULL, r.reason); assertEquals(Board.BLACK, r.winner); assertEquals(33, r.black); assertEquals(31, r.white)
    }

    @Test fun equalCountIsADraw() {
        val all = (0 until 64).map { Board.name(it) }
        val r = Rules.outcome(pos(all.take(32), all.drop(32)))!!
        assertEquals(0, r.winner)
    }

    @Test fun gameNotOverWhileSomeoneCanMove() {
        assertNull(Rules.outcome(Position.START))
    }

    @Test fun replayInsertsPasses() {
        // a short known game ending early: f5 d6 c5 f4 e3 f6 d3 f3 ... use a random legal game and check replay agrees
        var p = Position.START
        val moves = ArrayList<Int>()
        val rnd = kotlin.random.Random(3)
        while (Rules.outcome(p) == null) {
            p = Rules.settle(p).first
            val legal = Rules.squares(Rules.moves(p))
            val m = legal[rnd.nextInt(legal.size)]
            moves += m
            p = Rules.play(p, m)
        }
        assertEquals(Rules.settle(p).first, Rules.replay(moves))
        assertNull(Rules.replay(moves + moves.first()))
    }

    @Test fun saveCodecRoundTrip() {
        val g = SavedGame(GameConfig(Mode.TWO_PLAYER, Board.WHITE, 6), listOf("f5", "d6", "c3").map(::sq), 42)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        assertNull(SaveCodec.decode("{\"v\":1,\"mode\":\"AI\",\"human\":1,\"level\":3,\"seed\":0,\"moves\":\"a1\"}"))
    }
}

package io.embodit.flipdisc.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private class MemStore : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    private class Rig {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val store = MemStore()
        var settings = Settings(firstRunDone = true)
        val sounds = ArrayList<Pair<Sound, Float>>()
        val log = ArrayList<String>()
        val flow = GameFlow(
            scope = scope, engineDispatcher = dispatcher, clock = { dispatcher.scheduler.currentTime }, store = store,
            settings = { settings }, onSettings = { settings = it }, onSound = { s, r -> sounds += s to r }, log = { log += it },
        )
        val st get() = flow.state.value
        fun settle() = scope.advanceUntilIdle()
    }

    private fun sq(n: String) = Board.parse(n)!!

    @Test fun firstRunThenSetup() {
        val r = Rig(); r.settings = Settings()
        r.flow.start()
        assertEquals(Phase.WELCOME, r.st.phase)
        r.flow.welcomeDone()
        assertEquals(Phase.SETUP, r.st.phase)
        assertTrue(r.settings.firstRunDone)
    }

    @Test fun tapLegalPlaysFlipsAndHandsOver() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        assertEquals(setOf("d3", "c4", "f5", "e6"), r.st.legal.map(Board::name).toSet())
        r.flow.tapSquare(sq("f5"))
        assertEquals(Phase.MOVING, r.st.phase)
        assertNotNull(r.st.anim)
        r.settle()
        assertEquals(Phase.IDLE, r.st.phase)
        assertEquals(Board.WHITE, r.st.toMove)
        assertEquals(4, r.st.black); assertEquals(1, r.st.white)
        // one place sound, one flip ring
        assertEquals(1, r.sounds.count { it.first == Sound.PLACE })
        assertEquals(1, r.sounds.count { it.first == Sound.FLIP })
    }

    @Test fun illegalTapsSayWhy() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        r.flow.tapSquare(sq("a1"))
        assertEquals(AlertKind.NO_FLIP, r.st.alert?.kind)
        r.flow.tapSquare(sq("d4"))
        assertEquals(AlertKind.OCCUPIED, r.st.alert?.kind)
        assertEquals(Phase.IDLE, r.st.phase)
        assertEquals(0, r.st.moves.size)
        r.settle()
        assertNull(r.st.alert)
    }

    @Test fun dragSnapsAndReleasesOnLegal() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        assertTrue(r.flow.dragStart(Board.BLACK))
        assertFalse("not your trough", r.flow.dragStart(Board.WHITE))
        val g = BoardGeometry(r.settings.tilt)
        val start = r.flow.drag.held
        val target = g.squareScene(sq("f5"), BoardGeometry.DISC_Y + 0.03f)
        r.flow.dragMove((target - start) * 0.5f)
        r.flow.dragMove(target - start)
        assertEquals(sq("f5"), r.st.snapped)
        assertEquals(Phase.DRAGGING, r.st.phase)
        r.flow.dragEnd()
        assertEquals(Phase.MOVING, r.st.phase)
        assertTrue(r.st.anim!!.fromDrag)
        r.settle()
        assertEquals(listOf(sq("f5")), r.st.moves)
    }

    @Test fun dragReleasedOffALegalSquareGoesBackWithAReason() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        r.flow.dragStart(Board.BLACK)
        val g = BoardGeometry(r.settings.tilt)
        val start = r.flow.drag.held
        r.flow.dragMove(g.squareScene(sq("a1"), BoardGeometry.DISC_Y + 0.03f) - start)
        assertNull(r.st.snapped)
        r.flow.dragEnd()
        assertEquals(Phase.IDLE, r.st.phase)
        assertNotNull(r.st.back)
        assertEquals(AlertKind.NO_FLIP, r.st.alert?.kind)
        assertTrue(r.sounds.any { it.first == Sound.RETRACT })
        assertEquals(0, r.st.moves.size)
        // over an occupied square
        r.settle()
        r.flow.dragStart(Board.BLACK)
        r.flow.dragMove(g.squareScene(sq("d4"), BoardGeometry.DISC_Y + 0.03f) - r.flow.drag.held)
        r.flow.dragEnd()
        assertEquals(AlertKind.OCCUPIED, r.st.alert?.kind)
        // cancel: back, no reason
        r.settle()
        r.flow.dragStart(Board.BLACK)
        r.flow.dragCancel()
        assertNull(r.st.alert)
        assertNotNull(r.st.back)
    }

    @Test fun aiAnswersAfterTheMinimumThink() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 1))
        r.flow.tapSquare(sq("f5"))
        r.scope.advanceTimeBy(GameFlow.FLIGHT_MS + GameFlow.LAND_MS + 500)
        r.scope.runCurrent()
        assertEquals(Phase.THINKING, r.st.phase)
        r.settle()
        assertEquals(2, r.st.moves.size)
        assertEquals(Phase.IDLE, r.st.phase)
    }

    /** M11: back from the background while the AI thinks: the search starts again. */
    @Test fun foregroundRestartsTheSearch() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.AI, Board.WHITE, 1))
        assertEquals(Phase.THINKING, r.st.phase)
        r.flow.onBackground()
        r.settle()
        assertEquals("cancelled", 0, r.st.moves.size)
        r.flow.onForeground()
        r.settle()
        assertEquals(1, r.st.moves.size)
    }

    @Test fun passIsShownThenPlayContinues() {
        // Black plays a move after which White has no move (found by a random playout)
        val r = Rig()
        val (before, m) = findPass()
        r.flow.load(before, GameConfig(Mode.TWO_PLAYER))
        r.flow.tapSquare(m)
        r.scope.advanceTimeBy(r.st.anim!!.totalMs + 10)
        r.scope.runCurrent()
        assertEquals(Phase.PASS, r.st.phase)
        assertEquals(Board.WHITE, r.st.passColor)
        assertTrue(r.sounds.any { it.first == Sound.PASS })
        r.scope.advanceTimeBy(GameFlow.PASS_MS + 10)
        r.scope.runCurrent()
        assertEquals(Phase.IDLE, r.st.phase)
        assertEquals(Board.BLACK, r.st.toMove)
    }

    private fun findPass(): Pair<List<Int>, Int> {
        for (seed in 1..5000) {
            val rnd = kotlin.random.Random(seed)
            var pos = Position.START
            val moves = ArrayList<Int>()
            while (Rules.outcome(pos) == null) {
                pos = Rules.settle(pos).first
                val legal = Rules.squares(Rules.moves(pos))
                val m = legal[rnd.nextInt(legal.size)]
                val next = Rules.play(pos, m)
                if (pos.toMove == Board.BLACK && Rules.moves(next) == 0L && Rules.outcome(next) == null) return moves to m
                moves += m; pos = next
            }
        }
        error("no pass found")
    }

    /** AI undo goes back to before your last move, across the AI reply; 2p asks first and takes back one. */
    @Test fun undoRules() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 1))
        assertFalse(r.st.canUndo)
        r.flow.tapSquare(sq("f5")); r.settle()
        assertEquals(2, r.st.moves.size)
        r.flow.requestUndo(); r.settle()
        assertEquals(0, r.st.moves.size)
        assertEquals(Phase.IDLE, r.st.phase)

        val p = Rig()
        p.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        p.flow.tapSquare(sq("f5")); p.settle()
        p.flow.requestUndo()
        assertEquals(ConfirmKind.UNDO_2P, p.st.confirm)
        assertEquals(1, p.st.moves.size)
        p.flow.confirm(); p.settle()
        assertEquals(0, p.st.moves.size)
    }

    /** M9 / D30: a random colour only goes into this game's config. */
    @Test fun randomColourDoesNotTouchSettings() {
        val r = Rig()
        r.flow.newGame(GameSetup(Mode.AI, SidePick.RANDOM, 2))
        assertEquals(SidePick.RANDOM, r.settings.lastSetup.side)
        assertTrue(r.st.config!!.humanColor in listOf(Board.BLACK, Board.WHITE))
    }

    @Test fun saveAndRestore() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER, Board.BLACK, 4))
        r.flow.tapSquare(sq("f5")); r.settle()
        r.flow.tapSquare(sq("d6")); r.settle()
        val saved = r.store.saved!!
        val q = Rig()
        q.store.saved = saved
        q.flow.start()
        assertEquals(Phase.IDLE, q.st.phase)
        assertEquals(r.st.position, q.st.position)
        // killed in the middle of a flip: the move is already saved, restore shows the flipped position
        val k = Rig()
        k.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        k.flow.tapSquare(sq("f5"))
        assertEquals(listOf(sq("f5")), k.store.saved!!.moves)
    }

    /** design dataModel: reset deletes the game and the first-run flag, keeps volume / language / angle. */
    @Test fun resetKeepsVolumeLanguageAngle() {
        val r = Rig()
        r.settings = Settings(language = Language.EN, volume = 1, tilt = 60, firstRunDone = true)
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER)); r.flow.tapSquare(sq("f5")); r.settle()
        r.flow.requestConfirm(ConfirmKind.RESET); r.flow.confirm()
        assertNull(r.store.saved)
        assertEquals(Phase.WELCOME, r.st.phase)
        assertFalse(r.settings.firstRunDone)
        assertEquals(Language.EN, r.settings.language); assertEquals(1, r.settings.volume); assertEquals(60, r.settings.tilt)
    }

    @Test fun newGameAsksOnceAMoveIsMade() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        r.flow.requestNew()
        assertEquals(Phase.SETUP, r.st.phase)
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        r.flow.tapSquare(sq("f5")); r.settle()
        r.flow.requestNew()
        assertEquals(ConfirmKind.NEW, r.st.confirm)
        r.flow.cancelConfirm()
        assertEquals(1, r.st.moves.size)
    }

    @Test fun gameEndsWithAResult() {
        val r = Rig()
        // a full random game, replayed: the flow reaches RESULT with the right counts
        val rnd = kotlin.random.Random(9)
        var pos = Position.START
        val moves = ArrayList<Int>()
        while (Rules.outcome(pos) == null) {
            pos = Rules.settle(pos).first
            val legal = Rules.squares(Rules.moves(pos))
            val m = legal[rnd.nextInt(legal.size)]
            moves += m; pos = Rules.play(pos, m)
        }
        r.flow.load(moves.dropLast(1), GameConfig(Mode.TWO_PLAYER))
        r.flow.tapSquare(moves.last()); r.settle()
        assertEquals(Phase.RESULT, r.st.phase)
        val res = Rules.outcome(pos)!!
        assertEquals(res, r.st.result)
        assertNull("finished games are not kept", r.store.saved)
    }

    @Test fun troughCounts() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.TWO_PLAYER))
        assertEquals(30, r.st.troughCount(Board.BLACK)); assertEquals(30, r.st.troughCount(Board.WHITE))
        assertEquals(Board.BLACK to 0, r.st.nextSlot(Board.BLACK))
        r.flow.tapSquare(sq("f5")); r.settle()
        assertEquals(29, r.st.troughCount(Board.BLACK))
        assertEquals(Board.BLACK to 1, r.st.nextSlot(Board.BLACK))
        assertEquals(28, r.st.troughCount(Board.BLACK, outBlack = 1))
    }

    @Test fun hintComesBackSilently() {
        val r = Rig()
        r.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 3))
        r.flow.toggleHint(); r.settle()
        val h = r.st.hint!!
        assertTrue(h.square in r.st.legal)
        assertEquals(HintKind.UNSURE, h.kind)
        assertTrue(r.sounds.isEmpty())
        r.flow.toggleHint()
        assertNull(r.st.hint)
    }
}

package io.embodit.flipdisc.ui.board.input

import io.embodit.flipdisc.domain.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** board-input README 必带单测: every kind of hit resolves to the right square / trough, replayed through resolvePath. */
class HitReplayTest {
    private fun sq(n: String) = Board.parse(n)!!
    private fun resolve(chain: List<String>) = resolvePath(chain, BoardNames::resolveName)

    @Test fun everyHitKind() {
        val f5 = sq("f5")
        assertEquals(FlipTarget.Square(f5), resolve(listOf(BoardNames.pad(f5), "boardRoot", "tiltRoot")))
        assertEquals(FlipTarget.Square(f5), resolve(listOf(BoardNames.tile(f5), BoardNames.pad(f5), "boardRoot")))
        assertEquals(FlipTarget.Square(f5), resolve(listOf(BoardNames.discTop(f5), BoardNames.piece(f5), "boardRoot")))
        assertEquals(FlipTarget.Square(f5), resolve(listOf(BoardNames.discBottom(f5), BoardNames.piece(f5))))
        assertEquals(FlipTarget.Trough(Board.BLACK), resolve(listOf(BoardNames.slot(Board.BLACK, 3), BoardNames.trough(Board.BLACK), "sceneRoot")))
        assertEquals(FlipTarget.Trough(Board.WHITE), resolve(listOf(BoardNames.troughBody(Board.WHITE), BoardNames.trough(Board.WHITE))))
        assertNull(resolve(listOf("frame", "boardRoot", "tiltRoot", "sceneRoot", "root")))
        assertNull(resolve(listOf("felt", "boardRoot")))
    }

    @Test fun outOfRangeIsNull() {
        assertNull(BoardNames.resolveName("pad:64"))
        assertNull(BoardNames.resolveName("piece:-1"))
        assertNull(BoardNames.resolveName("trough:green"))
        assertNull(BoardNames.resolveName("pad:"))
    }

    @Test fun everySquareRoundTrips() {
        for (i in 0 until 64) {
            for (n in listOf(BoardNames.pad(i), BoardNames.tile(i), BoardNames.piece(i), BoardNames.discTop(i))) assertEquals(FlipTarget.Square(i), BoardNames.resolveName(n))
        }
    }

    /** Real `path=` lines in the template's log format (from the emulator replay; headset lines get pasted here after the first device run). */
    @Test fun replayLoggedPaths() {
        val logged = listOf(
            "tile:37 < pad:37 < boardRoot < tiltRoot < sceneRoot < root" to FlipTarget.Square(37),
            "disc:27:top < piece:27 < boardRoot < tiltRoot < sceneRoot < root" to FlipTarget.Square(27),
            "slot:black:0 < trough:black < sceneRoot < root" to FlipTarget.Trough(Board.BLACK),
            "troughBody:white < trough:white < sceneRoot < root" to FlipTarget.Trough(Board.WHITE),
        )
        for ((path, want) in logged) assertEquals(path, want, resolve(parseLoggedPath(path)))
    }

    /** M? drag-delta: SDK 6.1.9 sends per-event deltas; 60 x 2 mm must total 12 cm. */
    @Test fun dragTotalSumsDeltas() {
        val t = DragTotal()
        var last = Offset3M(0f, 0f, 0f)
        repeat(60) { last = t.add(Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-5f)
        t.reset(); assertEquals(0f, t.x, 0f)
    }
}

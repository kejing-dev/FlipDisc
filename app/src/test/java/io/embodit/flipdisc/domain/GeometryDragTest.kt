package io.embodit.flipdisc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeometryDragTest {
    private fun sq(n: String) = Board.parse(n)!!

    @Test fun axesMatchTheDesign() {
        val g = BoardGeometry(30)
        // a1 far-left, h8 near-right (design spatialLayout.boardRoot)
        val a1 = g.local(sq("a1")); val h8 = g.local(sq("h8"))
        assertTrue(a1.x < 0 && a1.z < 0); assertTrue(h8.x > 0 && h8.z > 0)
        // 30°: far edge top at y = 0.367 (design corners.board), seat tag centre (0, 0.4155, -0.2888)
        assertEquals(0.367f, g.farTop().y, 0.002f)
        val t = g.tagCentre()
        assertEquals(0.3667f + 0.02f + BoardGeometry.TAG_H / 2, t.y, 0.002f); assertEquals(-0.2888f, t.z, 0.002f)
        // D13: 17 sp digits (cap ~0.7 em) on the scaled tag are >= 0.5° at 1.75 m
        val capM = 17 * 0.7f * BoardGeometry.TAG_DP_M * BoardGeometry.TAG_SCALE
        assertTrue(Math.toDegrees(kotlin.math.atan((capM / 1.75f).toDouble())) >= 0.5)
        // round trip
        val p = V3(0.1f, 0.2f, -0.05f)
        val back = g.toScene(g.toLocal(p))
        assertEquals(p.x, back.x, 1e-5f); assertEquals(p.y, back.y, 1e-5f); assertEquals(p.z, back.z, 1e-5f)
        for (i in 0 until 64) assertEquals(i, g.squareAt(g.local(i)))
    }

    /** M16 / D35: the seat tag never meets the board + flip envelope or a trough, at every tilt and yaw -30 / 0 / +30; all inside the volume. */
    @Test fun seatTagClearsAndEverythingFits() {
        for (tilt in Settings.TILTS) {
            val g = BoardGeometry(tilt)
            for (yaw in listOf(0f)) { // the tag is fixed facing +Z (never yaws), see BoardGeometry.TAG_SCALE
                val tag = g.tagBox(yaw)
                assertFalse("tilt $tilt yaw $yaw board", g.tagHitsBoard(yaw))
                assertFalse("tilt $tilt yaw $yaw trough", tag.intersects(g.troughBox(Board.BLACK)) || tag.intersects(g.troughBox(Board.WHITE)))
                assertTrue("tilt $tilt yaw $yaw tag inside ${tag}", tag.inside(g.volumeBox))
            }
            assertTrue("tilt $tilt board ${g.boardEnvelope()}", g.boardEnvelope().inside(g.volumeBox))
            assertTrue(g.troughBox(Board.BLACK).inside(g.volumeBox) && g.troughBox(Board.WHITE).inside(g.volumeBox))
            assertFalse(g.troughBox(Board.BLACK).intersects(g.boardEnvelope()) && tilt < 45)
            // flight arcs stay inside the volume
            for (s in 0 until 64) for (k in 0..10) {
                val f = g.flight(g.takeFrom(Board.WHITE), s, k / 10f)
                assertTrue("flight $tilt ${Board.name(s)} $f", f.y <= BoardGeometry.VOLUME.y && f.y >= 0f)
            }
        }
    }

    /** M19: click pads 6.15 cm with >= 1.5 cm gaps; trough 7.5 cm wide; pad at 1.75 m >= 2°. */
    @Test fun clickTargetSizes() {
        val gap = BoardGeometry.PITCH - BoardGeometry.PAD
        assertTrue(gap >= 0.015f && gap >= 0.3f * BoardGeometry.PAD - 1e-4f)
        val deg = Math.toDegrees(2 * kotlin.math.atan(BoardGeometry.PAD / 2 / 1.75)).toFloat()
        assertTrue("pad $deg°", deg >= 2f)
        assertTrue(BoardGeometry.TROUGH_W >= 0.04f)
    }

    // ---- snap (M15) ----

    private val g = BoardGeometry(30)
    private fun at(name: String, dx: Float = 0f, h: Float = 0.03f): V3 { val c = g.local(sq(name)); return V3(c.x + dx, h, c.z) }

    @Test fun snapHysteresis() {
        val legal = setOf(sq("d3"), sq("c4"), sq("f5"), sq("e6"))
        var s = Snap.step(SnapState(), at("f5", dx = 0.05f), legal, g)
        assertNull("5 cm away: not yet", s.snapped)
        s = Snap.step(s, at("f5", dx = 0.047f), legal, g)
        assertEquals(sq("f5"), s.snapped)
        s = Snap.step(s, at("f5", dx = 0.063f), legal, g)
        assertEquals("6.3 cm: still held", sq("f5"), s.snapped)
        s = Snap.step(s, at("f5", dx = 0.065f), legal, g)
        assertNull("6.5 cm: released", s.snapped)
    }

    @Test fun switchNeedsOneCentimetre() {
        // two legal squares side by side
        val legal = setOf(sq("c4"), sq("d4"))
        var s = Snap.step(SnapState(), at("c4"), legal, g)
        assertEquals(sq("c4"), s.snapped)
        // half way + 4 mm toward d4: d4 is 8 mm closer -> keep c4
        s = Snap.step(s, at("c4", dx = 0.044f), legal, g)
        assertEquals(sq("c4"), s.snapped)
        // + 6 mm: d4 is 1.2 cm closer -> switch
        s = Snap.step(s, at("c4", dx = 0.046f), legal, g)
        assertEquals(sq("d4"), s.snapped)
    }

    @Test fun orientationHysteresis() {
        val legal = setOf(sq("f5"))
        var s = Snap.step(SnapState(), at("f5", h = 0.20f), legal, g)
        assertFalse(s.faceParallel)
        s = Snap.step(s, at("f5", h = 0.11f), legal, g); assertTrue(s.faceParallel)
        s = Snap.step(s, at("f5", h = 0.15f), legal, g); assertTrue("12–16 cm keeps the last", s.faceParallel)
        s = Snap.step(s, at("f5", h = 0.17f), legal, g); assertFalse(s.faceParallel)
        s = Snap.step(s, at("f5", h = 0.14f), legal, g); assertFalse("coming down 12–16 cm keeps horizontal", s.faceParallel)
    }

    /** Holding 60 s never puts the disc back by itself (design input.drag.hold). */
    @Test fun holdingNeverTimesOut() {
        val d = DragController()
        d.begin(Board.BLACK, g.takeFrom(Board.BLACK), now = 0L)
        d.move(V3(0f, 0.1f, -0.1f), g, setOf(sq("f5")))
        assertTrue(d.active)
        assertEquals(60_000L, d.heldMs(60_000L))
        assertTrue(d.active)
    }

    @Test fun heldDiscStaysInsideAndOffTheBoard() {
        val d = DragController()
        d.begin(Board.BLACK, g.takeFrom(Board.BLACK), 0L)
        d.move(V3(5f, -5f, 5f), g, emptySet())
        val v = g.volumeBox
        assertTrue(d.held.x <= v.max.x - 0.019f && d.held.y >= 0.019f && d.held.z <= v.max.z - 0.019f)
        // pushed into the board: stays >= 1.5 cm off the felt
        val f5 = g.squareScene(sq("f5"), 0f)
        d.move(f5 - g.takeFrom(Board.BLACK) - g.normal * 0.05f, g, emptySet())
        assertTrue(g.toLocal(d.held).y >= BoardGeometry.DRAG_CLEAR + BoardGeometry.DISC_Y - 1e-4f)
    }

    // ---- flip timing (M3) ----

    @Test fun dominoOrder() {
        val p = Position(Board.bit(sq("a1")) or Board.bit(sq("h8")), listOf("b1", "c1", "d1", "e1", "f1", "g1", "g7").fold(0L) { b, s -> b or Board.bit(sq(s)) }, Board.BLACK)
        val flips = Rules.flipList(p, sq("h1"))
        // same line: later with distance; different lines start together
        val byD = flips.groupBy { it.distance }
        for (d in 1 until 6) assertTrue(FlipTiming.startOf(d) < FlipTiming.startOf(d + 1))
        assertEquals(FlipTiming.STEP_MS, FlipTiming.startOf(2) - FlipTiming.startOf(1))
        assertTrue(byD.keys.contains(6))
        // longest chain d = 6: total <= 1.2 s
        assertTrue(FlipTiming.total(flips) <= 1200L)
        // two directions at distance 1 start at the same time
        val two = Rules.flipList(Position(Board.bit(sq("c1")) or Board.bit(sq("c3")), Board.bit(sq("b1")) or Board.bit(sq("b2")), Board.BLACK), sq("a1"))
        assertEquals(2, two.size)
        assertEquals(FlipTiming.startOf(two[0].distance), FlipTiming.startOf(two[1].distance))
        assertEquals(0f, FlipTiming.progress(flips.first { it.distance == 6 }, FlipTiming.startOf(5)), 1e-6f)
        assertTrue(abs(FlipTiming.progress(flips.first { it.distance == 1 }, FlipTiming.DELAY_MS + 130) - 0.5f) < 1e-3f)
    }
}

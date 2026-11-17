package io.embodit.flipdisc.domain

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A small vector for layout maths (metres). Kept free of SDK types so it can be unit tested. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    val length: Float get() = sqrt(x * x + y * y + z * z)

    companion object { val ZERO = V3(0f, 0f, 0f) }
}

/** An axis-aligned box (scene metres), for the D35 / fit checks. */
data class Box3(val min: V3, val max: V3) {
    fun intersects(o: Box3): Boolean =
        min.x < o.max.x && max.x > o.min.x && min.y < o.max.y && max.y > o.min.y && min.z < o.max.z && max.z > o.min.z

    /** Inside [o] (0.1 mm float tolerance). */
    fun inside(o: Box3, eps: Float = 1e-4f): Boolean =
        min.x >= o.min.x - eps && min.y >= o.min.y - eps && min.z >= o.min.z - eps && max.x <= o.max.x + eps && max.y <= o.max.y + eps && max.z <= o.max.z + eps

    companion object {
        fun of(points: List<V3>): Box3 = Box3(
            V3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }),
            V3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }),
        )
    }
}

/**
 * Where everything sits in the volume (design-package spatialLayout). Scene space: origin on the volume floor, centred
 * in x, +Z toward the player, +Y up. Hierarchy:
 *
 *   sceneRoot (volume floor; content shifted CONTENT_Z)
 *   ├ tiltRoot  at the board's near-bottom edge, tipped [tiltDeg] about X (far edge up)
 *   │ └ boardRoot  felt centre; boardRoot y = 0 is the felt, row 0 (rank 1) far, row 7 (rank 8) near
 *   ├ troughs (never tilt), the seat tag (upright, above the far edge)
 */
class BoardGeometry(val tiltDeg: Int = Settings.DEFAULT_TILT) {
    companion object {
        const val PITCH = 0.08f
        const val FELT = 0.64f
        const val OUTER = 0.69f
        const val THICK = 0.025f
        const val CONTENT_Z = -0.0187f
        val VOLUME = V3(0.94f, 0.80f, 0.88f)

        /** Visible tile on every square (system hover is drawn on it): 4 x 4 cm, 2.5 mm proud. */
        const val TILE = 0.04f
        const val TILE_H = 0.0025f
        /** Click pad per square: 6.15 cm (gap 1.85 cm = 0.30 x edge, design input.clickTargets). */
        const val PAD = 0.0615f

        const val DISC_R = 0.03f
        const val DISC_H = 0.011f
        /** A disc on the board: its centre above the felt (bottom on the tile). */
        const val DISC_Y = TILE_H + DISC_H / 2

        const val TROUGH_W = 0.075f
        const val TROUGH_H = 0.03f
        const val TROUGH_L = 0.36f
        const val TROUGH_X = 0.4075f
        const val TROUGH_SLOTS = 30
        const val SLOT_STEP = 0.012f
        /** Standing discs in the trough: top 6.8 cm above the floor (3.8 cm out of the trough). */
        const val SLOT_Y = 0.068f - DISC_R

        const val FLIP_LIFT = 0.03f
        const val SNAP_LIFT = 0.015f
        const val SNAP_RAISE = 0.004f
        const val ARC = 0.08f

        /** Attachment panels render at ~0.0008 m per dp (measured on five-stones); the seat tag is 280 x 72 dp. */
        const val TAG_DP_M = 0.0008f
        /**
         * D13: the tag (280 x 60 dp) is drawn 1.65x (37 x 7.9 cm) so its 17 sp digits reach >= 0.5° cap height at 1.75 m;
         * the design's 280 x 72 dp at 1x read at ~0.3° on the emulator (deviation). It never yaws (fixed facing +Z).
         */
        const val TAG_SCALE = 1.65f
        const val TAG_DP_W = 280
        const val TAG_DP_H = 60
        const val TAG_W = TAG_DP_W * TAG_DP_M * TAG_SCALE
        const val TAG_H = TAG_DP_H * TAG_DP_M * TAG_SCALE
        const val TAG_GAP = 0.02f
        const val TAG_BACK = 0.06f

        /** Drag: the held disc stays 2 cm inside the volume and at least 1.5 cm off the board. */
        const val DRAG_INSET = 0.02f
        const val DRAG_CLEAR = 0.015f
    }

    val theta: Float = Math.toRadians(tiltDeg.toDouble()).toFloat()

    /** tiltRoot position in scene space: the near-bottom edge of the board. */
    val tiltOrigin: V3 = V3(0f, 0f, CONTENT_Z + OUTER / 2)
    /** boardRoot relative to tiltRoot (before rotation): up by the thickness, back by half the board. */
    val boardOffset: V3 = V3(0f, THICK, -OUTER / 2)

    /** Board-local (felt y = 0) position of a square centre. */
    fun local(sq: Int, lift: Float = 0f): V3 = V3((Board.col(sq) - 3.5f) * PITCH, lift, (Board.row(sq) - 3.5f) * PITCH)

    private fun rotX(v: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }

    /** Scene position of a board-local position. */
    fun toScene(local: V3): V3 = tiltOrigin + rotX(boardOffset + local, theta)

    /** Board-local position of a scene position (inverse of [toScene]). */
    fun toLocal(scene: V3): V3 = rotX(scene - tiltOrigin, -theta) - boardOffset

    /** Board-local direction of a scene direction. */
    fun dirToLocal(d: V3): V3 = rotX(d, -theta)

    fun squareScene(sq: Int, lift: Float = DISC_Y): V3 = toScene(local(sq, lift))

    /** The board normal in scene space. */
    val normal: V3 get() = rotX(V3(0f, 1f, 0f), theta)

    /** The square under a board-local point, or null off the felt. */
    fun squareAt(local: V3): Int? {
        val c = kotlin.math.floor(local.x / PITCH + 4f).toInt()
        val r = kotlin.math.floor(local.z / PITCH + 4f).toInt()
        return if (Board.inside(c, r)) Board.at(c, r) else null
    }

    // ---------------------------------------------------------------- troughs

    /** Trough centre (on the floor): Black's on the left, White's on the right; near end flush with the board's near edge. */
    fun troughBase(color: Int): V3 = V3(if (color == Board.BLACK) -TROUGH_X else TROUGH_X, 0f, tiltOrigin.z - TROUGH_L / 2)

    /** Slot [i] of a trough (0 = the near end, taken first): the centre of a standing disc. */
    fun slot(color: Int, i: Int): V3 = V3(troughBase(color).x, SLOT_Y, tiltOrigin.z - SLOT_STEP / 2 - i * SLOT_STEP)

    /** The disc a move takes: the near-end disc of [color]'s trough (design: from the board end). */
    fun takeFrom(color: Int): V3 = slot(color, 0)

    // ---------------------------------------------------------------- flight

    /**
     * The flight from the trough to [sq] at time fraction [t] (0..1), scene space: apex = the higher end + 8 cm (design
     * animation.place: above the highest board point on the path), kept 2 cm under the volume top.
     */
    fun flight(from: V3, sq: Int, t: Float): V3 {
        val a = from
        val b = squareScene(sq)
        val k = t.coerceIn(0f, 1f)
        val apex = min(max(a.y, b.y) + ARC, VOLUME.y - DRAG_INSET)
        val mid = (a.y + b.y) / 2
        return a.lerp(b, k) + V3(0f, 4f * (apex - mid) * k * (1f - k), 0f)
    }

    // ---------------------------------------------------------------- seat tag

    /** The far-edge top line in scene space. */
    fun farTop(): V3 = toScene(V3(0f, 0f, -OUTER / 2))

    /** Centre of the seat tag: upright, 2 cm above the far edge, stepping back 6 cm x sin(tilt) at steep angles. */
    fun tagCentre(): V3 = farTop() + V3(0f, TAG_GAP + TAG_H / 2, -TAG_BACK * sin(theta))

    /** Seat tag corners after a yaw of [yawDeg] about its centre (the system may turn it toward the viewer). */
    fun tagCorners(yawDeg: Float = 0f): List<V3> {
        val c = tagCentre()
        val a = Math.toRadians(yawDeg.toDouble()).toFloat()
        val hx = TAG_W / 2; val hy = TAG_H / 2
        return listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).map { (sx, sy) ->
            V3(c.x + sx * hx * cos(a), c.y + sy * hy, c.z - sx * hx * sin(a))
        }
    }

    // ---------------------------------------------------------------- envelopes (D35, fit)

    /** The board slab plus every disc's flip envelope (centre lifted 3 cm, radius 3 cm edge-on). */
    fun boardEnvelope(): Box3 {
        val h = OUTER / 2
        val top = DISC_Y + FLIP_LIFT + DISC_R
        val pts = ArrayList<V3>()
        for (x in listOf(-h, h)) for (z in listOf(-h, h)) for (y in listOf(-THICK, top)) pts += toScene(V3(x, y, z))
        return Box3.of(pts)
    }

    /**
     * Does the seat tag (yawed by [yawDeg] about its centre, 2 mm thick) touch the board slab or any disc's flip envelope?
     * Checked in board-local space on a grid of points over the tag, so the tilted board is not over-approximated by an AABB.
     */
    fun tagHitsBoard(yawDeg: Float): Boolean {
        val c = tagCentre()
        val a = Math.toRadians(yawDeg.toDouble()).toFloat()
        val discEdge = 3.5f * PITCH + DISC_R + 0.0005f
        val discTop = DISC_Y + FLIP_LIFT + DISC_R
        val h = OUTER / 2
        for (i in 0..28) for (j in 0..6) for (t in listOf(-0.001f, 0.001f)) {
            val u = (i / 28f - 0.5f) * TAG_W; val v = (j / 6f - 0.5f) * TAG_H
            val p = V3(c.x + u * cos(a) + t * sin(a), c.y + v, c.z - u * sin(a) + t * cos(a))
            val l = toLocal(p)
            val inSlab = kotlin.math.abs(l.x) <= h && kotlin.math.abs(l.z) <= h && l.y in -THICK..0f
            val inDiscs = kotlin.math.abs(l.x) <= discEdge && kotlin.math.abs(l.z) <= discEdge && l.y in 0f..discTop
            if (inSlab || inDiscs) return true
        }
        return false
    }

    fun troughBox(color: Int): Box3 {
        val b = troughBase(color)
        return Box3(V3(b.x - TROUGH_W / 2, 0f, b.z - TROUGH_L / 2), V3(b.x + TROUGH_W / 2, SLOT_Y + DISC_R, b.z + TROUGH_L / 2))
    }

    fun tagBox(yawDeg: Float = 0f): Box3 {
        val c = tagCorners(yawDeg)
        val pad = 0.002f
        return Box3.of(c).let { Box3(it.min - V3(pad, 0f, pad), it.max + V3(pad, 0f, pad)) }
    }

    /** The volume box in scene space (floor at y = 0). */
    val volumeBox: Box3 = Box3(V3(-VOLUME.x / 2, 0f, -VOLUME.z / 2), V3(VOLUME.x / 2, VOLUME.y, VOLUME.z / 2))

    // ---------------------------------------------------------------- drag

    /** A held disc's position, clamped 2 cm inside the volume and kept at least 1.5 cm off the board (design input.drag.follow). */
    fun clampHeld(p: V3): V3 {
        val i = DRAG_INSET
        var q = V3(
            p.x.coerceIn(volumeBox.min.x + i, volumeBox.max.x - i),
            p.y.coerceIn(volumeBox.min.y + i, volumeBox.max.y - i),
            p.z.coerceIn(volumeBox.min.z + i, volumeBox.max.z - i),
        )
        val l = toLocal(q)
        val over = kotlin.math.abs(l.x) <= OUTER / 2 && kotlin.math.abs(l.z) <= OUTER / 2
        if (over && l.y < DRAG_CLEAR + DISC_Y && l.y > -THICK - DISC_R) q = toScene(V3(l.x, DRAG_CLEAR + DISC_Y, l.z))
        return q
    }
}

/** A unit quaternion (x, y, z, w) for orientation maths without SDK types (the scene converts to Quat(x, y, z, w)). */
data class Q4(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Q4) = Q4(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun rotate(v: V3): V3 {
        val p = this * Q4(v.x, v.y, v.z, 0f) * Q4(-x, -y, -z, w)
        return V3(p.x, p.y, p.z)
    }

    fun slerp(o: Q4, t: Float): Q4 {
        var b = o
        var d = x * o.x + y * o.y + z * o.z + w * o.w
        if (d < 0f) { b = Q4(-o.x, -o.y, -o.z, -o.w); d = -d }
        if (d > 0.9995f) return Q4(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t).normalized()
        val a = kotlin.math.acos(d.coerceIn(-1f, 1f))
        val s = sin(a)
        val k0 = sin((1 - t) * a) / s; val k1 = sin(t * a) / s
        return Q4(x * k0 + b.x * k1, y * k0 + b.y * k1, z * k0 + b.z * k1, w * k0 + b.w * k1)
    }

    fun normalized(): Q4 { val n = sqrt(x * x + y * y + z * z + w * w); return Q4(x / n, y / n, z / n, w / n) }

    companion object {
        val IDENTITY = Q4(0f, 0f, 0f, 1f)
        fun axisAngle(axis: V3, angle: Float): Q4 {
            val n = axis.length.takeIf { it > 1e-6f } ?: return IDENTITY
            val s = sin(angle / 2) / n
            return Q4(axis.x * s, axis.y * s, axis.z * s, cos(angle / 2))
        }
    }
}

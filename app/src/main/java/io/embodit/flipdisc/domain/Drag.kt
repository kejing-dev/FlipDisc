package io.embodit.flipdisc.domain

import kotlin.math.hypot

/**
 * Snap state of a held disc (design input.drag): position hysteresis 4.8 / 6.4 cm with a 1 cm margin to change square,
 * orientation hysteresis 12 / 16 cm. Pure: feed it board-local positions, read [snapped] / [faceParallel].
 */
data class SnapState(val snapped: Int? = null, val faceParallel: Boolean = false)

object Snap {
    const val SNAP_IN = 0.6f * BoardGeometry.PITCH // 4.8 cm
    const val SNAP_OUT = 0.8f * BoardGeometry.PITCH // 6.4 cm
    const val SWITCH_MARGIN = 0.01f
    const val FACE_IN = 0.12f
    const val FACE_OUT = 0.16f
    /** Sanity guard (not in the design): a disc held this far off the board never snaps. */
    const val MAX_SNAP_HEIGHT = 0.25f

    fun planar(local: V3, sq: Int, geo: BoardGeometry): Float {
        val c = geo.local(sq)
        return hypot(local.x - c.x, local.z - c.z)
    }

    fun step(prev: SnapState, local: V3, legal: Set<Int>, geo: BoardGeometry): SnapState {
        val h = local.y
        val face = when {
            h <= FACE_IN -> true
            h > FACE_OUT -> false
            else -> prev.faceParallel
        }
        if (legal.isEmpty() || h > MAX_SNAP_HEIGHT) return SnapState(null, face)
        val nearest = legal.minByOrNull { planar(local, it, geo) }!!
        val dn = planar(local, nearest, geo)
        val cur = prev.snapped?.takeIf { it in legal }
        val snapped = if (cur != null) {
            val dc = planar(local, cur, geo)
            when {
                dc > SNAP_OUT -> if (dn <= SNAP_IN) nearest else null
                nearest != cur && dn <= SNAP_IN && dn <= dc - SWITCH_MARGIN -> nearest
                else -> cur
            }
        } else if (dn <= SNAP_IN) nearest else null
        return SnapState(snapped, face)
    }
}

/**
 * The disc in the hand. Lives outside Compose state: the scene reads [held] every frame (D: high-rate input never goes
 * back through 2D state); the flow only hears about snap changes. All positions are scene metres.
 */
class DragController {
    @Volatile var color: Int = 0; private set
    @Volatile var active: Boolean = false; private set
    @Volatile var held: V3 = V3.ZERO; private set
    @Volatile var snap: SnapState = SnapState(); private set
    private var start: V3 = V3.ZERO
    private var startedAtMs = 0L

    fun begin(color: Int, from: V3, now: Long) {
        this.color = color
        start = from
        held = from
        snap = SnapState()
        active = true
        startedAtMs = now
    }

    /** [total] = movement since the press in volume metres (already divided by the volume's scale). Returns true if the snap changed. */
    fun move(total: V3, geo: BoardGeometry, legal: Set<Int>): Boolean {
        if (!active) return false
        held = geo.clampHeld(start + total)
        val next = Snap.step(snap, geo.toLocal(held), legal, geo)
        val changed = next.snapped != snap.snapped
        snap = next
        return changed
    }

    fun heldMs(now: Long): Long = if (active) now - startedAtMs else 0L

    fun end() { active = false }
}

/** The domino timing of one move (design animation.flip): 60 ms after landing, then 120 ms per ring, 260 ms each. */
object FlipTiming {
    const val DELAY_MS = 60L
    const val STEP_MS = 120L
    const val FLIP_MS = 260L

    fun startOf(distance: Int): Long = DELAY_MS + STEP_MS * (distance - 1)

    fun total(flips: List<Flip>): Long = if (flips.isEmpty()) 0L else startOf(flips.maxOf { it.distance }) + FLIP_MS

    /** 0..1 progress of [f] at [ms] after landing. */
    fun progress(f: Flip, ms: Long): Float = ((ms - startOf(f.distance)).toFloat() / FLIP_MS).coerceIn(0f, 1f)
}

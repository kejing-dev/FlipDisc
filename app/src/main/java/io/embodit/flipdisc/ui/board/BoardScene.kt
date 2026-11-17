package io.embodit.flipdisc.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.flipdisc.domain.Board
import io.embodit.flipdisc.domain.BoardGeometry
import io.embodit.flipdisc.domain.DragController
import io.embodit.flipdisc.domain.FlipTiming
import io.embodit.flipdisc.domain.GameFlow
import io.embodit.flipdisc.domain.HintKind
import io.embodit.flipdisc.domain.Phase
import io.embodit.flipdisc.domain.PlayState
import io.embodit.flipdisc.domain.Q4
import io.embodit.flipdisc.domain.Settings
import io.embodit.flipdisc.domain.V3
import io.embodit.flipdisc.domain.AlertKind
import io.embodit.flipdisc.platform.TAG
import io.embodit.flipdisc.ui.board.input.BoardNames
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Every entity of the board volume. Compose hands over [PlayState] + [Settings] through [apply]; per-frame motion
 * (the flight, the domino flips, the held disc, the tilt easing, the result ripple) runs in [onFrame] from
 * [BoardFrameSystem], always on wall-clock time (D55).
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ tiltRoot (near-bottom board edge, tipped about X)
 *   │ └ boardRoot (felt centre)
 *   │   ├ frame, felt (Canvas texture), 64 pads "pad:<i>" (collider) with a visible tile "tile:<i>"
 *   │   ├ 64 discs "piece:<i>" (collider) with halves "disc:<i>:top|bottom", legal / snap rings, hint mark, frame, dot
 *   ├ troughs "trough:black|white" (collider) with "troughBody" and 30 standing "slot:<c>:<k>" discs
 *   ├ flyer (the disc in the hand / in the air)
 *   └ seat tag (AttachmentPanel, upright above the far edge)
 * ```
 */
internal class BoardScene(private val drag: DragController) {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var tag: Entity? = null

    private var geo = BoardGeometry()
    private var shownTilt = Settings.DEFAULT_TILT.toFloat()
    private var tiltFrom = shownTilt
    private var tiltTo = shownTilt
    private var tiltAt = 0L
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f
    var volumeScale = 1f; private set

    private val discs = arrayOfNulls<Entity>(64)
    private val discMaterials = arrayOfNulls<Pair<ModelEntity, ModelEntity>>(64)
    private var discsBuilt = 0
    private val pads = ArrayList<Entity>()
    private val tiles = ArrayList<Entity>()
    private var padsBuilt = 0
    private val rings = ArrayList<ModelEntity>()
    private var snapRing: ModelEntity? = null
    private var hintMark: ModelEntity? = null
    private var frameMark: ModelEntity? = null
    private var lastDot: ModelEntity? = null
    private var flyer: Entity? = null
    private val troughs = HashMap<Int, Entity>()
    private val slots = HashMap<Int, List<Entity>>()

    // animation state
    private var clock = 0f
    private var flyerPos: V3? = null
    private var flyerRot = Q4.IDENTITY
    private var animDirty = false
    /** Debug hold for the S03x screenshot: keep the illegal frame on. */
    @Volatile var holdFrame = false

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
    }

    val ready: Boolean get() = built

    /** The volume's half height from the SpatialView; the floor is at -half. Also gives the volume's scale for drags. */
    fun setRegion(halfY: Float) {
        if (abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY, 0f))
        volumeScale = (2 * halfY / BoardGeometry.VOLUME.y).takeIf { it > 0.05f } ?: 1f
        Log.i(TAG, "SCENE region halfY=$halfY scale=$volumeScale")
    }

    /** S00 stand-in: a plain felt slab and four discs, shown until the real board is built (design container.lifecycle). */
    fun showFallback(slab: MeshResource, disc: MeshResource, felt: Material, black: Material, ivory: Material) {
        if (built || fallback.isNotEmpty()) return
        val g = geo
        val board = ModelEntity(slab, felt).apply { setName("fallbackBoard") }
        place(board, g.toScene(V3(0f, -BoardGeometry.THICK / 2, 0f)))
        board.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), g.theta))
        val four = listOf("d4" to ivory, "e4" to black, "d5" to black, "e5" to ivory).map { (n, m) ->
            ModelEntity(disc, m).apply {
                setName("fallbackDisc:$n")
                place(this, g.squareScene(Board.parse(n)!!))
                transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), g.theta))
            }
        }
        fallback = listOf(board) + four
        fallback.forEach { sceneRoot.addChild(it) }
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))
    private fun rotate(e: Entity, q: Q4) = e.transform().setQuaternion(Quat(q.x, q.y, q.z, q.w))

    fun install(r: SceneResources, tagEntity: Entity?) {
        if (built) { r.close(); return }
        res = r
        tag = tagEntity
        val t0 = SystemClock.elapsedRealtime()
        boardRoot.addChild(ModelEntity(r.frame, r.walnut).apply { setName("frame"); transform().setPosition(Vector3(0f, -BoardGeometry.THICK / 2, 0f)) })
        boardRoot.addChild(ModelEntity(r.felt, r.feltMat).apply {
            setName("felt")
            transform().setPosition(Vector3(0f, 0.0004f, 0f))
            transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
        })
        // troughs: a collider holder per side with the walnut body and 30 standing discs as visible children (system hover lights them)
        for (c in listOf(Board.BLACK, Board.WHITE)) {
            val holder = Entity().apply {
                setName(BoardNames.trough(c))
                components.set(CollisionComponent(collisionShape = listOf(r.troughShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
            }
            val base = geo.troughBase(c)
            val shapeY = (BoardGeometry.SLOT_Y + BoardGeometry.DISC_R) / 2
            // the collider is centred on the holder: the holder sits at half the trough + disc height, children are offset down
            place(holder, V3(base.x, shapeY, base.z))
            holder.addChild(ModelEntity(r.trough, r.walnutTrough).apply { setName(BoardNames.troughBody(c)); place(this, V3(0f, BoardGeometry.TROUGH_H / 2 - shapeY, 0f)) })
            val standing = Q4.axisAngle(V3(1f, 0f, 0f), (if (c == Board.BLACK) PI / 2 else -PI / 2).toFloat())
            slots[c] = (0 until BoardGeometry.TROUGH_SLOTS).map { i ->
                discEntity(r, BoardNames.slot(c, i)).also { e ->
                    val s = geo.slot(c, i)
                    place(e, V3(s.x - base.x, s.y - shapeY, s.z - base.z))
                    rotate(e, standing)
                    holder.addChild(e)
                }
            }
            troughs[c] = holder
            sceneRoot.addChild(holder)
        }
        repeat(24) { i -> rings += ModelEntity(r.legalRing, r.legalMat).apply { setName("legal:$i"); enabled = false }.also { boardRoot.addChild(it) } }
        snapRing = ModelEntity(r.snapRing, r.snapMat).apply { setName("snapRing"); enabled = false }.also { boardRoot.addChild(it) }
        hintMark = flatMark(r, r.hintSure, "hintMark")
        frameMark = flatMark(r, r.illegalMat, "illegalFrame")
        lastDot = ModelEntity(r.dot, r.dotMat).apply { setName("lastDot"); enabled = false }.also { boardRoot.addChild(it) }
        flyer = discEntity(r, "flyer").also { it.enabled = false; sceneRoot.addChild(it) }
        tag?.let { sceneRoot.addChild(it); it.transform().setScaleVector(Vector3(BoardGeometry.TAG_SCALE, BoardGeometry.TAG_SCALE, BoardGeometry.TAG_SCALE)) }

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        layout(force = true)
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms (pads + discs follow over 4 frames)")
        apply(state, settings, force = true)
    }

    private fun flatMark(r: SceneResources, m: Material, name: String): ModelEntity =
        ModelEntity(r.markPlane, m).apply {
            setName(name); enabled = false
            transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
        }.also { boardRoot.addChild(it) }

    /** A two-colour disc: black half on top (+Y), ivory half below; no collider (the caller adds one if pickable). */
    private fun discEntity(r: SceneResources, name: String): Entity {
        val e = Entity().apply { setName(name) }
        val q = BoardGeometry.DISC_H / 4
        val top = ModelEntity(r.discHalf, r.black).apply { setName("$name:top"); transform().setPosition(Vector3(0f, q, 0f)) }
        val bottom = ModelEntity(r.discHalf, r.ivory).apply { setName("$name:bottom"); transform().setPosition(Vector3(0f, -q, 0f)) }
        e.addChild(top); e.addChild(bottom)
        return e
    }

    /** 64 pads and 64 board discs, 16 per frame (design budget.startup: "64 子分 4 帧建"). */
    private fun buildSome() {
        val r = res ?: return
        val end = min(64, padsBuilt + 16)
        for (i in padsBuilt until end) {
            // board-input 通用模式: a full-cell click pad (Collision + Interactable + HoverEffect) whose visible 4 cm tile the system lights.
            val pad = Entity().apply {
                setName(BoardNames.pad(i))
                place(this, geo.local(i, BoardGeometry.TILE_H / 2))
                components.set(CollisionComponent(collisionShape = listOf(r.padShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
            }
            val tile = ModelEntity(r.tile, r.tileMat).apply { setName(BoardNames.tile(i)) }
            pad.addChild(tile)
            tiles += tile
            boardRoot.addChild(pad)
            pads += pad
            // the disc that will ever sit on this square (discs never move in Reversi, so square = disc id)
            val d = Entity().apply {
                setName(BoardNames.piece(i))
                components.set(CollisionComponent(collisionShape = listOf(r.discShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
                enabled = false
            }
            val q = BoardGeometry.DISC_H / 4
            val top = ModelEntity(r.discHalf, r.black).apply { setName(BoardNames.discTop(i)); transform().setPosition(Vector3(0f, q, 0f)) }
            val bottom = ModelEntity(r.discHalf, r.ivory).apply { setName(BoardNames.discBottom(i)); transform().setPosition(Vector3(0f, -q, 0f)) }
            d.addChild(top); d.addChild(bottom)
            discMaterials[i] = top to bottom
            boardRoot.addChild(d)
            discs[i] = d
        }
        padsBuilt = end
        discsBuilt = end
        if (padsBuilt == 64) { Log.i(TAG, "PADS + DISCS built"); animDirty = true; apply(state, settings, force = true) }
    }

    private fun layout(force: Boolean = false) {
        val want = settings.tilt.toFloat()
        if (!force && want == tiltTo) return
        tiltFrom = shownTilt; tiltTo = want; tiltAt = SystemClock.uptimeMillis()
        if (force) { tiltFrom = want; shownTilt = want }
        geo = BoardGeometry(settings.tilt)
        applyTilt(shownTilt)
        Log.i(TAG, "LAYOUT tilt=${geo.tiltDeg} tag=${geo.tagCentre()} farTop=${geo.farTop()}")
    }

    /** The board turns about its near edge over 300 ms (design animation.tilt); the seat tag follows the far edge, upright. */
    private fun applyTilt(deg: Float) {
        shownTilt = deg
        val g = BoardGeometry(deg.toInt())
        val theta = Math.toRadians(deg.toDouble()).toFloat()
        place(tiltRoot, g.tiltOrigin)
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), theta))
        place(boardRoot, g.boardOffset)
        val far = g.tiltOrigin + V3(0f, (g.boardOffset.y) * kotlin.math.cos(theta) + BoardGeometry.OUTER * sin(theta), g.boardOffset.y * sin(theta) - BoardGeometry.OUTER * kotlin.math.cos(theta))
        tag?.let { place(it, far + V3(0f, BoardGeometry.TAG_GAP + BoardGeometry.TAG_H / 2, -BoardGeometry.TAG_BACK * sin(theta))) }
    }

    // ---- state ----

    fun apply(next: PlayState, s: Settings, force: Boolean = false) {
        val prev = state
        state = next
        val settingsChanged = s != settings
        settings = s
        if (!built) return
        if (settingsChanged || force) layout()
        val r = res ?: return
        if (next.anim != null || prev.anim != null || next.position != prev.position || force) animDirty = true

        // troughs: the discs still in them (the held / returning disc is out)
        val outB = (if (next.dragColor == Board.BLACK || next.back?.color == Board.BLACK) 1 else 0)
        val outW = (if (next.dragColor == Board.WHITE || next.back?.color == Board.WHITE) 1 else 0)
        for (c in listOf(Board.BLACK, Board.WHITE)) {
            val n = next.troughCount(c, outB, outW)
            slots[c]?.forEachIndexed { i, e -> e.enabled = i >= BoardGeometry.TROUGH_SLOTS - n }
        }

        // legal rings (the snapped one turns into the snap ring)
        val showLegal = next.phase in setOf(Phase.IDLE, Phase.DRAGGING) && next.humansTurn
        val legal = if (showLegal) next.legal.filter { it != next.snapped }.sorted() else emptyList()
        rings.forEachIndexed { i, e ->
            val sq = legal.getOrNull(i)
            if (sq != null) { place(e, geo.local(sq, BoardGeometry.TILE_H + 0.0009f)); e.enabled = true } else e.enabled = false
        }
        snapRing?.let { e -> val sq = next.snapped; if (sq != null) { place(e, geo.local(sq, BoardGeometry.TILE_H + 0.001f)); e.enabled = true } else e.enabled = false }

        // hint mark: solid / dashed amber, thin grey (design gameSpec.hint.states)
        hintMark?.let { e ->
            val h = next.hint
            if (h != null && next.phase == Phase.IDLE) {
                e.components[ModelComponent::class.java]?.materials?.set(0, when (h.kind) { HintKind.SURE -> r.hintSure; HintKind.UNSURE -> r.hintUnsure; HintKind.LOST -> r.hintLost })
                place(e, geo.local(h.square, BoardGeometry.TILE_H + 0.0014f)); e.enabled = true
            } else e.enabled = false
        }
        onFrame(0f)
    }

    // ---- per frame ----

    private fun base(color: Int): Q4 = if (color == Board.WHITE) Q4.axisAngle(V3(1f, 0f, 0f), PI.toFloat()) else Q4.IDENTITY

    private fun tiltQ(): Q4 = Q4.axisAngle(V3(1f, 0f, 0f), Math.toRadians(shownTilt.toDouble()).toFloat())

    private fun standing(c: Int): Q4 = Q4.axisAngle(V3(1f, 0f, 0f), (if (c == Board.BLACK) PI / 2 else -PI / 2).toFloat())

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        if (padsBuilt < 64) buildSome()
        val now = SystemClock.uptimeMillis()
        val st = state

        // tilt easing (300 ms)
        if (shownTilt != tiltTo) {
            val k = ((now - tiltAt) / 300f).coerceIn(0f, 1f)
            val e = 1f - (1f - k) * (1f - k)
            applyTilt(if (k >= 1f) tiltTo else tiltFrom + (tiltTo - tiltFrom) * e)
            geo = BoardGeometry(settings.tilt)
        }

        val anim = st.anim
        val animMs = anim?.let { a -> (now - a.startedAt).let { el -> st.freezeMs?.let { f -> min(el, f) } ?: el } } ?: 0L
        if (animDirty || anim != null || st.result != null || st.snapped != null) updateDiscs(st, now, animMs)

        // the flyer: tap flight / drop after a drag / held in the hand / flying back
        val fl = flyer
        if (fl != null) {
            val g = BoardGeometry(shownTilt.toInt())
            var pos: V3? = null
            var rot = flyerRot
            var color: Int
            if (anim != null && animMs < anim.landMs) {
                color = anim.color
                val end = g.squareScene(anim.square)
                if (anim.fromDrag) {
                    val k = (animMs.toFloat() / GameFlow.DROP_MS).coerceIn(0f, 1f)
                    pos = g.squareScene(anim.square, BoardGeometry.DISC_Y + BoardGeometry.SNAP_LIFT * (1f - k))
                    rot = tiltQ() * base(color)
                } else {
                    val from = g.slot(anim.fromTrough, anim.fromSlot)
                    val k = (animMs.toFloat() / GameFlow.FLIGHT_MS).coerceIn(0f, 1f)
                    pos = if (animMs <= GameFlow.FLIGHT_MS) g.flight(from, anim.square, k)
                    else end - g.normal * (0.002f * sin(PI.toFloat() * (animMs - GameFlow.FLIGHT_MS) / GameFlow.LAND_MS))
                    rot = standing(anim.fromTrough).slerp(tiltQ() * base(color), smooth(k))
                }
            } else if (drag.active) {
                color = drag.color
                val snapped = drag.snap.snapped
                val target = if (snapped != null) g.squareScene(snapped, BoardGeometry.DISC_Y + BoardGeometry.SNAP_LIFT) else drag.held
                val prevPos = flyerPos ?: target
                val a = if (dt <= 0f) 1f else 1f - exp(-dt / 0.04f)
                pos = prevPos.lerp(target, a)
                val want = if (drag.snap.faceParallel || snapped != null) tiltQ() * base(color) else base(color)
                val b = if (dt <= 0f) 1f else 1f - exp(-dt / 0.047f) // ~140 ms to settle
                rot = (if (flyerPos == null) standing(color) else flyerRot).slerp(want, b)
            } else st.back?.let { back ->
                color = back.color
                val (tc, slot) = st.nextSlot(back.color)
                val to = g.slot(tc, slot)
                val k = ((now - back.startedAt).toFloat() / GameFlow.RETURN_MS).coerceIn(0f, 1f)
                pos = back.from.lerp(to, k) + V3(0f, 0.06f * 4f * k * (1f - k), 0f)
                rot = flyerRot.slerp(standing(tc), smooth(k))
            }
            if (pos != null) {
                place(fl, pos); rotate(fl, rot)
                flyerPos = pos; flyerRot = rot
                fl.enabled = true
            } else {
                fl.enabled = false; flyerPos = null; flyerRot = Q4.IDENTITY
            }
        }

        // illegal frame: 160 ms on the square (S03x), kept by the debug hold
        frameMark?.let { e ->
            val al = st.alert
            if (al != null && al.square != null && al.kind != AlertKind.TIP && (now - al.at < GameFlow.FRAME_MS || holdFrame)) {
                place(e, geo.local(al.square, BoardGeometry.TILE_H + 0.0016f)); e.enabled = true
            } else e.enabled = false
        }
    }

    private fun smooth(k: Float) = k * k * (3f - 2f * k)

    /** Board discs: static colour from the position, the placed one hidden until it lands, flipped ones turning on the domino clock. */
    private fun updateDiscs(st: PlayState, now: Long, animMs: Long) {
        if (discsBuilt < 64) return
        animDirty = false
        val anim = st.anim
        val flipping = anim?.flips?.associateBy { it.square }.orEmpty()
        val raised = st.snapped?.let { sq -> io.embodit.flipdisc.domain.Rules.flipList(st.position, sq).map { it.square }.toSet() }.orEmpty()
        val winner = st.result?.winner ?: 0
        val ripple = if (winner != 0) now - st.resultAt else -1L
        for (i in 0 until 64) {
            val d = discs[i] ?: continue
            val color = st.position.at(i)
            val placedHidden = anim != null && i == anim.square && animMs < anim.landMs
            if (color == Board.EMPTY || placedHidden) { d.enabled = false; continue }
            d.enabled = true
            val f = flipping[i]
            if (f != null && anim != null) {
                val t = FlipTiming.progress(f, animMs - anim.landMs)
                val from = anim.before.at(i)
                val c = geo.local(anim.square); val p = geo.local(i)
                val dir = V3(p.x - c.x, 0f, p.z - c.z)
                val axis = V3(dir.z, 0f, -dir.x) // up x dir: the edge nearest the placed disc rises first
                val e = smooth(t)
                place(d, geo.local(i, BoardGeometry.DISC_Y + BoardGeometry.FLIP_LIFT * sin(PI.toFloat() * e)))
                rotate(d, Q4.axisAngle(axis, PI.toFloat() * e) * base(from))
                if (t < 1f) animDirty = true
                continue
            }
            var lift = BoardGeometry.DISC_Y
            if (i in raised) lift += BoardGeometry.SNAP_RAISE
            if (ripple >= 0 && color == winner) {
                val ring = hypot(Board.col(i) - 3.5f, Board.row(i) - 3.5f)
                val t = ((ripple - ring * 70f) / 180f).coerceIn(0f, 1f)
                lift += 0.006f * sin(PI.toFloat() * t)
                if (ripple < 1200) animDirty = true
            }
            place(d, geo.local(i, lift))
            rotate(d, base(color))
        }
        lastDot?.let { e ->
            val sq = st.lastMove
            if (sq != null && (anim == null || animMs >= anim.landMs) && st.position.at(sq) != Board.EMPTY) {
                place(e, geo.local(sq, BoardGeometry.DISC_Y + BoardGeometry.DISC_H / 2 + 0.0007f)); e.enabled = true
            } else e.enabled = false
        }
    }

    /** Debug replay: the real entities a hit on [sq] can report, the deepest pad child last. */
    fun replaySquare(sq: Int): List<Entity> {
        if (sq !in pads.indices) return emptyList()
        val disc = discMaterials[sq]?.first?.takeIf { discs[sq]?.enabled == true }
        return listOfNotNull(pads[sq], disc, tiles.getOrNull(sq))
    }

    /** Debug replay: a trough's holder, its body and its first standing disc. */
    fun replayTrough(color: Int): List<Entity> {
        val holder = troughs[color] ?: return emptyList()
        val body = holder.getChildren().firstOrNull()
        val slot = slots[color]?.firstOrNull { it.enabled }?.getChildren()?.firstOrNull()
        return listOfNotNull(holder, body, slot)
    }

    fun evidence(): String {
        val g = geo
        return "tilt=${g.tiltDeg} shown=$shownTilt pads=$padsBuilt discs=${discs.count { it?.enabled == true }} rings=${rings.count { it.enabled }} " +
            "snap=${snapRing?.enabled} hint=${hintMark?.enabled} frame=${frameMark?.enabled} flyer=${flyer?.enabled}@$flyerPos " +
            "tag=${g.tagCentre()} troughB=${troughs[Board.BLACK]?.transform()?.position} pad=${BoardGeometry.PAD} disc=${2 * BoardGeometry.DISC_R}x${BoardGeometry.DISC_H} " +
            "trough=${BoardGeometry.TROUGH_W}x${BoardGeometry.TROUGH_L} halfY=$regionHalfY scale=$volumeScale"
    }

    fun dispose() {
        res?.close()
        res = null
    }
}

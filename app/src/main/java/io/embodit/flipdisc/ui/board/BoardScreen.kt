package io.embodit.flipdisc.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.flipdisc.ui.board.input.BoardInputListener
import io.embodit.flipdisc.ui.board.input.FlipTarget
import io.embodit.flipdisc.ui.board.input.FlipTargets
import io.embodit.flipdisc.ui.board.input.InputSource
import io.embodit.flipdisc.ui.board.input.Offset3M
import io.embodit.flipdisc.ui.board.input.boardInput
import io.embodit.flipdisc.ui.control.hintSentence
import io.embodit.flipdisc.ui.control.passSentence
import io.embodit.flipdisc.ui.control.tagTurn
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.flipdisc.R
import io.embodit.flipdisc.domain.AlertKind
import io.embodit.flipdisc.domain.Board
import androidx.compose.foundation.layout.Column
import io.embodit.flipdisc.domain.BoardGeometry
import io.embodit.flipdisc.domain.Phase
import io.embodit.flipdisc.domain.PlayState
import io.embodit.flipdisc.domain.V3
import io.embodit.flipdisc.platform.Graph
import io.embodit.flipdisc.platform.TAG
import io.embodit.flipdisc.ui.Containers
import io.embodit.flipdisc.ui.theme.FlipColors
import io.embodit.flipdisc.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    /** The board's input listener, so debug `replay` feeds the very same object the gestures do. */
    @Volatile var listener: BoardInputListener<FlipTarget>? = null
}

private const val TAG_PANEL = "seatTag"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the board and
 * routes pinch / trigger taps (pads, tiles, discs, troughs) and drags out of a trough to [io.embodit.flipdisc.domain.GameFlow].
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene(graph.flow.drag) }
    val listener = remember(graph) { BoardListener(graph) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(
            open = { navigator.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
            close = { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
        )
        graph.window.closeBoard = { navigator.closeWindowContainer() }
        graph.window.request("start")
        onDispose {
            graph.window.detach()
            runCatching { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
        }
    }

    // Leaving for the background stops the engine, settles the move, puts a held disc back and saves (design container.lifecycle).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        SceneHolder.listener = listener
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // Factory template board-input (D50): system taps, short drags turned back into taps, real drags out of a
            // trough, all three inputs (controller ray, hand ray, eye-hand). Targets resolve up the parent chain.
            .boardInput(FlipTargets, listener),
        attachments = {
            // The attachment is its own composition: hand it the localised context explicitly.
            AttachmentPanel(TAG_PANEL) { SeatTagRoot(graph) }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            // S00: a plain felt slab and four discs first, then the real board.
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design container.lifecycle: "首帧兜底形状 绒面板 + 4 枚圆柱子")
                    val slab = MeshResource.createBox(Vector3(BoardGeometry.OUTER, BoardGeometry.THICK, BoardGeometry.OUTER), cornerRadius = 0.004f)
                    // gate:allow geometry S00 loading stand-in discs
                    val disc = MeshResource.createCylinder(radius = BoardGeometry.DISC_R, height = BoardGeometry.DISC_H)
                    fun mat(c: Color) = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(c.red, c.green, c.blue, 1f)); setRoughness(0.7f) }
                    Fallback(slab, disc, mat(FlipColors.Felt), mat(FlipColors.DiscBlack), mat(FlipColors.DiscWhite))
                }.getOrNull()
            }
            quick?.let { q -> scene.showFallback(q.slab, q.disc, q.felt, q.black, q.ivory) }
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            // QA only (debug broadcast `s00hold on`): keep the S00 stand-in for 10 s on the next launch so it can be captured.
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            scene.install(resources, attachments.entity(TAG_PANEL))
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
                graph.flow.volumeScale = scene.volumeScale
            }
            scene.apply(state, settings)
        },
    )
}

private class Fallback(val slab: MeshResource, val disc: MeshResource, val felt: PhysicallyBasedMaterial, val black: PhysicallyBasedMaterial, val ivory: PhysicallyBasedMaterial)

/** Board input -> game flow. Taps on a square / trough; a drag only from the side-to-move's own trough. */
private class BoardListener(private val graph: Graph) : BoardInputListener<FlipTarget> {
    override fun onTap(target: FlipTarget?, source: InputSource, position: Offset3D?) {
        Log.i(TAG, "PINCH src=${source.label} target=$target")
        when (target) {
            is FlipTarget.Square -> graph.flow.tapSquare(target.square)
            is FlipTarget.Trough -> graph.flow.tapTrough(target.color)
            null -> Unit
        }
    }

    override fun onDragStart(target: FlipTarget, source: InputSource): Boolean =
        target is FlipTarget.Trough && graph.flow.dragStart(target.color)

    override fun onDragMove(totalM: Offset3M) = graph.flow.dragMove(V3(totalM.x, totalM.y, totalM.z))

    override fun onDragEnd() = graph.flow.dragEnd()

    override fun onDragCancel() = graph.flow.dragCancel()

    /** Hover is a hint only (the system lights tiles / discs itself; Shared Space rarely sends it to the app). */
    override fun onHover(target: FlipTarget?, source: InputSource) = Unit
}

/** The seat tag is its own composition: it reads state + language itself so a language switch always reaches it. */
@Composable
private fun SeatTagRoot(graph: Graph) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    androidx.compose.runtime.key(s.language) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContext provides base.localized(s.language)) { SeatTag(st, graph) }
    }
}

/**
 * The seat tag above the far edge (design panels.seatTag; 280 x 60 dp drawn 1.65x, see BoardGeometry.TAG_SCALE; no buttons except the reopen chip when the window
 * was closed): line 1 the score and whose turn, line 2 what just happened on the board. Same sources as the window (D38).
 */
@Composable
private fun SeatTag(state: PlayState, graph: Graph) {
    val window = graph.window
    Box(
        modifier = Modifier
            .size(width = BoardGeometry.TAG_DP_W.dp, height = BoardGeometry.TAG_DP_H.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF2FBF6EC)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            window.levelHint -> TagLine(stringResource(R.string.level_hint), 15, FontWeight.Normal, "tag/level")
            window.panelState == "ON_DESTROY" -> Button(onClick = { window.request("chip") }) {
                // design-style: inherited-content-color Button
                Text(stringResource(R.string.open_panel), fontSize = 16.sp)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagDot(Board.BLACK); TagLine("${state.black}", 17, FontWeight.Bold, "tag/black")
                    TagLine(":", 17, FontWeight.Bold, "tag/colon")
                    TagDot(Board.WHITE); TagLine("${state.white}", 17, FontWeight.Bold, "tag/white")
                    TagLine("· " + tagTurn(state), 16, FontWeight.SemiBold, "tag/turn")
                }
                val second = tagEvent(state)
                if (second != null) TagLine(second.first, 14, FontWeight.SemiBold, "tag/event", second.second)
            }
        }
    }
}

@Composable
private fun TagDot(color: Int) {
    Box(Modifier.size(14.dp).clip(CircleShape).background(if (color == Board.BLACK) FlipColors.DiscBlack else FlipColors.DiscWhite).border(1.5.dp, FlipColors.Ink, CircleShape))
}

@Composable
private fun TagLine(text: String, sp: Int, weight: FontWeight, tag: String, color: Color = FlipColors.Ink) {
    Text(text, color = color, fontSize = sp.sp, fontWeight = weight, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") })
}

/** Line 2: refusal reason, pass, the hint sentence, or the first-move tip; null when nothing happened (design panels.seatTag). */
@Composable
private fun tagEvent(state: PlayState): Pair<String, Color>? {
    val al = state.alert
    return when {
        al != null && al.kind == AlertKind.NO_FLIP -> stringResource(R.string.tag_no_flip) to FlipColors.ErrorText
        al != null && al.kind == AlertKind.OCCUPIED -> stringResource(R.string.tag_occupied) to FlipColors.ErrorText
        al != null && al.kind == AlertKind.TIP -> stringResource(R.string.tag_drag_tip) to FlipColors.Ink
        state.phase == Phase.PASS -> passSentence(state) to FlipColors.Ink
        state.hint != null -> hintSentence(state, short = true) to FlipColors.Ink
        state.showTip -> stringResource(R.string.tag_drag_tip) to FlipColors.InkSecondary
        else -> null
    }
}

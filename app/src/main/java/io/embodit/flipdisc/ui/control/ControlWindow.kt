package io.embodit.flipdisc.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.flipdisc.BuildConfig
import io.embodit.flipdisc.R
import io.embodit.flipdisc.domain.Board
import io.embodit.flipdisc.domain.ConfirmKind
import io.embodit.flipdisc.domain.EndReason
import io.embodit.flipdisc.domain.GameSetup
import io.embodit.flipdisc.domain.HintKind
import io.embodit.flipdisc.domain.Language
import io.embodit.flipdisc.domain.Mode
import io.embodit.flipdisc.domain.Phase
import io.embodit.flipdisc.domain.PlayState
import io.embodit.flipdisc.domain.Settings
import io.embodit.flipdisc.domain.SidePick
import io.embodit.flipdisc.platform.Graph
import io.embodit.flipdisc.platform.TAG
import io.embodit.flipdisc.ui.Containers
import io.embodit.flipdisc.ui.Page
import io.embodit.flipdisc.ui.Ui
import io.embodit.flipdisc.ui.theme.FlipColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the board, 560 x 600 dp fixed): all 2D UI of the app, three pages
 * switched by the native SpatialUI TabBar (D8): Play / Rules / Settings. Setup, game and result are the three contents of
 * Play; More settings and the confirm cards are pushed pages. Dragging and closing are the system window controls (D21).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    DisposableEffect(navigator) {
        graph.window.exitAction = { graph.flow.confirm(); exitApp(context, navigator, graph) }
        onDispose { graph.window.exitAction = null }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // The game decides the page at its milestones: first run -> rules, setup or a game -> play.
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.WELCOME -> Ui.page = Page.RULES
            Phase.SETUP -> Ui.page = Page.PLAY
            else -> Unit
        }
    }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.moreOpen = false
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxSize().onSizeChanged { with(density) { Log.i(TAG, "WINDOW content ${it.width.toDp().value.toInt()}x${it.height.toDp().value.toInt()} dp") } }
        .padding(horizontal = 28.dp, vertical = 24.dp)) {
        val confirm = state.confirm
        when {
            confirm != null -> ConfirmCard(confirm, state, graph)
            Ui.page == Page.PLAY -> PlayPage(state, settings, graph)
            Ui.page == Page.RULES -> RulesPage(state, graph)
            Ui.moreOpen -> MorePage(settings, graph)
            else -> SettingsPage(settings, graph)
        }
    }

    // The TabBar is its own system window: its items do not see this window's localised context, so the labels are
    // resolved here and passed in as plain strings (five-stones QA q1-6).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    androidx.compose.runtime.key(settings.language) { ControlTabBar(tabs) }
}

// ------------------------------------------------------------------ shared words

@Composable
private fun levelName(level: Int): String = stringResource(
    when (level) { 1 -> R.string.level_1; 2 -> R.string.level_2; 3 -> R.string.level_3; 4 -> R.string.level_4; 5 -> R.string.level_5; else -> R.string.level_6 },
)

@Composable
private fun levelBlurb(level: Int): String = stringResource(
    when (level) { 1 -> R.string.blurb_1; 2 -> R.string.blurb_2; 3 -> R.string.blurb_3; 4 -> R.string.blurb_4; 5 -> R.string.blurb_5; else -> R.string.blurb_6 },
)

@Composable
internal fun sideName(color: Int): String = stringResource(if (color == Board.BLACK) R.string.side_black else R.string.side_white)

/** Who [color] is: "You" / "Computer · Club night" against the AI, "Black" / "White" for two players (design twoPlayerCopy). */
@Composable
private fun whoName(state: PlayState, color: Int): String {
    val cfg = state.config ?: return sideName(color)
    return when {
        cfg.mode == Mode.TWO_PLAYER -> sideName(color)
        color == cfg.humanColor -> stringResource(R.string.play_you)
        else -> stringResource(R.string.play_computer, levelName(cfg.level))
    }
}

/** Seat tag line 1 tail and the status card's turn words. */
@Composable
internal fun tagTurn(state: PlayState): String {
    val cfg = state.config
    return when {
        cfg == null || state.phase in setOf(Phase.LOADING, Phase.WELCOME, Phase.SETUP) -> stringResource(R.string.tag_ready)
        state.result != null -> resultHeadline(state)
        state.phase == Phase.THINKING -> stringResource(R.string.play_thinking)
        cfg.mode == Mode.TWO_PLAYER -> stringResource(if (state.toMove == Board.BLACK) R.string.turn_black else R.string.turn_white)
        state.humansTurn -> stringResource(R.string.play_your_move)
        else -> stringResource(R.string.play_their_move)
    }
}

@Composable
internal fun passSentence(state: PlayState): String {
    val cfg = state.config
    val who = state.passColor
    return when {
        cfg == null -> ""
        cfg.mode == Mode.TWO_PLAYER -> stringResource(R.string.pass_2p, sideName(who), sideName(Board.other(who)))
        who == cfg.humanColor -> stringResource(R.string.pass_you)
        else -> stringResource(R.string.pass_them)
    }
}

/** The hint in words (design gameSpec.hint.states); [short] = the seat-tag version that fits 280 dp. */
@Composable
internal fun hintSentence(state: PlayState, short: Boolean): String {
    val h = state.hint ?: return ""
    val sq = Board.name(h.square)
    val twoP = state.config?.mode == Mode.TWO_PLAYER
    return when (h.kind) {
        HintKind.SURE -> when {
            h.diff == 0 && short -> stringResource(R.string.hint_tag_draw, sq)
            h.diff == 0 -> stringResource(R.string.hint_sure_draw, sq)
            short -> stringResource(R.string.hint_tag_sure, sq, h.diff)
            twoP -> stringResource(R.string.hint_sure_2p, sq, h.diff, sideName(state.toMove))
            else -> stringResource(R.string.hint_sure, sq, h.diff)
        }
        HintKind.UNSURE -> stringResource(if (short) R.string.hint_tag_unsure else R.string.hint_unsure, sq)
        HintKind.LOST -> stringResource(if (short) R.string.hint_tag_lost else R.string.hint_lost, sq, -h.diff)
    }
}

@Composable
internal fun resultHeadline(state: PlayState): String {
    val r = state.result ?: return ""
    val cfg = state.config
    return when {
        r.winner == 0 -> stringResource(R.string.result_draw)
        cfg?.mode == Mode.TWO_PLAYER -> stringResource(if (r.winner == Board.BLACK) R.string.result_black_wins else R.string.result_white_wins)
        r.winner == cfg?.humanColor -> stringResource(R.string.result_win)
        else -> stringResource(R.string.result_lose)
    }
}

// ------------------------------------------------------------------ Play

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    when {
        state.phase == Phase.LOADING -> LoadingPage()
        state.config == null || state.phase in setOf(Phase.SETUP, Phase.WELCOME) -> SetupPage(state, settings, graph)
        state.result != null -> ResultPage(state, graph)
        else -> GamePage(state, settings, graph)
    }
}

/** S00: the app picture, the name, one line and a spinner, centred (design panels.loading). */
@Composable
private fun LoadingPage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        AppPicture(Modifier.size(240.dp))
        Spacer(Modifier.height(20.dp))
        Txt(stringResource(R.string.app_name), "loading/name", style = PicoTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Txt(stringResource(R.string.loading_line), "loading/line", style = PicoTheme.typography.bodyLarge, color = FlipColors.InkSecondary)
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
    }
}

/** The icon motif: felt square with a disc mid-flip (black left, ivory right). */
@Composable
private fun AppPicture(modifier: Modifier) {
    Canvas(modifier) {
        drawRoundRect(Color(0xFF2F7850), cornerRadius = CornerRadius(size.minDimension * 0.18f))
        val r = size.minDimension * 0.30f
        val c = Offset(size.width / 2, size.height / 2)
        drawArc(FlipColors.DiscBlack, 90f, 180f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
        drawArc(FlipColors.DiscWhite, -90f, 180f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
        drawLine(Color(0xFF55585C), Offset(c.x - r * 0.22f, c.y + r * 0.96f), Offset(c.x + r * 0.22f, c.y - r * 0.96f), strokeWidth = r * 0.10f)
    }
}

/** S02: opponent, level card (AI) or the two-player card, colour, Start (design panels.setupAi / setup2p). */
@Composable
private fun SetupPage(state: PlayState, settings: Settings, graph: Graph) {
    val setup = settings.lastSetup
    fun set(change: (GameSetup) -> GameSetup) = graph.settings.update { it.copy(lastSetup = change(it.lastSetup)) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(stringResource(R.string.setup_opponent), "setup/opponent", style = PicoTheme.typography.labelLarge, color = FlipColors.InkSecondary)
            Segments(listOf(Mode.AI to stringResource(R.string.setup_ai), Mode.TWO_PLAYER to stringResource(R.string.setup_2p)), setup.mode, "setup/opponent", { m -> set { it.copy(mode = m) } }, Modifier.fillMaxWidth())
        }
        if (setup.mode == Mode.AI) {
            Card(Modifier.fillMaxWidth().height(148.dp)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(stringResource(R.string.setup_level) + " " + setup.level, "setup/level-n", style = PicoTheme.typography.labelLarge, color = FlipColors.InkSecondary)
                        Spacer(Modifier.width(12.dp))
                        Txt(levelName(setup.level), "setup/level", style = PicoTheme.typography.titleLarge)
                    }
                    Txt(levelBlurb(setup.level), "setup/blurb", style = PicoTheme.typography.bodyMedium, color = FlipColors.InkSecondary, maxLines = 2)
                    StopSlider(setup.level - 1, (1..6).map { "$it" }, "setup/level-slider", { k -> set { it.copy(level = k + 1) } }, Modifier.fillMaxWidth())
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(R.string.setup_color), "setup/color", style = PicoTheme.typography.labelLarge, color = FlipColors.InkSecondary)
                Segments(
                    listOf(SidePick.BLACK to stringResource(R.string.setup_black), SidePick.WHITE to stringResource(R.string.setup_white), SidePick.RANDOM to stringResource(R.string.setup_random)),
                    setup.side, "setup/color", { s -> set { it.copy(side = s) } }, Modifier.fillMaxWidth(),
                )
            }
        } else {
            Card(Modifier.fillMaxWidth().height(248.dp)) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(R.drawable.ic_group), contentDescription = null, modifier = Modifier.size(32.dp), tint = FlipColors.Primary)
                        Txt(stringResource(R.string.setup_2p_title), "setup/2p-title", style = PicoTheme.typography.titleLarge, maxLines = 2)
                    }
                    Txt(stringResource(R.string.setup_2p_note), "setup/2p-note", style = PicoTheme.typography.bodyLarge, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DiscDot(Board.BLACK, 22.dp); DiscDot(Board.WHITE, 22.dp)
                        Txt(stringResource(R.string.setup_2p_rule), "setup/2p-rule", style = PicoTheme.typography.bodyMedium, color = FlipColors.InkSecondary, maxLines = 2)
                    }
                }
            }
        }
        PrimaryButton(stringResource(R.string.setup_start), R.drawable.ic_tab_play, "setup/start", onClick = {
            if (state.phase == Phase.WELCOME) graph.flow.welcomeDone()
            graph.flow.newGame(setup)
            Ui.page = Page.PLAY
        }, modifier = Modifier.fillMaxWidth())
    }
}

/** S03–S08: score block, status card, [Hint], [Undo] [Mute], [New game] (design panels.play, 7 clickables with the tabs). */
@Composable
private fun GamePage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScoreBlock(state)
        StatusCard(state)
        val hintLabel = if (state.hintBusy) stringResource(R.string.play_hint_working) else stringResource(R.string.play_hint)
        PrimaryButton(hintLabel, R.drawable.ic_lightbulb, "play/hint", onClick = { flow.toggleHint() }, enabled = state.canHint || state.hint != null, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.play_undo), R.drawable.ic_undo, "play/undo", onClick = { flow.requestUndo() }, enabled = state.canUndo, modifier = Modifier.weight(1f))
            val muted = settings.muted
            SecondaryButton(
                stringResource(if (muted) R.string.play_unmute else R.string.play_mute), if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, "play/mute",
                onClick = { graph.settings.update { s -> if (s.muted) s.copy(volume = s.lastVolume) else s.copy(lastVolume = s.volume, volume = 0) } },
                modifier = Modifier.weight(1f),
            )
        }
        SecondaryButton(stringResource(R.string.play_new), R.drawable.ic_add, "play/new", onClick = { flow.requestNew() }, modifier = Modifier.fillMaxWidth())
    }
}

/** Two big counts with whose turn marked, and a two-tone bar of the board's share (design panels.play hero). */
@Composable
private fun ScoreBlock(state: PlayState) {
    Card(Modifier.fillMaxWidth().height(164.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ScoreSide(state, Board.BLACK, Modifier.weight(1f))
                ScoreSide(state, Board.WHITE, Modifier.weight(1f))
            }
            val total = (state.black + state.white).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                Box(Modifier.weight(state.black.coerceAtLeast(1).toFloat() / total).height(12.dp).background(FlipColors.DiscBlack))
                Box(Modifier.weight(state.white.coerceAtLeast(1).toFloat() / total).height(12.dp).background(FlipColors.DiscWhite))
            }
        }
    }
}

@Composable
private fun ScoreSide(state: PlayState, color: Int, modifier: Modifier) {
    val turn = state.result == null && state.toMove == color && state.phase in setOf(Phase.IDLE, Phase.DRAGGING, Phase.THINKING)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (turn) FlipColors.Primary else Color.Transparent), contentAlignment = Alignment.Center) {
            DiscDot(color, 36.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Txt("${if (color == Board.BLACK) state.black else state.white}", "play/count", style = PicoTheme.typography.displaySmall.copy(fontSize = 56.sp, fontWeight = FontWeight.Bold))
            Txt(whoName(state, color), "play/who", style = PicoTheme.typography.labelLarge, color = FlipColors.InkSecondary)
        }
    }
}

/** One sentence about now: whose turn, thinking, pass, the refusal reason, or the hint in its three looks. */
@Composable
private fun StatusCard(state: PlayState) {
    val h = state.hint
    val al = state.alert
    val (icon, text) = when {
        al != null && al.kind == io.embodit.flipdisc.domain.AlertKind.NO_FLIP -> null to stringResource(R.string.tag_no_flip)
        al != null && al.kind == io.embodit.flipdisc.domain.AlertKind.OCCUPIED -> null to stringResource(R.string.tag_occupied)
        state.phase == Phase.PASS -> null to passSentence(state)
        h != null -> when (h.kind) { HintKind.SURE -> R.drawable.ic_check_circle; HintKind.UNSURE -> R.drawable.ic_help; HintKind.LOST -> R.drawable.ic_trending_down } to hintSentence(state, short = false)
        state.phase == Phase.MOVING -> null to stringResource(R.string.play_flipping)
        state.showTip || (al != null && al.kind == io.embodit.flipdisc.domain.AlertKind.TIP) -> null to stringResource(R.string.status_turn_tip, tagTurn(state), stringResource(R.string.tag_drag_tip))
        else -> null to tagTurn(state)
    }
    val bg = when {
        h?.kind == HintKind.LOST -> FlipColors.LostContainer
        h != null -> FlipColors.SecondaryContainer
        al != null && al.kind != io.embodit.flipdisc.domain.AlertKind.TIP -> Color(0xFFFFE4E0)
        else -> PicoTheme.colorScheme.fillSecondary
    }
    val fg = when {
        h != null && h.kind != HintKind.LOST -> FlipColors.OnSecondaryContainer
        al != null && al.kind != io.embodit.flipdisc.domain.AlertKind.TIP -> FlipColors.ErrorText
        else -> FlipColors.Ink
    }
    Card(Modifier.fillMaxWidth().height(64.dp), color = bg) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.phase == Phase.THINKING) { CircularProgressIndicator(modifier = Modifier.size(26.dp)); Spacer(Modifier.width(12.dp)) }
            if (icon != null) { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp), tint = fg); Spacer(Modifier.width(12.dp)) }
            Txt(text, "play/status", style = PicoTheme.typography.titleMedium, color = fg, maxLines = 2)
        }
    }
}

/** S09: winner, score and why, then [Again] / [Change opponent] (design panels.result). */
@Composable
private fun ResultPage(state: PlayState, graph: Graph) {
    val r = state.result ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Card(Modifier.fillMaxWidth().height(256.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(resultHeadline(state), "result/title", style = PicoTheme.typography.headlineSmall.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DiscDot(Board.BLACK, 34.dp)
                    Txt("${r.black} : ${r.white}", "result/score", style = PicoTheme.typography.displaySmall.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold))
                    DiscDot(Board.WHITE, 34.dp)
                }
                Txt(stringResource(if (r.reason == EndReason.FULL) R.string.result_full else R.string.result_no_moves), "result/reason", style = PicoTheme.typography.titleMedium)
                Txt(stringResource(R.string.result_moves, state.moves.size), "result/moves", style = PicoTheme.typography.bodyMedium, color = FlipColors.InkSecondary)
            }
        }
        PrimaryButton(stringResource(R.string.result_again), R.drawable.ic_replay, "result/again", onClick = { graph.flow.again() }, modifier = Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.result_change), R.drawable.ic_swap_horiz, "result/change", onClick = { graph.flow.toSetup() }, modifier = Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------ Confirm (S10)

@Composable
private fun ConfirmCard(kind: ConfirmKind, state: PlayState, graph: Graph) {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    // the side who just moved asks; the side to move now agrees
    val asker = sideName(Board.other(state.toMove))
    val (title, body, labels) = when (kind) {
        ConfirmKind.EXIT -> Triple(stringResource(R.string.confirm_exit_t), stringResource(R.string.confirm_exit_b), stringResource(R.string.confirm_exit_y) to stringResource(R.string.confirm_exit_n))
        ConfirmKind.NEW -> Triple(stringResource(R.string.confirm_new_t), stringResource(R.string.confirm_new_b), stringResource(R.string.confirm_new_y) to stringResource(R.string.confirm_new_n))
        ConfirmKind.RESET -> Triple(stringResource(R.string.confirm_reset_t), stringResource(R.string.confirm_reset_b), stringResource(R.string.confirm_reset_y) to stringResource(R.string.confirm_reset_n))
        ConfirmKind.UNDO_2P -> Triple(stringResource(R.string.confirm_undo_t, asker), stringResource(R.string.confirm_undo_b, sideName(state.toMove)), stringResource(R.string.confirm_undo_y) to stringResource(R.string.confirm_undo_n))
    }
    val icon = when (kind) { ConfirmKind.EXIT -> R.drawable.ic_logout; ConfirmKind.NEW -> R.drawable.ic_add; ConfirmKind.RESET -> R.drawable.ic_restart_alt; ConfirmKind.UNDO_2P -> R.drawable.ic_undo }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(140.dp).clip(CircleShape).background(FlipColors.PrimaryContainer), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(72.dp), tint = FlipColors.OnPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Txt(title, "confirm/${kind.name}/title", style = PicoTheme.typography.titleLarge, align = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        Txt(body, "confirm/${kind.name}/body", style = PicoTheme.typography.bodyLarge, color = FlipColors.InkSecondary, align = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(labels.first, icon, "confirm/yes", onClick = {
            if (kind == ConfirmKind.EXIT) graph.window.exitAction?.invoke() ?: run { graph.flow.confirm(); exitApp(context, navigator, graph) } else graph.flow.confirm()
        }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        SecondaryButton(labels.second, null, "confirm/no", onClick = { graph.flow.cancelConfirm() }, modifier = Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------ Rules (S01 / S13)

@Composable
private fun RulesPage(state: PlayState, graph: Graph) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        // same-page option (rules / how to play), not page navigation
        Segments(listOf(0 to stringResource(R.string.rules_seg_rules), 1 to stringResource(R.string.rules_seg_how)), Ui.rulesView, "rules/view", { Ui.rulesView = it }, Modifier.fillMaxWidth())
        if (Ui.rulesView == 0) {
            RulesPanels()
            Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleMedium)
            val m = listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).map { stringResource(it) }
            for (row in 0..1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (col in 0..1) {
                        Card(Modifier.weight(1f).height(80.dp)) {
                            Txt(m[row * 2 + col], "rules/mistake", style = PicoTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        } else HowTo()
        if (state.phase == Phase.WELCOME) {
            PrimaryButton(stringResource(R.string.rules_ok), R.drawable.ic_tab_play, "rules/ok", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth())
        } else {
            SecondaryButton(stringResource(R.string.rules_back), R.drawable.ic_back, "rules/back", onClick = { Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Three pictures: trap it, flip it · only lit dots · most discs wins (design content.rulesPage.panels). */
@Composable
private fun RulesPanels() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(R.string.rules_panel_1, R.string.rules_panel_2, R.string.rules_panel_3).forEachIndexed { i, caption ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.fillMaxWidth().height(96.dp)) { drawRulePanel(i) }
                Txt(stringResource(caption), "rules/panel", style = PicoTheme.typography.labelLarge, align = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

private fun DrawScope.drawRulePanel(i: Int) {
    drawRoundRect(FlipColors.Felt, cornerRadius = CornerRadius(16f))
    fun disc(cx: Float, cy: Float, r: Float, black: Boolean) {
        drawCircle(if (black) FlipColors.DiscBlack else FlipColors.DiscWhite, r, Offset(cx, cy))
        drawCircle(Color(0xFF1B1B1B), r, Offset(cx, cy), style = Stroke(1.5f))
    }
    val h = size.height; val w = size.width
    when (i) {
        0 -> {
            // ○ ● ● ○ above, ○ ○ ○ ○ below
            val r = w / 11f
            for (k in 0..3) {
                val x = w * (0.2f + 0.2f * k)
                disc(x, h * 0.32f, r, k == 1 || k == 2)
                disc(x, h * 0.72f, r, false)
            }
            drawLine(FlipColors.HintRing, Offset(w * 0.5f, h * 0.45f), Offset(w * 0.5f, h * 0.58f), strokeWidth = 4f)
        }
        1 -> {
            val r = w / 14f
            disc(w * 0.42f, h * 0.38f, r, false); disc(w * 0.58f, h * 0.38f, r, true)
            disc(w * 0.42f, h * 0.62f, r, true); disc(w * 0.58f, h * 0.62f, r, false)
            for ((x, y) in listOf(0.42f to 0.14f, 0.82f to 0.38f, 0.18f to 0.62f, 0.58f to 0.86f)) {
                drawCircle(FlipColors.LegalRing, r * 0.8f, Offset(w * x, h * y), style = Stroke(4f))
            }
        }
        else -> {
            val r = w / 9f
            for (k in 0..4) drawOval(FlipColors.DiscBlack, Offset(w * 0.32f - r, h * (0.82f - k * 0.13f) - r * 0.3f), Size(2 * r, r * 0.6f))
            for (k in 0..1) drawOval(FlipColors.DiscWhite, Offset(w * 0.68f - r, h * (0.82f - k * 0.13f) - r * 0.3f), Size(2 * r, r * 0.6f))
        }
    }
}

/** How to play (B19): each main action with the hand way and the controller way. */
@Composable
private fun HowTo() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(stringResource(R.string.how_to_title), "how/title", style = PicoTheme.typography.titleMedium)
        HowRow(R.drawable.ic_check_circle, stringResource(R.string.how_to_tap_label), stringResource(R.string.how_to_tap_hand), stringResource(R.string.how_to_tap_ctrl))
        HowRow(R.drawable.ic_tab_play, stringResource(R.string.how_to_drag_label), stringResource(R.string.how_to_drag_hand), stringResource(R.string.how_to_drag_ctrl))
        HowRow(R.drawable.ic_open_in_new, stringResource(R.string.how_to_panel_label), stringResource(R.string.how_to_panel_hand), null)
        HowRow(R.drawable.ic_tune, stringResource(R.string.how_to_angle_label), stringResource(R.string.how_to_angle_hand), null)
    }
}

@Composable
private fun HowRow(icon: Int, label: String, hand: String, ctrl: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = FlipColors.Primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Txt(label, "how/label", style = PicoTheme.typography.labelLarge)
            Txt(hand, "how/hand", style = PicoTheme.typography.bodyMedium, color = FlipColors.InkSecondary, maxLines = 2)
            if (ctrl != null) Txt(ctrl, "how/ctrl", style = PicoTheme.typography.bodyMedium, color = FlipColors.InkSecondary, maxLines = 2)
        }
    }
}

// ------------------------------------------------------------------ Settings (S11) / More (S12)

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    fun upd(change: (Settings) -> Settings) = graph.settings.update(change)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().height(100.dp)) {
            Txt(stringResource(R.string.set_volume), "settings/volume", style = PicoTheme.typography.titleMedium)
            StopSlider(
                settings.volume, listOf(R.string.vol_off, R.string.vol_low, R.string.vol_mid, R.string.vol_high).map { stringResource(it) }, "settings/volume",
                { v -> upd { s -> if (v == 0) s.copy(volume = 0, lastVolume = if (s.volume > 0) s.volume else s.lastVolume) else s.copy(volume = v, lastVolume = v) } },
                Modifier.fillMaxWidth(),
            )
        }
        Column(Modifier.fillMaxWidth().height(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(stringResource(R.string.set_tilt), "settings/tilt", style = PicoTheme.typography.titleMedium)
            TiltPicture(settings.tilt, Modifier.fillMaxWidth().height(96.dp))
            StopSlider(
                Settings.TILTS.indexOf(settings.tilt).coerceAtLeast(0), Settings.TILTS.map { "$it°" }, "settings/tilt",
                { k -> upd { it.copy(tilt = Settings.TILTS[k]) } }, Modifier.fillMaxWidth(),
            )
        }
        SecondaryButton(stringResource(R.string.set_more), R.drawable.ic_tune, "settings/more", onClick = { Ui.moreOpen = true }, modifier = Modifier.fillMaxWidth())
        // D26: quit is the last row on the first screen; the confirm card opens in this window.
        SecondaryButton(stringResource(R.string.set_exit), R.drawable.ic_logout, "settings/exit", onClick = { graph.flow.requestConfirm(ConfirmKind.EXIT) }, modifier = Modifier.fillMaxWidth(), danger = true)
    }
}

/** Side view of the board at [tilt] next to its trough (design panels.settings hero). */
@Composable
private fun TiltPicture(tilt: Int, modifier: Modifier) {
    Canvas(modifier) {
        val base = size.height * 0.9f
        val x0 = size.width * 0.62f
        val len = size.height * 0.95f
        val a = Math.toRadians(tilt.toDouble())
        drawLine(FlipColors.InkSecondary, Offset(size.width * 0.15f, base), Offset(size.width * 0.85f, base), strokeWidth = 2f)
        val end = Offset(x0 - (len * kotlin.math.cos(a)).toFloat(), base - (len * kotlin.math.sin(a)).toFloat())
        drawLine(FlipColors.Frame, Offset(x0, base), end, strokeWidth = 12f)
        drawLine(FlipColors.Felt, Offset(x0, base - 6f), Offset(end.x, end.y - 6f), strokeWidth = 5f)
        drawRect(FlipColors.Trough, Offset(x0 + 12f, base - 18f), Size(size.width * 0.16f, 18f))
    }
}

@Composable
private fun MorePage(settings: Settings, graph: Graph) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton("‹ " + stringResource(R.string.more_back), R.drawable.ic_back, "more/back", onClick = { Ui.moreOpen = false })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(stringResource(R.string.more_lang), "more/lang", style = PicoTheme.typography.titleMedium)
            Segments(
                listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)),
                settings.language, "more/lang", { l -> graph.settings.update { it.copy(language = l) } }, Modifier.fillMaxWidth(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SecondaryButton(stringResource(R.string.more_reopen), R.drawable.ic_open_in_new, "more/reopen", onClick = { graph.window.recall() }, modifier = Modifier.fillMaxWidth())
            Txt(stringResource(R.string.more_reopen_note), "more/reopen-note", style = PicoTheme.typography.bodySmall, color = FlipColors.InkSecondary, maxLines = 2)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SecondaryButton(stringResource(R.string.more_reset), R.drawable.ic_restart_alt, "more/reset", onClick = { graph.flow.requestConfirm(ConfirmKind.RESET) }, modifier = Modifier.fillMaxWidth())
            Txt(stringResource(R.string.more_reset_note), "more/reset-note", style = PicoTheme.typography.bodySmall, color = FlipColors.InkSecondary, maxLines = 2)
        }
        Txt(stringResource(R.string.more_about, BuildConfig.VERSION_NAME), "more/about", style = PicoTheme.typography.bodySmall, color = FlipColors.InkSecondary, maxLines = 2)
    }
}

/** D26 exit: release, close both windows, remove every task, then end the process. */
internal fun exitApp(context: Context, navigator: SpatialNavigator, graph: Graph) {
    Log.i(TAG, "EXIT release + close + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground(); graph.sounds.release(); graph.window.detach() }
    // D44: close BOTH window containers (board volume and this control window) before ending the task, otherwise the
    // task stays in recents. Give them 400 ms to report ON_DESTROY, then remove the task and end.
    val closeBoard = graph.window.closeBoard
    runCatching { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
    runCatching { closeBoard?.invoke() }.onFailure { Log.w(TAG, "EXIT close board: $it") }
    Log.i(TAG, "EXIT closed control + board (board closer=${closeBoard != null})")
    val main = Handler(Looper.getMainLooper())
    main.postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        Log.i(TAG, "EXIT finishAndRemoveTask")
        main.postDelayed({ exitProcess(0) }, 300)
    }, 400)
}

/** The native TabBar with labels already resolved in the app language (kept last in the file: no string lookups below). */
@Composable
private fun ControlTabBar(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

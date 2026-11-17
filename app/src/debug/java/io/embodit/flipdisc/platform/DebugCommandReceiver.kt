package io.embodit.flipdisc.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.flipdisc.domain.Board
import io.embodit.flipdisc.domain.BoardGeometry
import io.embodit.flipdisc.domain.ConfirmKind
import io.embodit.flipdisc.domain.FlipTiming
import io.embodit.flipdisc.domain.GameConfig
import io.embodit.flipdisc.domain.GameFlow
import io.embodit.flipdisc.domain.GameSetup
import io.embodit.flipdisc.domain.HintKind
import io.embodit.flipdisc.domain.Language
import io.embodit.flipdisc.domain.Mode
import io.embodit.flipdisc.domain.Phase
import io.embodit.flipdisc.domain.Position
import io.embodit.flipdisc.domain.Rules
import io.embodit.flipdisc.domain.Settings
import io.embodit.flipdisc.domain.SidePick
import io.embodit.flipdisc.domain.V3
import io.embodit.flipdisc.ui.Page
import io.embodit.flipdisc.ui.Ui
import io.embodit.flipdisc.ui.board.SceneHolder
import io.embodit.flipdisc.ui.board.input.FlipTarget
import io.embodit.flipdisc.ui.board.input.FlipTargets
import io.embodit.flipdisc.ui.board.input.InputSource
import io.embodit.flipdisc.ui.board.input.resolveUp
import kotlin.random.Random

/**
 * Debug builds only. Every command goes through the same GameFlow calls the board and the window use; `drag` feeds the
 * same dragStart / dragMove / dragEnd the board-input listener calls, `replay` resolves real scene entities and hands
 * them to the real listener.
 *
 *   adb shell am broadcast -a io.embodit.flipdisc.DEBUG -p io.embodit.flipdisc --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S13 | S03x | S04a | S04b | S08a|S08b|S08c | "S09 win|lose|draw" | "S10 new|undo|reset|exit"
 *   place f5 · drag "f5 [hold]" · drag "free hold" · drag "a1" (released off a legal square)
 *   hold flip|illegal|pass|think|off · hint (toggle, real) · hint "sure|unsure|lost" (look only)
 *   new "black ai 3" (side black|white|random, mode ai|2p, level 1-6) · load "f5 d6 c3" · undo · confirm · cancel
 *   tilt 20|30|45|60|90 · lang zh|en|system · sound 0-3 · reopen · seed N · page play|rules|settings · more on|off
 *   panelat left|bottom|right (reopen the window there, QA framing only)
 *   rulesview 0|1 · welcome · s00hold on|off · replay "f5|trough:black [hand-ray|eye-hand|controller]" · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim()
        val args = a.split(" ").filter { it.isNotBlank() }
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun sq(s: String) = Board.parse(s)
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun ensureGame() {
            val st = flow.state.value
            if (st.config == null || st.phase in setOf(Phase.SETUP, Phase.WELCOME, Phase.RESULT, Phase.LOADING)) flow.newGame(GameConfig(Mode.AI, Board.BLACK, 3))
            Ui.page = Page.PLAY
        }
        fun firstLegal(): Int? = flow.state.value.legal.minOrNull()
        when (cmd) {
            "goto" -> when (args.getOrNull(0)?.uppercase()) {
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: force-stop and start, then capture at once (or `s00hold on` first)")
                "S01" -> { settings { it.copy(firstRunDone = false) }; flow.debugState { it.copy(phase = Phase.WELCOME, config = null) }; Ui.page = Page.RULES; Ui.rulesView = 0 }
                "S02" -> { flow.toSetup(); Ui.page = Page.PLAY }
                "S03" -> ensureGame()
                "S03X" -> { ensureGame(); flow.holdAlert = true; SceneHolder.scene?.holdFrame = true; flow.tapSquare(Board.parse("a1")!!) }
                "S04A" -> { ensureGame(); firstLegal()?.let { dragTo(flow, it, hold = true) } }
                "S04B" -> { ensureGame(); dragFree(flow, hold = true) }
                "S05" -> { ensureGame(); holdFlip(flow); firstLegal()?.let { flow.tapSquare(it) } }
                "S06" -> { flow.holdThinking = true; flow.newGame(GameConfig(Mode.AI, Board.WHITE, 3)); Ui.page = Page.PLAY }
                "S07" -> passPosition(flow)
                "S08A" -> { ensureGame(); flow.debugHint(HintKind.SURE, 4) }
                "S08B" -> { ensureGame(); flow.debugHint(HintKind.UNSURE, 0) }
                "S08C" -> { ensureGame(); flow.debugHint(HintKind.LOST, -6) }
                "S09" -> { finished(flow, args.getOrNull(1) ?: "win"); Ui.page = Page.PLAY }
                "S10" -> {
                    Ui.page = Page.PLAY
                    when (args.getOrNull(1)) {
                        "undo" -> { flow.newGame(GameConfig(Mode.TWO_PLAYER, Board.BLACK, 3)); firstLegal()?.let { flow.load(listOf(it), GameConfig(Mode.TWO_PLAYER, Board.BLACK, 3)) }; flow.requestUndo() }
                        "reset" -> { Ui.page = Page.SETTINGS; Ui.moreOpen = true; flow.requestConfirm(ConfirmKind.RESET) }
                        "exit" -> { Ui.page = Page.SETTINGS; flow.requestConfirm(ConfirmKind.EXIT) }
                        else -> { ensureGame(); if (flow.state.value.moves.isEmpty()) firstLegal()?.let { flow.load(listOf(it)) }; flow.requestNew() }
                    }
                }
                "S11" -> { Ui.page = Page.SETTINGS; Ui.moreOpen = false }
                "S12" -> { Ui.page = Page.SETTINGS; Ui.moreOpen = true }
                "S13" -> { Ui.page = Page.RULES; Ui.rulesView = 0 }
                else -> Log.w(TAG, "unknown state $a")
            }
            "place" -> sq(a)?.let { flow.tapSquare(it) }
            "drag" -> when (args.getOrNull(0)) {
                "free" -> dragFree(flow, hold = args.getOrNull(1) == "hold")
                else -> args.getOrNull(0)?.let(::sq)?.let { dragTo(flow, it, hold = args.getOrNull(1) == "hold") }
            }
            "hold" -> when (a) {
                "flip" -> holdFlip(flow)
                "illegal" -> { flow.holdAlert = true; SceneHolder.scene?.holdFrame = true }
                "pass" -> flow.holdPass = true
                "think" -> flow.holdThinking = true
                "off" -> {
                    flow.freeze(null); flow.holdAlert = false; flow.holdPass = false; flow.holdThinking = false
                    SceneHolder.scene?.holdFrame = false
                    if (flow.drag.active) flow.dragEnd()
                }
            }
            "hint" -> when (a) {
                "sure" -> flow.debugHint(HintKind.SURE, 4)
                "unsure" -> flow.debugHint(HintKind.UNSURE, 0)
                "lost" -> flow.debugHint(HintKind.LOST, -6)
                else -> flow.toggleHint()
            }
            "new" -> {
                val side = when (args.getOrNull(0)) { "white" -> SidePick.WHITE; "random" -> SidePick.RANDOM; else -> SidePick.BLACK }
                val mode = if (args.getOrNull(1) == "2p") Mode.TWO_PLAYER else Mode.AI
                flow.newGame(GameSetup(mode, side, args.getOrNull(2)?.toIntOrNull() ?: 1)); Ui.page = Page.PLAY
            }
            "load" -> flow.load(args.mapNotNull(::sq))
            "setupmode" -> settings { it.copy(lastSetup = it.lastSetup.copy(mode = if (a == "2p") Mode.TWO_PLAYER else Mode.AI)) }
            "undo" -> flow.requestUndo()
            "confirm" -> if (flow.state.value.confirm == ConfirmKind.EXIT) graph.window.exitAction?.invoke() else flow.confirm()
            "cancel" -> flow.cancelConfirm()
            "level" -> a.toIntOrNull()?.let { l -> flow.state.value.config?.let { flow.load(flow.state.value.moves, it.copy(level = l.coerceIn(1, 6))) } }
            "tilt" -> a.toIntOrNull()?.takeIf { it in Settings.TILTS }?.let { t -> settings { it.copy(tilt = t) } }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound", "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 3), lastVolume = if (v > 0) v.coerceIn(1, 3) else it.lastVolume) } }
            "reopen", "recall" -> graph.window.recall()
            "panelat" -> { WindowCtl.debugPlacement = a.takeIf { it == "left" || it == "bottom" }; graph.window.recall() }
            "seed" -> a.toIntOrNull()?.let { flow.aiSeed = it }
            "page" -> { Ui.page = when (a) { "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }; Ui.moreOpen = false }
            "more" -> { Ui.page = Page.SETTINGS; Ui.moreOpen = on }
            "rulesview" -> { Ui.page = Page.RULES; Ui.rulesView = a.toIntOrNull()?.coerceIn(0, 1) ?: 0 }
            "welcome" -> flow.welcomeDone()
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "replay" -> replay(args)
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} more=${Ui.moreOpen} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }

    /** Freeze the next move half way through the second ring of the domino (S05 "hold flip"). */
    private fun holdFlip(flow: GameFlow) =
        flow.freeze(GameFlow.FLIGHT_MS + GameFlow.LAND_MS + FlipTiming.startOf(1) + FlipTiming.FLIP_MS / 2)

    /** The drag path through the listener's own entry points: out of the trough, over [sq] 3 cm up, released unless [hold]. */
    private fun dragTo(flow: GameFlow, sq: Int, hold: Boolean) {
        val st = flow.state.value
        if (!flow.dragStart(st.toMove)) return
        val geo = BoardGeometry(SceneHolder.graph?.settings?.value?.tilt ?: Settings.DEFAULT_TILT)
        val start = flow.drag.held
        val target = geo.squareScene(sq, BoardGeometry.DISC_Y + 0.03f)
        val k = flow.volumeScale
        for (i in 1..12) flow.dragMove((target - start) * (i / 12f * k))
        Log.i(TAG, "DEBUG drag to ${Board.name(sq)} snapped=${flow.state.value.snapped?.let(Board::name)} hold=$hold")
        if (!hold) flow.dragEnd()
    }

    /** Out of the trough and 25 cm up: held flat, nothing snapped (S04b). */
    private fun dragFree(flow: GameFlow, hold: Boolean) {
        if (!flow.dragStart(flow.state.value.toMove)) return
        val k = flow.volumeScale
        for (i in 1..10) flow.dragMove(V3(0.08f, 0.25f, -0.05f) * (i / 10f * k))
        if (!hold) flow.dragEnd()
    }

    /** S07: a real pass: replay a random game up to the Black move after which White has no move, hold, play it. */
    private fun passPosition(flow: GameFlow) {
        for (seed in 1..5000) {
            val rnd = Random(seed)
            var pos = Position.START
            val moves = ArrayList<Int>()
            while (Rules.outcome(pos) == null) {
                pos = Rules.settle(pos).first
                val legal = Rules.squares(Rules.moves(pos))
                val m = legal[rnd.nextInt(legal.size)]
                val next = Rules.play(pos, m)
                if (pos.toMove == Board.BLACK && Rules.moves(next) == 0L && Rules.outcome(next) == null) {
                    flow.load(moves, GameConfig(Mode.AI, Board.BLACK, 3))
                    flow.holdPass = true
                    Ui.page = Page.PLAY
                    flow.tapSquare(m)
                    Log.i(TAG, "DEBUG pass position seed=$seed n=${moves.size} play=${Board.name(m)}")
                    return
                }
                moves += m
                pos = next
            }
        }
    }

    /** S09: a finished random game with the asked result for a human Black (win / lose / draw), through flow.load. */
    private fun finished(flow: GameFlow, want: String) {
        for (seed in 1..200_000) {
            val rnd = Random(seed)
            var pos = Position.START
            val moves = ArrayList<Int>()
            while (Rules.outcome(pos) == null) {
                pos = Rules.settle(pos).first
                val legal = Rules.squares(Rules.moves(pos))
                val m = legal[rnd.nextInt(legal.size)]
                moves += m
                pos = Rules.play(pos, m)
            }
            val r = Rules.outcome(pos)!!
            val ok = when (want) { "lose" -> r.winner == Board.WHITE; "draw" -> r.winner == 0; else -> r.winner == Board.BLACK } && pos.empty == 0L
            if (ok) { flow.load(moves, GameConfig(Mode.AI, Board.BLACK, 3)); Log.i(TAG, "DEBUG S09 $want seed=$seed ${r.black}:${r.white}"); return }
        }
    }

    /** Input replay (board-input README 4): real scene entities -> resolveUp -> the real listener's onTap. */
    private fun replay(args: List<String>) {
        val scene = SceneHolder.scene ?: return
        val listener = SceneHolder.listener ?: return
        val src = when (args.getOrNull(1)) { "eye-hand" -> InputSource.EYE_HAND; "controller" -> InputSource.CONTROLLER; else -> InputSource.HAND_RAY }
        val what = args.getOrNull(0) ?: return
        val entities = if (what.startsWith("trough:")) scene.replayTrough(if (what.endsWith("white")) Board.WHITE else Board.BLACK)
        else Board.parse(what)?.let { scene.replaySquare(it) }.orEmpty()
        for (e in entities) {
            val (hit, path) = resolveUp(e, FlipTargets)
            val square = (hit as? FlipTarget.Square)?.square?.let(Board::name) ?: (hit as? FlipTarget.Trough)?.let { "trough:${it.color}" } ?: "-"
            Log.i(TAG, "REPLAY src=${src.label} path=$path -> square=$square")
            io.embodit.flipdisc.ui.board.input.BoardInputLog.write("BoardInput", "INPUT tap src=${src.label} path=$path -> square=$square")
        }
        entities.lastOrNull()?.let { deepest -> listener.onTap(resolveUp(deepest, FlipTargets).first, src, null) }
    }
}

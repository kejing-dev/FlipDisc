package io.embodit.flipdisc.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Named states of design-package states[] that live in the game (S10–S13 are window pages / cards). */
enum class Phase(val id: String) {
    LOADING("S00"), WELCOME("S01"), SETUP("S02"), IDLE("S03"), DRAGGING("S04"), MOVING("S05"),
    THINKING("S06"), PASS("S07"), RESULT("S09"),
}

enum class Sound { PLACE, FLIP, RETRACT, ILLEGAL, PASS, WIN, LOSE, DRAW }

/** Seat-tag line 2 reasons (design mustHave.rulesEnforced, input.tap). */
enum class AlertKind { NO_FLIP, OCCUPIED, TIP }

data class Alert(val kind: AlertKind, val square: Int?, val at: Long)

/** The move being shown: the disc flies in (tap) or drops from the snap height (drag), then [flips] turn over. */
data class MoveAnim(
    val square: Int,
    val color: Int,
    val before: Position,
    val flips: List<Flip>,
    val startedAt: Long,
    val fromDrag: Boolean,
    /** The trough slot the disc left from (tap flights start there): trough colour, slot index. */
    val fromTrough: Int = color,
    val fromSlot: Int = 0,
) {
    val landMs: Long get() = if (fromDrag) GameFlow.DROP_MS else GameFlow.FLIGHT_MS + GameFlow.LAND_MS
    val totalMs: Long get() = landMs + FlipTiming.total(flips)
}

/** A disc going back to its trough (released off a legal square, or a cancelled drag). */
data class ReturnAnim(val color: Int, val from: V3, val startedAt: Long)

enum class ConfirmKind { NEW, UNDO_2P, RESET, EXIT }

/** The one state every surface reads (board scene, control window, seat tag, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val moves: List<Int> = emptyList(),
    val position: Position = Position.START,
    val legal: Set<Int> = emptySet(),
    val anim: MoveAnim? = null,
    val back: ReturnAnim? = null,
    val snapped: Int? = null,
    val dragColor: Int = 0,
    val alert: Alert? = null,
    val hint: Hint? = null,
    val hintBusy: Boolean = false,
    val passColor: Int = 0,
    val result: GameResult? = null,
    val resultAt: Long = 0,
    val confirm: ConfirmKind? = null,
    /** Debug hold: the move animation stops this many ms after its start. */
    val freezeMs: Long? = null,
    val seed: Int = 0,
) {
    val toMove: Int get() = position.toMove
    val lastMove: Int? get() = moves.lastOrNull()
    val humansTurn: Boolean get() = config?.isHuman(toMove) == true
    val inGame: Boolean get() = config != null && phase in setOf(Phase.IDLE, Phase.DRAGGING, Phase.MOVING, Phase.THINKING, Phase.PASS)
    val black: Int get() = position.count(Board.BLACK)
    val white: Int get() = position.count(Board.WHITE)
    /** Moves played by [color] (for the trough counts). */
    fun movesBy(color: Int): Int = GameFlow.movesByColor(moves)[color - 1]

    /**
     * Discs left in [color]'s trough (30 each; a side that runs out takes from the other trough, design gameSpec.trays).
     * [out] = discs of each colour currently out of the trough but not yet on the board (in the hand / flying back).
     */
    fun troughCount(color: Int, outBlack: Int = 0, outWhite: Int = 0): Int {
        val used = GameFlow.movesByColor(moves)
        val ub = used[0] + outBlack; val uw = used[1] + outWhite
        val mine = if (color == Board.BLACK) ub else uw
        val theirs = if (color == Board.BLACK) uw else ub
        val n = BoardGeometry.TROUGH_SLOTS
        return (n - minOf(n, mine) - maxOf(0, theirs - n)).coerceAtLeast(0)
    }

    /** Where [color]'s next disc comes from: (trough colour, slot index); the near-end disc of its own trough first. */
    fun nextSlot(color: Int): Pair<Int, Int> {
        val n = BoardGeometry.TROUGH_SLOTS
        val own = troughCount(color)
        if (own > 0) return color to (n - own)
        val other = Board.other(color)
        return other to (n - troughCount(other)).coerceAtMost(n - 1)
    }
    val canUndo: Boolean get() {
        val cfg = config ?: return false
        if (phase !in setOf(Phase.IDLE, Phase.THINKING, Phase.PASS, Phase.RESULT)) return false
        return when (cfg.mode) {
            Mode.TWO_PLAYER -> moves.isNotEmpty()
            Mode.AI -> movesBy(cfg.humanColor) > 0
        }
    }
    val canHint: Boolean get() = phase == Phase.IDLE && humansTurn
    /** The side to move has not placed a disc yet this game: the seat tag says how (first-time guide, B19). */
    val showTip: Boolean get() = phase == Phase.IDLE && humansTurn && config?.let { cfg ->
        if (cfg.mode == Mode.AI) movesBy(cfg.humanColor) == 0 else moves.size < 2
    } == true
}

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Turn sequencing (rules - state - render: this is the middle layer). Main-thread confined: every public call and every
 * coroutine it launches runs on [scope]'s dispatcher; only the engine runs on [engineDispatcher]. Debug commands call
 * the same functions the board and the window call. Animation times are wall-clock (uptime) so a slowed renderer never
 * slows the game (D55).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound, Float) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
    seed: Int = 20261116,
) {
    companion object {
        const val FLIGHT_MS = 220L
        const val LAND_MS = 40L
        const val DROP_MS = 80L
        const val RETURN_MS = 220L
        const val PASS_MS = 1200L
        const val MIN_THINK_MS = 400L
        const val ALERT_MS = 1500L
        const val FRAME_MS = 160L

        /** [black, white] moves played, replaying the implicit passes. */
        fun movesByColor(moves: List<Int>): IntArray {
            val n = IntArray(2)
            var pos = Position.START
            for (sq in moves) {
                pos = Rules.settle(pos).first
                n[pos.toMove - 1]++
                pos = Rules.play(pos, sq)
            }
            return n
        }
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    val drag = DragController()
    private val random = Random(seed)
    var aiSeed: Int = seed
    var holdThinking: Boolean = false
    var holdPass: Boolean = false
    var holdAlert: Boolean = false
    /** The volume's current scale (rendered height / design height); drag travel is divided by it. */
    var volumeScale: Float = 1f

    private var moveJob: Job? = null
    private var thinkJob: Job? = null
    private var hintJob: Job? = null
    private var alertJob: Job? = null
    private var passJob: Job? = null
    private var backJob: Job? = null

    private fun set(next: PlayState) { _state.value = next }
    private fun geo() = BoardGeometry(settings().tilt)

    // ---- lifecycle ----

    fun start() {
        val saved = store.load()
        val st = settings()
        if (!st.firstRunDone) { set(s.copy(phase = Phase.WELCOME)); log("STATE S01"); return }
        val pos = saved?.let { Rules.replay(it.moves) }
        if (saved != null && pos != null && Rules.outcome(pos) == null) restore(saved, pos)
        else { set(s.copy(phase = Phase.SETUP)); log("STATE S02") }
    }

    fun welcomeDone() {
        onSettings(settings().copy(firstRunDone = true))
        toSetup()
    }

    private fun restore(saved: SavedGame, pos: Position) {
        cancelJobs()
        set(PlayState(phase = Phase.IDLE, config = saved.config, moves = saved.moves, position = pos, seed = saved.seed))
        log("GAME restored moves=${saved.moves.size}")
        nextTurn()
    }

    fun newGame(setup: GameSetup) {
        val human = when (setup.side) { SidePick.BLACK -> Board.BLACK; SidePick.WHITE -> Board.WHITE; SidePick.RANDOM -> if (random.nextBoolean()) Board.BLACK else Board.WHITE }
        // D30: the random pick only goes into this game's config; the setup keeps "random".
        onSettings(settings().copy(lastSetup = setup))
        newGame(GameConfig(setup.mode, human, setup.level.coerceIn(1, 6)))
    }

    fun newGame(config: GameConfig) {
        cancelJobs()
        drag.end()
        val seed = aiSeed + random.nextInt(1000)
        set(PlayState(phase = Phase.IDLE, config = config, position = Position.START, seed = seed))
        log("GAME new mode=${config.mode} human=${config.humanColor} level=${config.level} seed=$seed")
        persist()
        nextTurn()
    }

    fun again() { s.config?.let { newGame(it) } }

    fun toSetup() {
        cancelJobs()
        drag.end()
        set(PlayState(phase = Phase.SETUP))
        log("STATE S02")
    }

    /** Leaving for the background: stop the engine, finish the animation, put a held disc back, save (design container.lifecycle). */
    fun onBackground() {
        thinkJob?.cancel(); hintJob?.cancel()
        if (drag.active) { drag.end(); set(s.copy(phase = Phase.IDLE, snapped = null, dragColor = 0)) }
        if (s.anim != null) { moveJob?.cancel(); finishMove() }
        persist()
    }

    fun onForeground() {
        if (s.phase == Phase.THINKING && thinkJob?.isActive != true) think()
    }

    // ---- input (board pads, troughs, debug) ----

    /** A pinch / trigger on square [sq] (pad, tile or disc). */
    fun tapSquare(sq: Int) {
        val st = s
        log("TAP ${Board.name(sq)} phase=${st.phase.id}")
        if (st.phase != Phase.IDLE || !st.humansTurn) return
        when {
            sq in st.legal -> commit(sq, fromDrag = false)
            st.position.at(sq) != Board.EMPTY -> alert(AlertKind.OCCUPIED, sq)
            else -> alert(AlertKind.NO_FLIP, sq)
        }
    }

    /** A pinch on a trough without dragging: tell how to play (design input.tap). */
    fun tapTrough(color: Int) {
        log("TAP trough=$color phase=${s.phase.id}")
        if (s.phase == Phase.IDLE && s.humansTurn) alert(AlertKind.TIP, null)
    }

    /** The hand moved past the drag threshold on [color]'s trough. Only the side to move may take a disc. */
    fun dragStart(color: Int): Boolean {
        val st = s
        val cfg = st.config ?: return false
        if (st.phase != Phase.IDLE || !st.humansTurn || color != st.toMove || !cfg.isHuman(color)) {
            log("DRAG refused trough=$color toMove=${st.toMove} phase=${st.phase.id}")
            return false
        }
        val (tc, slot) = st.nextSlot(color)
        drag.begin(color, geo().slot(tc, slot), clock())
        set(st.copy(phase = Phase.DRAGGING, dragColor = color, snapped = null, hint = null, alert = null))
        log("DRAG start color=$color S04b")
        return true
    }

    /** [totalM] = travel since the press in view metres (+X right, +Y up, +Z toward the user). */
    fun dragMove(totalM: V3) {
        if (!drag.active || s.phase != Phase.DRAGGING) return
        val k = if (volumeScale > 0.05f) 1f / volumeScale else 1f
        if (drag.move(totalM * k, geo(), s.legal)) {
            val sn = drag.snap.snapped
            set(s.copy(snapped = sn))
            log("DRAG snap=${sn?.let(Board::name) ?: "-"} ${if (sn != null) "S04a" else "S04b"}")
        }
    }

    fun dragEnd() {
        if (!drag.active) return
        val sq = drag.snap.snapped
        val held = drag.held
        drag.end()
        if (sq != null && sq in s.legal) { log("DRAG release on ${Board.name(sq)}"); commit(sq, fromDrag = true); return }
        // Not snapped: back to the trough; over the felt say why (design input.drag.release).
        val g = geo()
        val over = g.squareAt(g.toLocal(held))
        returnToTrough(held)
        when {
            over == null -> Unit
            s.position.at(over) != Board.EMPTY -> alert(AlertKind.OCCUPIED, over)
            else -> alert(AlertKind.NO_FLIP, over)
        }
        log("DRAG release free over=${over?.let(Board::name) ?: "-"}")
    }

    /** Tracking lost: the disc goes back, no reason shown. */
    fun dragCancel() {
        if (!drag.active) return
        val held = drag.held
        drag.end()
        returnToTrough(held)
        log("DRAG cancel")
    }

    private fun returnToTrough(from: V3) {
        val color = s.dragColor
        set(s.copy(phase = Phase.IDLE, snapped = null, dragColor = 0, back = ReturnAnim(color, from, clock())))
        onSound(Sound.RETRACT, 1f)
        backJob?.cancel()
        backJob = scope.launch { delay(RETURN_MS); set(s.copy(back = null)) }
    }

    private fun alert(kind: AlertKind, sq: Int?) {
        val at = clock()
        set(s.copy(alert = Alert(kind, sq, at)))
        if (kind != AlertKind.TIP) onSound(Sound.ILLEGAL, 1f)
        log("ALERT $kind ${sq?.let(Board::name) ?: ""} S03x")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            while (holdAlert) delay(100)
            if (s.alert?.at == at) set(s.copy(alert = null))
        }
    }

    // ---- moves ----

    private fun commit(sq: Int, fromDrag: Boolean) {
        val st = s
        val before = st.position
        val flips = Rules.flipList(before, sq)
        val after = Rules.play(before, sq)
        val now = clock()
        val (tc, slot) = st.nextSlot(before.toMove)
        val anim = MoveAnim(sq, before.toMove, before, flips, now, fromDrag, tc, slot)
        set(st.copy(
            phase = Phase.MOVING, moves = st.moves + sq, position = after, legal = emptySet(), anim = anim,
            snapped = null, dragColor = 0, hint = null, hintBusy = false, alert = null,
        ))
        hintJob?.cancel()
        log("MOVE ${Board.name(sq)} color=${before.toMove} flips=${flips.size} n=${s.moves.size} drag=$fromDrag S05")
        persist()
        moveJob?.cancel()
        moveJob = scope.launch {
            waitAnim(anim, anim.landMs)
            onSound(Sound.PLACE, 1f)
            val rings = flips.map { it.distance }.distinct().sorted()
            for (d in rings) {
                waitAnim(anim, anim.landMs + FlipTiming.startOf(d))
                onSound(Sound.FLIP, 1f + 0.02f * (d - 1))
            }
            waitAnim(anim, anim.totalMs)
            finishMove()
        }
    }

    /** Waits until [ms] after the animation start, respecting a debug freeze. */
    private suspend fun waitAnim(anim: MoveAnim, ms: Long) {
        while (coroutineContext.isActive) {
            val f = s.freezeMs
            val elapsed = clock() - anim.startedAt
            if (f != null && f < ms) { delay(50); continue }
            if (elapsed >= ms) return
            delay(minOf(ms - elapsed, 50L))
        }
    }

    private fun finishMove() {
        val st = s
        set(st.copy(anim = null))
        val result = Rules.outcome(st.position)
        if (result != null) { finish(result); return }
        val (pos, passed) = Rules.settle(st.position)
        if (passed) {
            val who = st.position.toMove
            set(s.copy(phase = Phase.PASS, position = pos, passColor = who, legal = emptySet()))
            onSound(Sound.PASS, 1f)
            log("PASS color=$who S07")
            passJob?.cancel()
            passJob = scope.launch {
                delay(PASS_MS)
                while (holdPass) delay(100)
                if (s.phase == Phase.PASS) { set(s.copy(passColor = 0)); nextTurn() }
            }
            return
        }
        nextTurn()
    }

    private fun finish(result: GameResult) {
        cancelJobs()
        val st = s
        set(st.copy(phase = Phase.RESULT, result = result, resultAt = clock(), anim = null, hint = null, legal = emptySet(), confirm = null))
        val cfg = st.config
        // The opponent's win is not a fanfare: an AI win plays the low note.
        val sound = when {
            result.winner == 0 -> Sound.DRAW
            cfg != null && cfg.mode == Mode.AI && result.winner == cfg.aiColor -> Sound.LOSE
            else -> Sound.WIN
        }
        onSound(sound, 1f)
        log("RESULT winner=${result.winner} ${result.black}:${result.white} reason=${result.reason} moves=${st.moves.size} S09")
        store.clear()
    }

    private fun nextTurn() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT) return
        if (cfg.isHuman(st.toMove)) {
            set(st.copy(phase = Phase.IDLE, legal = Rules.squares(Rules.moves(st.position)).toSet()))
            log("STATE S03 toMove=${st.toMove} legal=${s.legal.joinToString(",") { Board.name(it) }}")
        } else think()
    }

    private fun think() {
        val st = s
        val cfg = st.config ?: return
        set(st.copy(phase = Phase.THINKING, legal = emptySet(), hint = null))
        log("THINK level=${cfg.level} S06")
        thinkJob?.cancel()
        val pos = st.position
        val ply = st.moves.size
        val seed = st.seed + ply
        thinkJob = scope.launch {
            val t0 = clock()
            val move = withContext(engineDispatcher) {
                val job = coroutineContext[Job]
                Engine(Random(seed), cancelled = { job?.isActive == false }).bestMove(pos, Level.of(cfg.level), ply)
            }
            val waited = clock() - t0
            if (waited < MIN_THINK_MS) delay(MIN_THINK_MS - waited)
            while (holdThinking) delay(100)
            if (s.phase != Phase.THINKING || s.position != pos || move < 0) return@launch
            log("AI ${Board.name(move)} after ${clock() - t0} ms")
            commit(move, fromDrag = false)
        }
    }

    // ---- hint (design gameSpec.hint, three states) ----

    fun toggleHint() {
        val st = s
        if (st.hint != null || st.hintBusy) { hintJob?.cancel(); set(st.copy(hint = null, hintBusy = false)); log("HINT off"); return }
        if (!st.canHint) return
        val pos = st.position
        set(st.copy(hintBusy = true))
        hintJob = scope.launch {
            val h = withContext(engineDispatcher) {
                val job = coroutineContext[Job]
                Engine(Random(st.seed), cancelled = { job?.isActive == false }).hint(pos)
            }
            if (s.position != pos || s.phase != Phase.IDLE) { set(s.copy(hintBusy = false)); return@launch }
            set(s.copy(hint = h, hintBusy = false))
            // hint is silent (design sound.silent)
            log("HINT ${h?.let { "${Board.name(it.square)} ${it.kind} ${it.diff}" }} S08")
        }
    }

    /** Debug: show a hint of a given kind on the engine's best square (for the S08a–c screenshots). */
    fun debugHint(kind: HintKind, diff: Int) {
        val st = s
        val sq = st.legal.firstOrNull() ?: return
        val best = Engine(Random(st.seed)).bestMove(st.position, Level.of(3), 99).takeIf { it >= 0 } ?: sq
        set(st.copy(hint = Hint(best, kind, diff), hintBusy = false))
        log("HINT debug ${Board.name(best)} $kind $diff S08")
    }

    // ---- undo ----

    fun requestUndo() {
        val cfg = s.config ?: return
        if (!s.canUndo) return
        if (cfg.mode == Mode.TWO_PLAYER) { set(s.copy(confirm = ConfirmKind.UNDO_2P)); log("CONFIRM UNDO_2P S10") } else undo()
    }

    private fun undo() {
        val cfg = s.config ?: return
        moveJob?.cancel(); thinkJob?.cancel(); hintJob?.cancel(); passJob?.cancel()
        val moves = s.moves.toMutableList()
        if (cfg.mode == Mode.TWO_PLAYER) {
            moves.removeAt(moves.lastIndex)
        } else {
            // back to just before your last move, across the AI's replies and implicit passes (design stateRules.undo)
            while (moves.isNotEmpty()) {
                val before = Rules.replay(moves.dropLast(1))!!
                moves.removeAt(moves.lastIndex)
                if (before.toMove == cfg.humanColor) break
            }
        }
        val pos = Rules.replay(moves)!!
        set(s.copy(moves = moves, position = pos, anim = null, result = null, phase = Phase.IDLE, confirm = null, hint = null, passColor = 0))
        onSound(Sound.RETRACT, 1f)
        log("UNDO -> n=${moves.size}")
        persist()
        nextTurn()
    }

    // ---- confirm cards ----

    fun requestConfirm(kind: ConfirmKind) { set(s.copy(confirm = kind)); log("CONFIRM $kind S10") }

    fun cancelConfirm() { set(s.copy(confirm = null)) }

    /** [新开一局]: asks first once a move has been made (design T13). */
    fun requestNew() {
        if (s.inGame && s.moves.isNotEmpty()) requestConfirm(ConfirmKind.NEW) else toSetup()
    }

    /** Confirms the open card. EXIT is handled by the window (it owns the navigator); it only saves here. */
    fun confirm() {
        val kind = s.confirm ?: return
        set(s.copy(confirm = null))
        when (kind) {
            ConfirmKind.UNDO_2P -> undo()
            ConfirmKind.NEW -> { store.clear(); toSetup() }
            ConfirmKind.RESET -> resetProgress()
            ConfirmKind.EXIT -> onBackground()
        }
    }

    /** design dataModel: deletes the game, rules show again; volume / language / angle stay. */
    fun resetProgress() {
        cancelJobs()
        store.clear()
        onSettings(settings().copy(firstRunDone = false, lastSetup = GameSetup()))
        set(PlayState(phase = Phase.WELCOME))
        log("RESET progress S01")
    }

    // ---- debug ----

    /** Debug: stop the move animation [ms] after its start (hold flip), or release it. */
    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms)); log("FREEZE ${ms ?: "off"}") }

    /** Debug: replace the game with [moves] (validated, passes implicit). */
    fun load(moves: List<Int>, config: GameConfig = s.config ?: GameConfig()) {
        cancelJobs()
        drag.end()
        val ok = ArrayList<Int>()
        var pos = Position.START
        for (sq in moves) {
            pos = Rules.settle(pos).first
            if (!Rules.isLegal(pos, sq)) break
            pos = Rules.play(pos, sq)
            ok += sq
        }
        pos = Rules.settle(pos).first
        set(PlayState(phase = Phase.IDLE, config = config, moves = ok, position = pos, seed = s.seed, freezeMs = s.freezeMs))
        log("LOAD n=${ok.size}")
        persist()
        Rules.outcome(pos)?.let { finish(it); return }
        nextTurn()
    }

    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    fun dump(): String {
        val st = s
        return "phase=${st.phase.id} cfg=${st.config} n=${st.moves.size} moves=${st.moves.joinToString(" ") { Board.name(it) }} " +
            "toMove=${st.toMove} score=${st.black}:${st.white} legal=${st.legal.joinToString(",") { Board.name(it) }} snapped=${st.snapped?.let(Board::name)} " +
            "hint=${st.hint} pass=${st.passColor} result=${st.result} confirm=${st.confirm} freeze=${st.freezeMs} drag=${drag.active} held=${drag.held}"
    }

    private fun persist() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT) return
        store.save(SavedGame(cfg, st.moves, st.seed))
    }

    private fun cancelJobs() {
        moveJob?.cancel(); thinkJob?.cancel(); hintJob?.cancel(); alertJob?.cancel(); passJob?.cancel(); backJob?.cancel()
    }
}

package io.embodit.flipdisc.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.flipdisc.data.FileGameStore
import io.embodit.flipdisc.data.SettingsStore
import io.embodit.flipdisc.domain.GameFlow
import io.embodit.flipdisc.domain.Sound
import io.embodit.flipdisc.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

const val TAG = "FlipDisc"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.flipdisc.ui.board.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileGameStore(context)
    val flow = GameFlow(
        scope = scope,
        engineDispatcher = Dispatchers.Default,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { snd, rate -> sounds.play(snd, rate) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()

    init {
        scope.launch { settings.settings.collect { s -> sounds.volumeLevel = s.volume } }
        flow.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Effects from assets/sounds (profile D51, design sound). Disc sounds are real CC0 recordings of a stone on a wooden board
 * (build/sound-sources.md): place / place_2 / place_3 rotate with a random ±5 % pitch; flip = the lighter retract take,
 * one per ring of the domino, pitch +2 % per ring; retract = disc back to its trough / undo. Short cues (illegal, win,
 * lose / pass / draw) are CC0 interface recordings, played quieter. Dragging, snapping and hints are silent. One sound of
 * a kind at a time: a new one stops the previous one of the same kind (no stacking, D51).
 */
class SoundPlayer(context: Context) {
    /** 0 = muted, 1..3 = low / mid / high (design sound.mute: 35 / 65 / 100 %). */
    @Volatile var volumeLevel = 3

    private val pool = SoundPool.Builder().setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastVariant = HashMap<String, Int>()
    private val lastStream = HashMap<Sound, Int>()

    private fun fileOf(s: Sound): String = when (s) {
        Sound.PLACE -> "place"
        Sound.FLIP, Sound.RETRACT -> "retract"
        Sound.ILLEGAL -> "illegal"
        Sound.WIN -> "win"
        Sound.LOSE, Sound.DRAW, Sound.PASS -> "lose"
    }

    private fun gainOf(s: Sound): Float = when (s) { Sound.PLACE -> 1f; Sound.FLIP, Sound.RETRACT -> 0.6f; else -> 0.55f }

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (base in Sound.entries.map { fileOf(it) }.toSet()) {
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[base] = found else Log.w(TAG, "no sound for $base")
        }
    }

    fun play(s: Sound, rateMul: Float = 1f) {
        val level = volumeLevel.coerceIn(0, 3)
        if (level == 0) return
        val base = fileOf(s)
        val variants = ids[base] ?: return
        val vol = VOLUME_PERCENT[level] / 100f * gainOf(s)
        var v = if (variants.size > 1) random.nextInt(variants.size) else 0
        if (variants.size > 1 && v == lastVariant[base]) v = (v + 1) % variants.size
        lastVariant[base] = v
        val jitter = if (s == Sound.PLACE || s == Sound.RETRACT) 0.95f + random.nextFloat() * 0.10f else 1f
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        lastStream[s]?.let { pool.stop(it) }
        lastStream[s] = pool.play(id, vol, vol, 1, 0, (jitter * rateMul).coerceIn(0.5f, 2f))
    }

    fun release() = pool.release()

    companion object {
        /** Volume percent of each snap stop (Settings.volume 0..3). */
        val VOLUME_PERCENT = intArrayOf(0, 35, 65, 100)
    }
}

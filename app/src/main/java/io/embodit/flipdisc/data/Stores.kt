package io.embodit.flipdisc.data

import android.content.Context
import android.util.Log
import io.embodit.flipdisc.domain.GameSetup
import io.embodit.flipdisc.domain.GameStore
import io.embodit.flipdisc.domain.Language
import io.embodit.flipdisc.domain.Mode
import io.embodit.flipdisc.domain.SaveCodec
import io.embodit.flipdisc.domain.SavedGame
import io.embodit.flipdisc.domain.Settings
import io.embodit.flipdisc.domain.SidePick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.json, written atomically after every move: a temp file, a read-back check, then a rename; the
 * previous good copy stays as game.bak (Chess Stores.kt pattern).
 */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.json")
    private val backup = File(dir, "game.bak")

    override fun load(): SavedGame? =
        listOf(file, backup).firstNotNullOfOrNull { f -> runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching {
            val tmp = File(dir, "game.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText()) == game) { "read-back mismatch" }
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("FlipDisc", "SAVE failed", it) }
    }

    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so every window recomposes on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putInt("volume", s.volume)
            .putInt("lastVolume", s.lastVolume)
            .putInt("tilt", s.tilt)
            .putBoolean("firstRunDone", s.firstRunDone)
            .putString("setupMode", s.lastSetup.mode.name)
            .putString("setupSide", s.lastSetup.side.name)
            .putInt("setupLevel", s.lastSetup.level)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            volume = prefs.getInt("volume", 3).coerceIn(0, 3),
            lastVolume = prefs.getInt("lastVolume", 3).coerceIn(1, 3),
            tilt = prefs.getInt("tilt", Settings.DEFAULT_TILT).takeIf { it in Settings.TILTS } ?: Settings.DEFAULT_TILT,
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            lastSetup = GameSetup(
                mode = Mode.valueOf(prefs.getString("setupMode", Mode.AI.name)!!),
                side = SidePick.valueOf(prefs.getString("setupSide", SidePick.BLACK.name)!!),
                level = prefs.getInt("setupLevel", 1).coerceIn(1, 6),
            ),
        )
    }.getOrElse { Settings() }
}

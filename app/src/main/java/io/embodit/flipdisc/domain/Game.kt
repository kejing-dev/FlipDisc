package io.embodit.flipdisc.domain

enum class Mode { AI, TWO_PLAYER }

enum class SidePick { BLACK, WHITE, RANDOM }

enum class Language { SYSTEM, ZH, EN }

/** One game's setup. [humanColor] is resolved (never random here); in two-player mode it is unused. */
data class GameConfig(
    val mode: Mode = Mode.AI,
    val humanColor: Int = Board.BLACK,
    val level: Int = 2,
) {
    fun isHuman(color: Int): Boolean = mode == Mode.TWO_PLAYER || color == humanColor
    val aiColor: Int get() = Board.other(humanColor)
}

/** What a game looks like on disk (files/game.json): config + the moves (passes implicit) + the seed. */
data class SavedGame(val config: GameConfig, val moves: List<Int>, val seed: Int = 0)

/** App settings (SharedPreferences). [volume] 0..3 = off / low / mid / high; [lastVolume] is what [静音] goes back to. */
data class Settings(
    val language: Language = Language.SYSTEM,
    /** Default High (100 %): the CC0 clicks are short, B20 "loud enough"; the four stops are in Settings. */
    val volume: Int = 3,
    val lastVolume: Int = 3,
    val tilt: Int = 30,
    val firstRunDone: Boolean = false,
    val lastSetup: GameSetup = GameSetup(),
) {
    val muted: Boolean get() = volume == 0

    companion object {
        /** design spatialLayout.tilt: 20 / 30 / 45 / 60 / 90°, default 30° (0° removed: occlusion). */
        val TILTS = listOf(20, 30, 45, 60, 90)
        const val DEFAULT_TILT = 30
    }
}

/** The setup page's choices (remembered between games; a random colour is resolved per game and never stored, D30). */
data class GameSetup(
    val mode: Mode = Mode.AI,
    val side: SidePick = SidePick.BLACK,
    val level: Int = 1,
)

/**
 * JSON-ish line codec for [SavedGame], kept dependency-free:
 * `{"v":1,"mode":"AI","human":1,"level":3,"seed":7,"moves":"f5 d6 c3"}`.
 */
object SaveCodec {
    fun encode(g: SavedGame): String =
        "{\"v\":1,\"mode\":\"${g.config.mode.name}\",\"human\":${g.config.humanColor},\"level\":${g.config.level},\"seed\":${g.seed}," +
            "\"moves\":\"${g.moves.joinToString(" ") { Board.name(it) }}\"}"

    private fun field(text: String, key: String): String? {
        val m = Regex("\"$key\":(\"[^\"]*\"|-?[0-9]+)").find(text) ?: return null
        return m.groupValues[1].trim('"')
    }

    fun decode(text: String): SavedGame? = runCatching {
        if (field(text, "v") != "1") return null
        val moves = field(text, "moves")!!.split(" ").filter { it.isNotBlank() }.map { Board.parse(it)!! }
        if (Rules.replay(moves) == null) return null
        SavedGame(
            config = GameConfig(Mode.valueOf(field(text, "mode")!!), field(text, "human")!!.toInt().coerceIn(1, 2), field(text, "level")!!.toInt().coerceIn(1, 6)),
            moves = moves,
            seed = field(text, "seed")?.toInt() ?: 0,
        )
    }.getOrNull()
}

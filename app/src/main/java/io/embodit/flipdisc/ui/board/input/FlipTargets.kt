package io.embodit.flipdisc.ui.board.input

import io.embodit.flipdisc.domain.Board

/** What a hit means: a square (pad / tile / disc) or a trough. */
sealed class FlipTarget {
    data class Square(val square: Int) : FlipTarget()
    data class Trough(val color: Int) : FlipTarget()
}

/**
 * Entity names come only from here (board-input README 必带单测 1): the scene calls these, the resolver parses them.
 * Every pickable holder carries Collision + Interactable + HoverEffect; its visible meshes are children named after it.
 */
object BoardNames {
    fun pad(sq: Int) = "pad:$sq"
    fun tile(sq: Int) = "tile:$sq"
    fun piece(sq: Int) = "piece:$sq"
    fun discTop(sq: Int) = "disc:$sq:top"
    fun discBottom(sq: Int) = "disc:$sq:bottom"
    fun trough(color: Int) = "trough:${colorName(color)}"
    fun troughBody(color: Int) = "troughBody:${colorName(color)}"
    fun slot(color: Int, i: Int) = "slot:${colorName(color)}:$i"

    private fun colorName(color: Int) = if (color == Board.BLACK) "black" else "white"
    private fun colorOf(name: String): Int? = when (name) { "black" -> Board.BLACK; "white" -> Board.WHITE; else -> null }

    /** Name -> target; null for anything else, including out-of-range squares (a naming bug shows up in the log as "-"). */
    fun resolveName(name: String): FlipTarget? {
        val parts = name.split(":")
        return when (parts[0]) {
            "pad", "tile", "piece", "disc" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until Board.SIZE }?.let { FlipTarget.Square(it) }
            "trough", "troughBody", "slot" -> parts.getOrNull(1)?.let(::colorOf)?.let { FlipTarget.Trough(it) }
            else -> null
        }
    }
}

val FlipTargets: TargetResolver<FlipTarget> = TargetResolver.byName(BoardNames::resolveName)

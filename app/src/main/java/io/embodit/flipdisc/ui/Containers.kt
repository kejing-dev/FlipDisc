package io.embodit.flipdisc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The board is the default container (manifest). */
object Containers {
    const val BOARD = "FlipBoard"
    const val CONTROL = "FlipControl"
    const val CONTROL_TAG = "control"
}

/** The three native TabBar pages (design windowModel: 下棋 / 规则 / 设置). */
enum class Page(val stateId: String) { PLAY("S03"), RULES("S13"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.RULES)
    /** Settings › More settings (S12), a pushed page inside the Settings tab. */
    var moreOpen by mutableStateOf(false)
    /** Rules tab: 0 = rules, 1 = how to play (same-page option). */
    var rulesView by mutableIntStateOf(0)
    /** The first page has been picked for this process (a restored game opens on Play, a first run on Rules). */
    var pagePicked = false
}

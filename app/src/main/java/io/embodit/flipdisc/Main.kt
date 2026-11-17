package io.embodit.flipdisc

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import androidx.compose.foundation.layout.Box
import io.embodit.flipdisc.ui.Containers
import io.embodit.flipdisc.ui.board.BoardScreen
import io.embodit.flipdisc.ui.control.ControlWindow
import io.embodit.flipdisc.ui.theme.FlipTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the board volume
 * (default container, 0.94 x 0.80 x 0.88 m from the manifest) and the control window (Planar 560 x 552 dp, fixed size so
 * text is never squeezed) placed to its right by the system.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            FlipTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 552.dp),
            // First open and every reopen: beside the board volume (profile D44 2026-11-05: Shared Space has no "bring it in
            // front"; Placement.none() does not follow the head and lands on top of the board).
            // Debug builds can reopen it below / left of the board (`panelat`) so the emulator's narrow default view frames the
            // whole window for QA shots; normal runs always use right.
            placement = {
                containers.firstOrNull { it.id == Containers.BOARD }?.let {
                    when (io.embodit.flipdisc.platform.WindowCtl.debugPlacement) { "left" -> Placement.left(it); "bottom" -> Placement.bottom(it); else -> Placement.right(it) }
                } ?: Placement.none()
            },
        ) {
            FlipTheme {
                Box(Modifier.windowConstraints(minWidth = 560.dp, minHeight = 552.dp, maxWidth = 560.dp, maxHeight = 552.dp)) { ControlWindow() }
            }
        }
    }

package io.embodit.flipdisc.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Text
import io.embodit.flipdisc.domain.Board
import io.embodit.flipdisc.platform.TAG
import io.embodit.flipdisc.ui.theme.FlipColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; the overflow scans read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

/** The page's main button: 72 dp, icon + words (design panels: "主按钮加高到 72 dp", D23 icons carry words). */
@Composable
internal fun PrimaryButton(text: String, icon: Int?, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 72.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = FlipColors.Primary, contentColor = FlipColors.OnPrimary),
    ) {
        if (icon != null) { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = FlipColors.OnPrimary); Box(Modifier.width(10.dp)) }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = FlipColors.OnPrimary)
    }
}

/** Secondary buttons: 64 dp (B31), icon + words; [danger] = error fill with white words (Quit). */
@Composable
internal fun SecondaryButton(text: String, icon: Int?, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val fg = if (danger) FlipColors.OnError else FlipColors.Ink
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) FlipColors.Error else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = fg); Box(Modifier.width(8.dp)) }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled same-page option (SegmentControl never changes pages, D8). The control is 52 dp (>= 48, B19). */
@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SegmentControl(modifier = modifier.height(52.dp)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = PicoTheme.typography.labelLarge, color = FlipColors.Ink)
                },
            )
        }
    }
}

/**
 * A slider with [stops] snap stops and their labels under it (B20 / design: volume four stops, angle five, level six).
 * The thumb follows a local float while dragging and snaps on release, so taps and small drags are never swallowed
 * (gate slider-quantized); the callback goes through rememberUpdatedState (SpatialUI Slider keeps its first closure,
 * gate slider-stale). 48 dp tall (gate slider-height).
 */
@Composable
internal fun StopSlider(index: Int, labels: List<String>, tag: String, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val stops = labels.size
    val current by rememberUpdatedState(onChange)
    var v by remember { mutableFloatStateOf(index.toFloat()) }
    LaunchedEffect(index) { v = index.toFloat() }
    Column(modifier) {
        Slider(
            value = v,
            onValueChange = { x -> v = x },
            onValueChangeFinished = {
                val k = kotlin.math.round(v).toInt().coerceIn(0, stops - 1)
                v = k.toFloat()
                current(k)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            valueRange = 0f..(stops - 1).toFloat(),
        )
        // label cells: half-width at the ends, full in between, so each label sits under its stop
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                val end = i == 0 || i == stops - 1
                val align = when (i) { 0 -> TextAlign.Start; stops - 1 -> TextAlign.End; else -> TextAlign.Center }
                Txt(l, "$tag/stop", style = PicoTheme.typography.labelMedium, color = if (i == index) FlipColors.Ink else FlipColors.InkSecondary,
                    align = align, modifier = Modifier.weight(if (end) 0.5f else 1f))
            }
        }
    }
}

/** A disc-coloured dot (ivory gets an ink rim so it shows on light glass). */
@Composable
internal fun DiscDot(color: Int, size: Dp = 18.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (color == Board.BLACK) FlipColors.DiscBlack else FlipColors.DiscWhite)
            .border(1.5.dp, FlipColors.Ink, CircleShape),
    )
}

/** A rounded card on the window glass. */
@Composable
internal fun Card(modifier: Modifier = Modifier, color: Color = PicoTheme.colorScheme.fillSecondary, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(color), contentAlignment = Alignment.CenterStart) { content() }
}

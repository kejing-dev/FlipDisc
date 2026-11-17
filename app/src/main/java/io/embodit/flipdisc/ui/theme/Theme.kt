package io.embodit.flipdisc.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.flipdisc.domain.Language
import io.embodit.flipdisc.platform.Graph
import java.util.Locale

/** design-package palette: felt green, copper hints, legal-point blue; 3D colours from palette.board3d / boardMarks. */
object FlipColors {
    val Primary = Color(0xFF2E7D5B)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFCFE9DB)
    val OnPrimaryContainer = Color(0xFF0F3A28)
    val Secondary = Color(0xFFB5763A)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFF3DEC6)
    val OnSecondaryContainer = Color(0xFF4A2B0E)
    val Tertiary = Color(0xFF4C8DFF)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFD9E6FF)
    val OnTertiaryContainer = Color(0xFF0B2A66)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    /** Solid ink on glass (design: onSurface #1B1B1B, never the vibrant grey). */
    val Ink = Color(0xFF1B1B1B)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Error text on glass. */
    val ErrorText = Color(0xFF7A1A18)
    /** Greyed "lost" hint card. */
    val LostContainer = Color(0xFFDDE0E3)

    // 3D (palette.board3d)
    val Felt = Color(0xFF3A8A5C)
    val Tile = Color(0xFF2F7850)
    val GridLine = Color(0xFF2A6645)
    val Frame = Color(0xFF5C3D26)
    val Trough = Color(0xFF6B4A2F)
    val DiscBlack = Color(0xFF1B1B1D)
    val DiscWhite = Color(0xFFEDE6D6)
    // marks (palette.boardMarks)
    val LegalRing = Color(0xFFF6F1E4)
    val SnapRing = Color(0xFFBFE3FF)
    val HintRing = Color(0xFFF2C14E)
    val LostRing = Color(0xFFC9CDD2)
    val IllegalFrame = Color(0xFFFFC9C2)
    val LastDot = Color(0xFFE2572B)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). [language] localises resources below this point
 * through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun FlipTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = FlipColors.OnPrimary,
        labelPrimary = FlipColors.Ink,
        labelSecondary = FlipColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = FlipColors.Error,
        alert = FlipColors.Secondary,
        passable = FlipColors.Primary,
        interaction = FlipColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}

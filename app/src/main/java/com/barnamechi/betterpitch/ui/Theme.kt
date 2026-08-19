package com.barnamechi.betterpitch.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barnamechi.betterpitch.music.Notes

/**
 * The one place the app's look is defined. Everything here is `internal` rather than `private`
 * because top-level `private` in Kotlin is file-scoped, and both screens need these.
 *
 * Note [Surface] and [Line] deliberately shadow material3 names - never `import material3.*` in
 * this package, import the symbols you use one by one.
 */

internal val Ink = Color(0xFF181328)
internal val Surface = Color(0xFF221A3A)
internal val Line = Color(0xFF3A2F60)
internal val Honey = Color(0xFFE8B04B)
internal val Mint = Color(0xFF57D9A3)
internal val Coral = Color(0xFFEF7D68)
internal val TextC = Color(0xFFEFEAFF)
internal val Muted = Color(0xFFA99FCF)

/** Inset wells (chips, stat boxes) and the text colours that sit on Honey / Coral. */
internal val Well = Color(0xFF2C2250)
internal val OnHoney = Color(0xFF241A05)
internal val OnCoral = Color(0xFF2A0D08)

/** Snappy, slightly bouncy key-press feedback. */
internal val keyPressSpring = spring<Float>(
    dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh
)

internal val BPM_CHOICES = listOf(60, 72, 84, 96, 120)

internal val CardShape = RoundedCornerShape(18.dp)
internal val ChipShape = RoundedCornerShape(10.dp)

/**
 * Locked keys keep their exact place in the C2-C6 layout and simply recede. Composited against
 * [Ink] rather than drawn with alpha, so a dimmed key never lets its neighbour show through.
 */
internal fun dim(c: Color): Color = lerp(Ink, c, 0.35f)

/** The panel chrome every card in the app shares. */
internal fun Modifier.card(pad: Int = 16): Modifier = this
    .fillMaxWidth()
    .clip(CardShape)
    .background(Surface)
    .border(1.dp, Line, CardShape)
    .padding(pad.dp)

/** Note label honouring the solfège toggle; Notes.solfege falls back to the name on black keys. */
internal fun label(m: Int, solfege: Boolean): String =
    if (solfege) Notes.solfege(m) else Notes.name(m)

/** Octave-agnostic label: "C" / "Do" rather than "C4" / "Do". */
internal fun pcLabel(m: Int, solfege: Boolean): String =
    if (solfege) Notes.solfege(m) else Notes.NAMES[((m % 12) + 12) % 12]

/** A row of equal-width segmented chips. Used for BPM and for the game's note spacing. */
@Composable
internal fun <T> RowScope.Chips(
    options: List<T>,
    selected: T,
    enabled: Boolean = true,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    options.forEach { choice ->
        val isSelected = choice == selected
        val bg = if (isSelected) Honey else Well
        Box(
            Modifier
                .weight(1f)
                .clip(ChipShape)
                .background(if (enabled) bg else dim(bg))
                .border(1.dp, if (isSelected) Honey else Line, ChipShape)
                .clickable(enabled = enabled) { onSelect(choice) }
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label(choice),
                color = if (isSelected) OnHoney else if (enabled) Muted else dim(Muted),
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun <T> ChipRow(
    options: List<T>,
    selected: T,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chips(options, selected, enabled, label, onSelect)
    }
}

/** The app has no navigation library; one enum plus a `when` is the whole router. */
internal enum class Route { Home, SightReading }

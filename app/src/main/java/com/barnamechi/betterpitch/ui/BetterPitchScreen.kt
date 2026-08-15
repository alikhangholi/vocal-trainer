package com.barnamechi.betterpitch.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barnamechi.betterpitch.billing.BillingStatus
import com.barnamechi.betterpitch.music.Notes
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink = Color(0xFF181328)
private val Surface = Color(0xFF221A3A)
private val Line = Color(0xFF3A2F60)
private val Honey = Color(0xFFE8B04B)
private val Mint = Color(0xFF57D9A3)
private val Coral = Color(0xFFEF7D68)
private val TextC = Color(0xFFEFEAFF)
private val Muted = Color(0xFFA99FCF)

/** Snappy, slightly bouncy key-press feedback. */
private val keyPressSpring = spring<Float>(
    dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh
)

private val BPM_CHOICES = listOf(60, 72, 84, 96, 120)

/**
 * Locked keys keep their exact place in the C2-C6 layout and simply recede. Composited against
 * [Ink] rather than drawn with alpha, so a dimmed key never lets its neighbour show through.
 */
private fun dim(c: Color): Color = lerp(Ink, c, 0.35f)

@Composable
fun BetterPitchScreen(
    detectedMidi: Int?,
    cents: Int,
    listening: Boolean,
    loMidi: Int?,
    hiMidi: Int?,
    onKeyDown: (Int, Boolean) -> Unit,
    onKeyUp: (Boolean) -> Unit,
    onToggleListen: () -> Unit,
    metronomeOn: Boolean,
    bpm: Int,
    onToggleMetronome: () -> Unit,
    onBpmChange: (Int) -> Unit,
    isPremium: Boolean,
    billingStatus: BillingStatus,
    onUnlock: () -> Unit,
) {
    var solfege by remember { mutableStateOf(false) }
    var sustain by remember { mutableStateOf(false) }
    var latched by remember { mutableStateOf<Int?>(null) }
    val keyboardScroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text("BETTERPITCH", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
        Text("Play any note, sing it back", color = TextC, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap a key to hear it. Turn on Sustain to hold a note continuously. Start listening and the key you sing lights up green.",
            color = Muted, fontSize = 13.sp
        )

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToggleChip("Solfège (Do Re Mi)", solfege) { solfege = it }
            Spacer(Modifier.width(16.dp))
            ToggleChip("Sustain (hold note)", sustain) {
                sustain = it
                if (!it && latched != null) { onKeyUp(true); latched = null }
            }
        }

        Spacer(Modifier.height(16.dp))
        Keyboard(
            detectedMidi = detectedMidi,
            latched = latched,
            solfege = solfege,
            scroll = keyboardScroll,
            isPremium = isPremium,
            onLockedPress = onUnlock,
            onPress = { m ->
                if (sustain) {
                    if (latched == m) { onKeyUp(true); latched = null }
                    else { onKeyDown(m, true); latched = m }
                } else onKeyDown(m, false) // struck: rings and fades on its own
            },
            onRelease = { if (!sustain) onKeyUp(false) } // damper
        )

        Spacer(Modifier.height(10.dp))
        // 44.dp per white key, same as Keyboard's own layout
        KeyboardOverview(
            scroll = keyboardScroll,
            contentWidth = 44.dp * (Notes.LOW..Notes.HIGH).count { !Notes.isBlack(it) },
            detectedMidi = detectedMidi,
            latched = latched,
        )

        Spacer(Modifier.height(18.dp))
        MetronomeBar(metronomeOn, bpm, onToggleMetronome, onBpmChange)

        if (!isPremium) {
            Spacer(Modifier.height(18.dp))
            UnlockPanel(billingStatus, onUnlock)
        }

        Spacer(Modifier.height(18.dp))
        MicPanel(detectedMidi, cents, listening, loMidi, hiMidi, solfege, isPremium, onToggleListen, onUnlock)
    }
}

/** Note label honouring the solfège toggle; Notes.solfege falls back to the name on black keys. */
private fun label(m: Int, solfege: Boolean): String =
    if (solfege) Notes.solfege(m) else Notes.name(m)

@Composable
private fun ToggleChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink, checkedTrackColor = Honey,
                uncheckedThumbColor = Muted, uncheckedTrackColor = Surface,
                uncheckedBorderColor = Line
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (checked) TextC else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Keyboard(
    detectedMidi: Int?,
    latched: Int?,
    solfege: Boolean,
    scroll: ScrollState,
    isPremium: Boolean,
    onLockedPress: () -> Unit,
    onPress: (Int) -> Unit,
    onRelease: () -> Unit,
) {
    val whiteW = 44.dp
    val blackW = 28.dp
    val whites = (Notes.LOW..Notes.HIGH).filter { !Notes.isBlack(it) }
    val totalW = whiteW * whites.size

    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val whitesBeforeC4 = whites.count { it < Notes.MIDDLE_C }
        val px = with(density) { (whiteW * whitesBeforeC4).toPx() }
        scroll.scrollTo((px - 300).coerceAtLeast(0f).roundToInt())
    }

    Box(
        Modifier
            .horizontalScroll(scroll)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Box(Modifier.width(totalW).height(150.dp)) {
            // white keys
            Row {
                whites.forEach { m ->
                    val lit = detectedMidi == m || latched == m
                    val locked = !isPremium && !Notes.isFree(m)
                    WhiteKey(m, whiteW, lit, solfege, locked, onLockedPress, onPress, onRelease)
                }
            }
            // black keys overlaid
            (Notes.LOW..Notes.HIGH).filter { Notes.isBlack(it) }.forEach { m ->
                val whitesBefore = whites.count { it < m }
                val x = whiteW * whitesBefore - blackW / 2
                val lit = detectedMidi == m || latched == m
                // No black key is in the free set, so in the free tier every one of them is locked.
                val locked = !isPremium
                var pressed by remember(m) { mutableStateOf(false) }
                val dip by animateFloatAsState(if (pressed) 1f else 0f, keyPressSpring, label = "blackDip")
                val shape = RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)
                Box(
                    Modifier
                        .offset(x = x)
                        .width(blackW).height(94.dp)
                        .graphicsLayer {
                            scaleY = 1f - 0.045f * dip
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        }
                        .clip(shape)
                        .background(
                            when {
                                locked -> dim(Color(0xFF241A3A))
                                lit -> Mint
                                else -> lerp(Color(0xFF241A3A), Color(0xFF4A3A75), dip)
                            }
                        )
                        .border(1.dp, if (locked) dim(Color(0xFF0E0820)) else Color(0xFF0E0820), shape)
                        .pointerInput(m, locked) {
                            // fires on touch-down (no tap/drag disambiguation delay); left
                            // unconsumed so a drag can still scroll the keyboard, which cancels
                            // the gesture and releases the note
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                if (locked) {
                                    waitForUpOrCancellation()?.let { onLockedPress() }
                                    return@awaitEachGesture
                                }
                                pressed = true
                                onPress(m)
                                waitForUpOrCancellation()
                                pressed = false
                                onRelease()
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun WhiteKey(
    m: Int, w: androidx.compose.ui.unit.Dp, lit: Boolean, solfege: Boolean,
    locked: Boolean, onLockedPress: () -> Unit,
    onPress: (Int) -> Unit, onRelease: () -> Unit,
) {
    var pressed by remember(m) { mutableStateOf(false) }
    val dip by animateFloatAsState(if (pressed) 1f else 0f, keyPressSpring, label = "whiteDip")
    val shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
    Box(
        Modifier
            .width(w).height(150.dp)
            .graphicsLayer {
                scaleY = 1f - 0.03f * dip
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
            .clip(shape)
            .background(
                when {
                    locked -> dim(Color(0xFFF3EEFF))
                    lit -> Mint
                    else -> lerp(Color(0xFFF3EEFF), Color(0xFFC8BCEA), dip)
                }
            )
            .border(1.dp, if (locked) dim(Color(0xFFB9ADDE)) else Color(0xFFB9ADDE), shape)
            .pointerInput(m, locked) {
                // fires on touch-down (no tap/drag disambiguation delay); left unconsumed so a
                // drag can still scroll the keyboard, which cancels the gesture and releases
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (locked) {
                        // Only a completed tap opens the unlock card - a drag is still a scroll.
                        waitForUpOrCancellation()?.let { onLockedPress() }
                        return@awaitEachGesture
                    }
                    pressed = true
                    onPress(m)
                    waitForUpOrCancellation()
                    pressed = false
                    onRelease()
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (m == Notes.MIDDLE_C) {
            Box(Modifier.padding(top = 8.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(Honey)
                .align(Alignment.TopCenter))
        }
        Text(
            label(m, solfege),
            color = if (locked) dim(Color(0xFF6B5FA0)) else Color(0xFF6B5FA0),
            fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

/**
 * Where you are in the full C2-C6 keyboard: every key as a tick, the visible slice as a bright
 * window, plus markers for the note you're playing (Honey) and the note you're singing (Mint).
 */
@Composable
private fun KeyboardOverview(
    scroll: ScrollState,
    contentWidth: androidx.compose.ui.unit.Dp,
    detectedMidi: Int?,
    latched: Int?,
) {
    val span = (Notes.HIGH - Notes.LOW).toFloat()
    val contentPx = with(LocalDensity.current) { contentWidth.toPx() }
    Column {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth().height(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2C2250))
                .border(1.dp, Line, RoundedCornerShape(8.dp))
        ) {
            val fullW = maxWidth
            // viewport = content - maxScroll, so the window is that share of the strip
            val visibleFrac =
                if (scroll.maxValue > 0 && contentPx > 0f) (contentPx - scroll.maxValue) / contentPx else 1f
            val windowW = fullW * visibleFrac.coerceIn(0.08f, 1f)
            val startFrac = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
            Box(
                Modifier
                    .offset(x = (fullW - windowW) * startFrac.coerceIn(0f, 1f))
                    .width(windowW).fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Honey.copy(alpha = 0.18f))
                    .border(1.dp, Honey.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            )
            // one tick per key; octave C's are taller and brighter
            (Notes.LOW..Notes.HIGH).forEach { m ->
                val f = (m - Notes.LOW) / span
                val isC = m % 12 == 0
                Box(
                    Modifier
                        .offset(x = fullW * f)
                        .align(Alignment.CenterStart)
                        .width(1.dp)
                        .height(if (isC) 16.dp else if (Notes.isBlack(m)) 7.dp else 10.dp)
                        .background(if (isC) Muted else Line)
                )
            }
            latched?.let { m ->
                Marker(fullW * ((m - Notes.LOW) / span), Honey)
            }
            detectedMidi?.let { m ->
                if (m in Notes.LOW..Notes.HIGH) Marker(fullW * ((m - Notes.LOW) / span), Mint)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Notes.name(Notes.LOW), color = Muted, fontSize = 10.sp)
            Text("full keyboard – drag the keys to move", color = Muted, fontSize = 10.sp)
            Text(Notes.name(Notes.HIGH), color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BoxScope.Marker(x: androidx.compose.ui.unit.Dp, color: Color) {
    Box(
        Modifier
            .offset(x = x - 3.dp)
            .align(Alignment.Center)
            .size(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
}

/** Metronome: on/off plus a few standard tempos. */
@Composable
private fun MetronomeBar(on: Boolean, bpm: Int, onToggle: () -> Unit, onBpmChange: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Metronome", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("$bpm BPM · accent every 4 beats", color = Muted, fontSize = 12.sp)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Coral else Honey)
                    .clickable { onToggle() }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text(
                    if (on) "Stop" else "Start",
                    color = if (on) Color(0xFF2A0D08) else Color(0xFF241A05),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BPM_CHOICES.forEach { choice ->
                val selected = choice == bpm
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Honey else Color(0xFF2C2250))
                        .border(1.dp, if (selected) Honey else Line, RoundedCornerShape(10.dp))
                        .clickable { onBpmChange(choice) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$choice",
                        color = if (selected) Color(0xFF241A05) else Muted,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * The single "Unlock betterPitch" surface. Shown only in the free tier, in the same card style as
 * [MetronomeBar]. If Cafe Bazaar isn't installed there is nothing to launch, so the CTA says so
 * instead of failing on tap.
 */
@Composable
private fun UnlockPanel(status: BillingStatus, onUnlock: () -> Unit) {
    val bazaarMissing = status == BillingStatus.BazaarMissing
    // Tapping while the billing connection isn't up would do nothing, so the CTA says what it's
    // actually doing: connecting, offering a retry, or ready to subscribe.
    val cta = when {
        bazaarMissing -> "Install Cafe Bazaar to subscribe"
        status == BillingStatus.Connecting -> "Connecting to Cafe Bazaar…"
        status == BillingStatus.Ready -> "Subscribe with Cafe Bazaar"
        else -> "Retry connection"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("Unlock betterPitch", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Free covers Do Re Mi Fa Sol La Si. Subscribe for the full C2–C6 keyboard and live " +
                "mic detection of the note you're singing.",
            color = Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (bazaarMissing) Color(0xFF2C2250) else Honey)
                .border(1.dp, if (bazaarMissing) Line else Honey, RoundedCornerShape(12.dp))
                .clickable(enabled = !bazaarMissing) { onUnlock() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                cta,
                color = if (bazaarMissing) Muted else Color(0xFF241A05),
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
        if (status is BillingStatus.Error) {
            Spacer(Modifier.height(8.dp))
            Text(status.message, color = Coral, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MicPanel(
    detectedMidi: Int?, cents: Int, listening: Boolean,
    loMidi: Int?, hiMidi: Int?, solfege: Boolean, isPremium: Boolean,
    onToggleListen: () -> Unit, onUnlock: () -> Unit,
) {
    val inTune = detectedMidi != null && abs(cents) <= 12
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text("Your voice", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            if (isPremium) "Sing one steady note on \"aah\"."
            else "Live mic detection is part of betterPitch.",
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()) {
            Text(
                detectedMidi?.let { label(it, solfege) } ?: "–",
                color = if (inTune) Mint else TextC, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (detectedMidi != null) (if (cents > 0) "+$cents¢" else "$cents¢") else "",
                color = Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(12.dp))
        TuningBar(detectedMidi, cents, inTune)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("flat ♭", color = Muted, fontSize = 11.sp)
            Text("in tune", color = Muted, fontSize = 11.sp)
            Text("♯ sharp", color = Muted, fontSize = 11.sp)
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RangeBox("Lowest sung", loMidi, solfege, Modifier.weight(1f))
            RangeBox("Highest sung", hiMidi, solfege, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (listening) Coral else Honey)
                .clickable { if (isPremium) onToggleListen() else onUnlock() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (!isPremium) "Unlock to use the mic" else if (listening) "Stop" else "Start listening",
                color = if (listening) Color(0xFF2A0D08) else Color(0xFF241A05),
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TuningBar(detectedMidi: Int?, cents: Int, inTune: Boolean) {
    val fraction = if (detectedMidi == null) 0.5f
    else (0.5f + (cents.coerceIn(-50, 50) / 100f))
    Box(
        Modifier
            .fillMaxWidth().height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF2C2250))
            .border(1.dp, Line, RoundedCornerShape(7.dp))
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dotColor = when {
                detectedMidi == null -> Muted
                inTune -> Mint
                abs(cents) > 30 -> Coral
                else -> Honey
            }
            Box(
                Modifier
                    .offset(x = maxWidth * fraction - 8.dp)
                    .align(Alignment.CenterStart)
                    .size(16.dp).clip(RoundedCornerShape(8.dp)).background(dotColor)
            )
        }
    }
}

@Composable
private fun RangeBox(label: String, midi: Int?, solfege: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2C2250))
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        // `label` is shadowed by the String param here, so spell the choice out
        Text(midi?.let { if (solfege) Notes.solfege(it) else Notes.name(it) } ?: "–", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}

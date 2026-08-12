package com.barnamechi.vocaltrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barnamechi.vocaltrainer.music.Notes
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

@Composable
fun VocalTrainerScreen(
    detectedMidi: Int?,
    cents: Int,
    listening: Boolean,
    loMidi: Int?,
    hiMidi: Int?,
    onKeyDown: (Int, Boolean) -> Unit,
    onKeyUp: (Boolean) -> Unit,
    onToggleListen: () -> Unit,
) {
    var solfege by remember { mutableStateOf(false) }
    var sustain by remember { mutableStateOf(false) }
    var latched by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text("VOCAL TRAINER", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
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
            onPress = { m ->
                if (sustain) {
                    if (latched == m) { onKeyUp(true); latched = null }
                    else { onKeyDown(m, true); latched = m }
                } else onKeyDown(m, false) // struck: rings and fades on its own
            },
            onRelease = { if (!sustain) onKeyUp(false) } // damper
        )

        Spacer(Modifier.height(22.dp))
        MicPanel(detectedMidi, cents, listening, loMidi, hiMidi, solfege, onToggleListen)
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
    onPress: (Int) -> Unit,
    onRelease: () -> Unit,
) {
    val whiteW = 44.dp
    val blackW = 28.dp
    val whites = (Notes.LOW..Notes.HIGH).filter { !Notes.isBlack(it) }
    val totalW = whiteW * whites.size

    val scroll = rememberScrollState()
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
                    WhiteKey(m, whiteW, lit, solfege, onPress, onRelease)
                }
            }
            // black keys overlaid
            (Notes.LOW..Notes.HIGH).filter { Notes.isBlack(it) }.forEach { m ->
                val whitesBefore = whites.count { it < m }
                val x = whiteW * whitesBefore - blackW / 2
                val lit = detectedMidi == m || latched == m
                Box(
                    Modifier
                        .offset(x = x)
                        .width(blackW).height(94.dp)
                        .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
                        .background(if (lit) Mint else Color(0xFF241A3A))
                        .border(1.dp, Color(0xFF0E0820), RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
                        .pointerInput(m) {
                            detectTapGestures(onPress = {
                                onPress(m); tryAwaitRelease(); onRelease()
                            })
                        }
                )
            }
        }
    }
}

@Composable
private fun WhiteKey(
    m: Int, w: androidx.compose.ui.unit.Dp, lit: Boolean, solfege: Boolean,
    onPress: (Int) -> Unit, onRelease: () -> Unit,
) {
    Box(
        Modifier
            .width(w).height(150.dp)
            .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .background(if (lit) Mint else Color(0xFFF3EEFF))
            .border(1.dp, Color(0xFFB9ADDE), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .pointerInput(m) {
                detectTapGestures(onPress = { onPress(m); tryAwaitRelease(); onRelease() })
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (m == Notes.MIDDLE_C) {
            Box(Modifier.padding(top = 8.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(Honey)
                .align(Alignment.TopCenter))
        }
        Text(
            label(m, solfege),
            color = Color(0xFF6B5FA0), fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

@Composable
private fun MicPanel(
    detectedMidi: Int?, cents: Int, listening: Boolean,
    loMidi: Int?, hiMidi: Int?, solfege: Boolean, onToggleListen: () -> Unit,
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
        Text("Sing one steady note on \"aah\".", color = Muted, fontSize = 13.sp)
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
                .clickable { onToggleListen() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (listening) "Stop" else "Start listening",
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

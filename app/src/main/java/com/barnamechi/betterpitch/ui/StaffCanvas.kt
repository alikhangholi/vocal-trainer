package com.barnamechi.betterpitch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.barnamechi.betterpitch.music.Staff
import kotlin.math.ceil
import kotlin.math.floor

// Per-note verdict. Bytes rather than an enum array so the round costs one allocation.
internal const val R_PENDING: Byte = 0
internal const val R_CORRECT: Byte = 1
internal const val R_WRONG: Byte = 2
internal const val R_MISSED: Byte = 3

/** How many note-gaps of lead time are visible left of the judgement line. */
internal const val NOTES_VISIBLE = 3.5f

/**
 * The scrolling treble staff.
 *
 * Everything is laid out from [nowBeat], which is read **inside the draw lambda only**: a snapshot
 * read there invalidates draw and nothing else, so this repaints at 60 fps without a single
 * recomposition. Reading it in the composable body instead would recompose the whole page every
 * frame - don't.
 *
 * [notes], [results] and [judgedAt] are plain arrays, mutated in place by the game. They are picked
 * up on the next frame because [nowBeat] already invalidates draw every frame.
 */
@Composable
internal fun StaffCanvas(
    notes: IntArray,
    results: ByteArray,
    judgedAt: FloatArray,
    beatsPerNote: Int,
    graceBeats: Float,
    nowBeat: FloatState,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val now = nowBeat.floatValue // the one per-frame state read
        val gap = size.height / 9f      // one staff line gap; 5 lines + ledgers + stems all fit
        val centerY = size.height / 2f
        fun y(step: Int) = stepY(step, centerY, gap)

        val clefW = gap * 3.2f
        val judgeX = size.width * 0.78f
        val entryX = clefW + gap
        val leadBeats = NOTES_VISIBLE * beatsPerNote
        // px-per-beat is fixed, so a higher BPM simply eats beats faster: the tempo sync is free.
        val ppb = ((judgeX - entryX) / leadBeats).coerceAtLeast(1f)
        val headRx = gap * 0.62f

        // --- staff lines -------------------------------------------------------------------
        val lineW = 1.dp.toPx()
        for (s in Staff.BOTTOM_LINE_STEP..Staff.TOP_LINE_STEP step 2) {
            drawLine(Line, Offset(0f, y(s)), Offset(size.width, y(s)), strokeWidth = lineW)
        }

        // --- judgement line + grace band ---------------------------------------------------
        val bandW = graceBeats * ppb
        if (bandW > 0f) {
            drawRect(
                Honey.copy(alpha = 0.09f),
                topLeft = Offset(judgeX, y(Staff.TOP_LINE_STEP) - gap),
                size = Size(bandW, gap * 6f)
            )
        }
        // The pulse is the free sync self-check: it must blink with the click, not before it.
        val pulse = if (running) 1f - (now - floor(now)) else 0.35f
        drawLine(
            Honey.copy(alpha = 0.45f + 0.55f * pulse),
            Offset(judgeX, y(Staff.TOP_LINE_STEP) - gap * 1.4f),
            Offset(judgeX, y(Staff.BOTTOM_LINE_STEP) + gap * 1.4f),
            strokeWidth = 2.dp.toPx()
        )

        drawClef(measurer, centerY, gap)

        // --- notes -------------------------------------------------------------------------
        // Beats are uniform (beat(i) = i * beatsPerNote), so the visible window is arithmetic:
        // no search, no allocation, O(notes on screen).
        val bpn = beatsPerNote.toFloat()
        val margin = gap * 3f
        val from = ceil((now - (size.width - judgeX + margin) / ppb) / bpn).toInt().coerceAtLeast(0)
        val to = floor((now + (judgeX + margin) / ppb) / bpn).toInt().coerceAtMost(notes.size - 1)

        for (i in from..to) {
            val noteBeat = i * bpn
            val cx = judgeX - (noteBeat - now) * ppb
            val step = Staff.step(notes[i])
            val cy = y(step)

            val verdict = results[i]
            val color = when (verdict) {
                R_CORRECT -> Mint
                R_WRONG, R_MISSED -> Coral
                // Pending notes brighten as they approach, so the eye is drawn to what's next.
                else -> lerp(Muted, TextC, (1f - (noteBeat - now) / leadBeats).coerceIn(0f, 1f))
            }
            // A short pop on judgement, then the colour just rides off screen as history.
            val flash = if (verdict == R_PENDING) 0f
            else (1f - (now - judgedAt[i]) / 0.4f).coerceIn(0f, 1f)
            val scale = 1f + 0.35f * flash

            ledgerLines(step, cx, centerY, gap, lineW)
            drawNote(cx, cy, headRx * scale, gap, step, beatsPerNote, color)
        }
    }
}

private fun DrawScope.drawNote(
    cx: Float, cy: Float, rx: Float, gap: Float, noteStep: Int, beatsPerNote: Int, color: Color,
) {
    val ry = gap * 0.46f
    val hollow = beatsPerNote >= 2          // half and whole notes are open
    val stemmed = beatsPerNote <= 2         // whole notes have no stem
    rotate(-20f, pivot = Offset(cx, cy)) {
        val topLeft = Offset(cx - rx, cy - ry)
        val s = Size(rx * 2, ry * 2)
        if (hollow) drawOval(color, topLeft, s, style = Stroke(width = gap * 0.22f))
        else drawOval(color, topLeft, s)
    }
    if (stemmed) {
        val up = noteStep < Staff.MIDDLE_LINE_STEP // below the middle line: stem up, on the right
        val sx = if (up) cx + rx * 0.92f else cx - rx * 0.92f
        val sy = if (up) cy - gap * 3.4f else cy + gap * 3.4f
        drawLine(color, Offset(sx, cy), Offset(sx, sy), strokeWidth = gap * 0.15f)
    }
}

/** Half a line gap per step, measured out from the middle line (B4). */
private fun stepY(step: Int, centerY: Float, gap: Float): Float =
    centerY - (step - Staff.MIDDLE_LINE_STEP) * (gap / 2f)

private fun DrawScope.ledgerLines(
    noteStep: Int, cx: Float, centerY: Float, gap: Float, lineW: Float,
) {
    val half = gap * 0.95f
    val below = Staff.BOTTOM_LINE_STEP - 2
    val above = Staff.TOP_LINE_STEP + 2
    if (noteStep <= below) {
        for (ls in below downTo noteStep step 2) {
            val ly = stepY(ls, centerY, gap)
            drawLine(Line, Offset(cx - half, ly), Offset(cx + half, ly), strokeWidth = lineW)
        }
    }
    if (noteStep >= above) {
        for (ls in above..noteStep step 2) {
            val ly = stepY(ls, centerY, gap)
            drawLine(Line, Offset(cx - half, ly), Offset(cx + half, ly), strokeWidth = lineW)
        }
    }
}

/**
 * A stylised G clef: a bar spanning the staff, a dot sitting exactly on the G4 line - which is
 * what a G clef actually means - and the letter G.
 *
 * Deliberately not the 𝄞 glyph (U+1D11E): it needs the Noto Music font, which most Android devices
 * don't ship, and it would render as a tofu box with no way to detect that at runtime. Bundling a
 * music font for one glyph isn't worth it. Swap this function for a real Path any time.
 */
private fun DrawScope.drawClef(measurer: TextMeasurer, centerY: Float, gap: Float) {
    val x = gap * 1.1f
    val gy = stepY(Staff.G_LINE_STEP, centerY, gap)
    drawLine(
        Honey,
        Offset(x, stepY(Staff.TOP_LINE_STEP, centerY, gap) - gap * 0.6f),
        Offset(x, stepY(Staff.BOTTOM_LINE_STEP, centerY, gap) + gap * 0.6f),
        strokeWidth = gap * 0.28f
    )
    drawCircle(Honey, radius = gap * 0.34f, center = Offset(x, gy))
    val laid = measurer.measure(
        "G",
        style = TextStyle(
            color = Honey,
            fontSize = (gap * 0.62f).toSp(),
            fontWeight = FontWeight.ExtraBold
        )
    )
    drawText(laid, topLeft = Offset(x + gap * 0.5f, gy - laid.size.height / 2f))
}

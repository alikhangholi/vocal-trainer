package com.barnamechi.betterpitch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.barnamechi.betterpitch.music.NoteValue
import com.barnamechi.betterpitch.music.RhythmLayout
import kotlin.math.ceil
import kotlin.math.floor

// Per-event verdict, same byte-array convention as StaffCanvas's R_* constants.
internal const val RR_PENDING: Byte = 0
internal const val RR_PERFECT: Byte = 1
internal const val RR_GOOD: Byte = 2
internal const val RR_MISSED: Byte = 3

/** How many beats of lead time are visible left of the judgement line. */
internal const val RHYTHM_LEAD_BEATS = 4f

/**
 * The scrolling rhythm staff: real notation - five lines, a neutral clef, a time signature,
 * barlines, and noteheads with stems, beams, flags, dots, ties and rests - moving right-to-left
 * toward the judgement line.
 *
 * Everything is drawn with DrawScope primitives and Paths. That is not stubbornness: the 𝄞 glyph
 * decision in StaffCanvas applies to the whole SMuFL set, since Android devices do not ship a
 * music font and a missing glyph renders as a tofu box with no way to detect it at runtime.
 *
 * All noteheads sit on the middle line, so every stem is the same length and **every beam is
 * horizontal** - which removes the slant and stem-length optimisation that is most of the work in
 * a real beaming engine.
 *
 * [nowBeat] is read inside the draw lambda only, same rationale as StaffCanvas: a snapshot read
 * there invalidates draw and nothing else, so this repaints every frame without recomposing the
 * page. [layout] and the three arrays are plain and mutated in place by the round; they are picked
 * up on the next frame because [nowBeat] already invalidates draw every frame.
 */
@Composable
internal fun RhythmTrackCanvas(
    layout: RhythmLayout,
    results: ByteArray,
    judgedAt: FloatArray,
    deltaBeats: FloatArray,
    goodWindowBeats: Float,
    nowBeat: FloatState,
    running: Boolean,
    countInBeats: Float,
    modifier: Modifier = Modifier,
) {
    // The default cache holds 8 entries and this page needs more than that - two time-signature
    // numerals, the triplet "3" and a count-in numeral per beat. A miss allocates a layout inside
    // the draw lambda, every frame, which is exactly the GC jitter a timing game cannot afford.
    val measurer = rememberTextMeasurer(cacheSize = 24)
    Canvas(modifier) {
        val now = nowBeat.floatValue // the one per-frame state read
        val meter = layout.meter
        // 10 gaps is the least that fits 5 staff lines plus stems, two beam levels, a triplet
        // bracket and the count-in row above, and the tie arcs and timing scatter below.
        val gap = (size.height / 10f).coerceAtMost(MAX_STAFF_GAP.toPx())
        val centerY = size.height / 2f
        val lineW = 1.dp.toPx()
        val headRx = gap * 0.62f
        val stemTop = centerY - gap * 3.4f

        val tsStyle = TextStyle(
            color = Honey, fontSize = (gap * 1.9f).toSp(), fontWeight = FontWeight.ExtraBold
        )
        val tsTop = measurer.measure(meter.top.toString(), tsStyle)
        val tsBottom = measurer.measure(meter.bottom.toString(), tsStyle)
        val clefX = gap * 1.0f
        val clefW = gap * 1.5f
        val tsX = clefX + clefW + gap * 0.6f
        val tsW = maxOf(tsTop.size.width, tsBottom.size.width).toFloat()
        val headerW = tsX + tsW + gap * 0.6f

        val judgeX = size.width * 0.78f
        val entryX = headerW + gap
        // px-per-beat is fixed, so a higher BPM simply eats beats faster: the tempo sync is free.
        val ppb = ((judgeX - entryX) / RHYTHM_LEAD_BEATS).coerceAtLeast(1f)

        fun xOf(beat: Float) = judgeX - (beat - now) * ppb

        // --- staff -------------------------------------------------------------------------
        for (k in -2..2) {
            val y = centerY + k * gap
            drawLine(Line, Offset(0f, y), Offset(size.width, y), strokeWidth = lineW)
        }

        // --- barlines ----------------------------------------------------------------------
        // Bars are uniform, so the visible ones are closed-form arithmetic - no array, no search.
        val barBeats = meter.beatsPerBar.toFloat()
        val leftBeat = now - (size.width - judgeX) / ppb
        val rightBeat = now + judgeX / ppb
        var bar = floor(leftBeat / barBeats).toInt().coerceAtLeast(0)
        val lastBar = ceil(rightBeat / barBeats).toInt()
        while (bar <= lastBar) {
            val beat = bar * barBeats
            if (beat > layout.totalBeats + 0.01f) break
            val x = xOf(beat)
            if (x >= -gap && x <= size.width + gap) {
                drawLine(
                    Line, Offset(x, centerY - gap * 2f), Offset(x, centerY + gap * 2f),
                    strokeWidth = if (beat <= 0.01f) lineW * 2f else lineW
                )
            }
            bar++
        }
        // Closing double barline.
        val endX = xOf(layout.totalBeats)
        if (endX >= -gap && endX <= size.width + gap) {
            drawLine(
                Line, Offset(endX - gap * 0.28f, centerY - gap * 2f),
                Offset(endX - gap * 0.28f, centerY + gap * 2f), strokeWidth = lineW
            )
            drawLine(
                Line, Offset(endX, centerY - gap * 2f), Offset(endX, centerY + gap * 2f),
                strokeWidth = lineW * 3f
            )
        }

        // --- judgement line ----------------------------------------------------------------
        // The pulse is the free sync self-check: it must blink with the click, not before it.
        val pulse = if (running) 1f - (now - floor(now)) else 0.35f
        drawLine(
            Honey.copy(alpha = 0.45f + 0.55f * pulse),
            Offset(judgeX, centerY - gap * 4.6f),
            Offset(judgeX, centerY + gap * 3.6f),
            strokeWidth = 2.dp.toPx()
        )

        // --- count-in ----------------------------------------------------------------------
        // One pip per click of the count-in bar, above the staff so it can never be mistaken for
        // a note. Each fills as its click passes the line.
        if (countInBeats > 0f && now < 0.5f) {
            for (k in -countInBeats.toInt()..-1) {
                val x = xOf(k.toFloat())
                if (x < -gap || x > size.width + gap) continue
                val c = Offset(x, centerY - gap * 4.4f)
                if (now >= k) drawCircle(Honey, radius = gap * 0.3f, center = c)
                else drawCircle(
                    Muted, radius = gap * 0.3f, center = c, style = Stroke(width = gap * 0.14f)
                )
            }
        }

        if (layout.size == 0) return@Canvas

        // --- events ------------------------------------------------------------------------
        val marginBeats = (size.width - judgeX) / ppb + 1f
        var from = lowerBound(layout.onsetBeat, now - marginBeats).coerceAtLeast(0)
        var to = lowerBound(layout.onsetBeat, now + (judgeX / ppb) + marginBeats)
            .coerceAtMost(layout.size)
        // Widen to whole beam groups: a group straddling the edge must still draw as one beam.
        if (from < layout.size) from = layout.beamFirst[from]
        if (to > from) to = layout.beamLast[to - 1] + 1

        for (i in from until to) {
            val ev = layout.events[i]
            val cx = xOf(layout.onsetBeat[i])
            // The far side of a tie is never judged in its own right, so it borrows the verdict of
            // the note it is tied from - one sound, one colour.
            val src = layout.verdictSource[i]
            val verdict = results[src]
            val color = when (verdict) {
                RR_PERFECT -> Mint
                RR_GOOD -> Honey
                RR_MISSED -> Coral
                // Pending events brighten as they approach, so the eye is drawn to what's next.
                else -> lerp(
                    Muted, TextC,
                    (1f - (layout.onsetBeat[i] - now) / RHYTHM_LEAD_BEATS).coerceIn(0f, 1f)
                )
            }
            // A short pop on judgement, then the colour just rides off screen as history.
            val flash = if (verdict == RR_PENDING) 0f
            else (1f - (now - judgedAt[src]) / 0.4f).coerceIn(0f, 1f)

            if (ev.isRest) {
                drawRest(ev.value, cx, centerY, gap, color)
                continue
            }

            drawHead(cx, centerY, headRx * (1f + 0.25f * flash), gap, ev.value.hollow, color)
            for (d in 0 until ev.dots) {
                drawCircle(
                    color, radius = gap * 0.15f,
                    center = Offset(cx + headRx * 1.9f + d * gap * 0.42f, centerY - gap * 0.5f)
                )
            }

            if (ev.value.stemmed) {
                val sx = cx + headRx * 0.92f
                drawLine(color, Offset(sx, centerY), Offset(sx, stemTop), strokeWidth = gap * 0.15f)

                if (layout.isBeamGroupHead(i)) {
                    drawBeams(layout, i, judgeX, now, ppb, headRx, stemTop, gap, color)
                } else if (layout.isUnbeamed(i) && ev.value.flags > 0) {
                    // Not in a group: it keeps its own flags.
                    for (f in 0 until ev.value.flags) {
                        drawFlag(sx, stemTop + f * gap * 0.68f, gap, color)
                    }
                }
            }

            // Tuplet bracket, drawn once per tuplet, above the beam.
            if (layout.tupletFirst[i] == i && layout.tupletLast[i] > i) {
                val last = layout.tupletLast[i]
                drawTupletBracket(
                    measurer, cx + headRx * 0.92f, xOf(layout.onsetBeat[last]) + headRx * 0.92f,
                    stemTop - gap * 0.85f, gap
                )
            }

            // Tie: an arc under the two heads, since every stem here points up.
            if (ev.tiedToNext && i + 1 < layout.size) {
                drawTie(cx, xOf(layout.onsetBeat[i + 1]), centerY, gap, color)
            }

            // Where the tap actually landed, at true scale inside the level's own hit window, so
            // the position reads directly as "early" or "late" and by how much.
            if (src == i && verdict != RR_PENDING && verdict != RR_MISSED) {
                val wy = centerY + gap * 3.0f
                val halfW = goodWindowBeats * ppb
                drawLine(
                    Line, Offset(cx - halfW, wy), Offset(cx + halfW, wy), strokeWidth = lineW
                )
                val tx = cx + deltaBeats[i] * ppb
                drawLine(
                    color, Offset(tx, wy - gap * 0.28f), Offset(tx, wy + gap * 0.28f),
                    strokeWidth = gap * 0.14f
                )
            }
        }

        // --- fixed header ------------------------------------------------------------------
        // Painted last: notes scroll all the way to x = 0, and with real notation the sight of one
        // sliding through the clef reads as a bug. Cover the zone, restore the staff lines across
        // it, then draw the clef and time signature on top.
        drawRect(Surface, topLeft = Offset(0f, 0f), size = Size(headerW, size.height))
        for (k in -2..2) {
            val y = centerY + k * gap
            drawLine(Line, Offset(0f, y), Offset(headerW, y), strokeWidth = lineW)
        }
        drawNeutralClef(clefX, clefW, centerY, gap)
        drawText(tsTop, topLeft = Offset(tsX, centerY - gap - tsTop.size.height / 2f))
        drawText(tsBottom, topLeft = Offset(tsX, centerY + gap - tsBottom.size.height / 2f))
    }
}

/**
 * The neutral (percussion) clef: two thick vertical bars spanning the middle of the staff. It is
 * the correct clef for music with rhythm but no pitch, and unlike the G clef it is genuinely two
 * rectangles - no Path needed.
 */
private fun DrawScope.drawNeutralClef(x: Float, w: Float, centerY: Float, gap: Float) {
    val barW = w * 0.34f
    drawRect(Honey, topLeft = Offset(x, centerY - gap), size = Size(barW, gap * 2f))
    drawRect(Honey, topLeft = Offset(x + w - barW, centerY - gap), size = Size(barW, gap * 2f))
}

/** A notehead on the middle line: filled for a quarter or shorter, open for a half or whole. */
private fun DrawScope.drawHead(
    cx: Float, cy: Float, rx: Float, gap: Float, hollow: Boolean, color: Color,
) {
    val ry = gap * 0.46f
    rotate(-20f, pivot = Offset(cx, cy)) {
        val topLeft = Offset(cx - rx, cy - ry)
        val s = Size(rx * 2, ry * 2)
        if (hollow) drawOval(color, topLeft, s, style = Stroke(width = gap * 0.22f))
        else drawOval(color, topLeft, s)
    }
}

/** One flag, hanging right of the stem from [y]. */
private fun DrawScope.drawFlag(sx: Float, y: Float, gap: Float, color: Color) {
    val p = Path().apply {
        moveTo(sx, y)
        quadraticBezierTo(sx + gap * 1.25f, y + gap * 0.45f, sx + gap * 0.55f, y + gap * 1.6f)
        quadraticBezierTo(sx + gap * 0.85f, y + gap * 0.5f, sx, y + gap * 0.75f)
        close()
    }
    drawPath(p, color)
}

/**
 * The beams for one group. The primary beam spans the whole group; each further level spans only
 * the runs of notes that are short enough to carry it, which is what draws an eighth followed by
 * two sixteenths correctly. A run of one gets a fractional stub, pointing back toward the group
 * unless it is the group's first note.
 */
private fun DrawScope.drawBeams(
    layout: RhythmLayout,
    head: Int,
    judgeX: Float,
    now: Float,
    ppb: Float,
    headRx: Float,
    stemTop: Float,
    gap: Float,
    color: Color,
) {
    val last = layout.beamLast[head]
    fun stemX(i: Int) = judgeX - (layout.onsetBeat[i] - now) * ppb + headRx * 0.92f
    val beamH = gap * 0.45f
    val stubW = gap * 0.9f

    var maxFlags = 0
    for (i in head..last) if (layout.flagsOf(i) > maxFlags) maxFlags = layout.flagsOf(i)

    for (level in 1..maxFlags) {
        val y = stemTop + (level - 1) * (beamH + gap * 0.23f)
        var i = head
        while (i <= last) {
            if (layout.flagsOf(i) < level) { i++; continue }
            var j = i
            while (j + 1 <= last && layout.flagsOf(j + 1) >= level) j++
            if (j > i) {
                drawRect(
                    color, topLeft = Offset(stemX(i), y), size = Size(stemX(j) - stemX(i), beamH)
                )
            } else {
                val x = stemX(i)
                val from = if (i == head) x else x - stubW
                drawRect(color, topLeft = Offset(from, y), size = Size(stubW, beamH))
            }
            i = j + 1
        }
    }
}

/**
 * The "3" bracket over a triplet: a hooked line with a hole for the numeral.
 *
 * Drawn in [Muted] rather than the note's verdict colour, and not only for looks. The measurer
 * caches by style, and a pending note's colour is a `lerp` that changes every single frame - keying
 * the layout on it would miss the cache and allocate a TextLayoutResult per frame, inside the draw
 * lambda, in a timing game. A bracket is structural furniture like a barline, so a fixed colour is
 * the right answer anyway.
 */
private fun DrawScope.drawTupletBracket(
    measurer: TextMeasurer, x0: Float, x1: Float, y: Float, gap: Float,
) {
    val color = Muted
    val w = gap * 0.11f
    val laid = measurer.measure(
        "3",
        TextStyle(color = color, fontSize = (gap * 1.1f).toSp(), fontWeight = FontWeight.Bold)
    )
    val mid = (x0 + x1) / 2f
    val half = laid.size.width / 2f + gap * 0.2f
    drawLine(color, Offset(x0, y), Offset(mid - half, y), strokeWidth = w)
    drawLine(color, Offset(mid + half, y), Offset(x1, y), strokeWidth = w)
    drawLine(color, Offset(x0, y), Offset(x0, y + gap * 0.35f), strokeWidth = w)
    drawLine(color, Offset(x1, y), Offset(x1, y + gap * 0.35f), strokeWidth = w)
    drawText(laid, topLeft = Offset(mid - laid.size.width / 2f, y - laid.size.height / 2f))
}

/** A tie arc, below the heads because every stem on this staff points up. */
private fun DrawScope.drawTie(x0: Float, x1: Float, centerY: Float, gap: Float, color: Color) {
    val y = centerY + gap * 0.8f
    val p = Path().apply {
        moveTo(x0, y)
        quadraticBezierTo((x0 + x1) / 2f, y + gap * 1.5f, x1, y)
    }
    drawPath(p, color, style = Stroke(width = gap * 0.13f))
}

/**
 * Rest glyphs. Whole and half rests are a block on a staff line; eighth and sixteenth rests are a
 * slanted stroke hung with one blob per flag; the quarter rest is the awkward one and is drawn as
 * the four-segment zig-zag it really is.
 */
private fun DrawScope.drawRest(
    value: NoteValue, cx: Float, centerY: Float, gap: Float, color: Color,
) {
    when (value) {
        // A whole rest hangs under the 4th line, a half rest sits on the 3rd.
        NoteValue.WHOLE -> drawRect(
            color, topLeft = Offset(cx - gap * 0.55f, centerY - gap),
            size = Size(gap * 1.1f, gap * 0.42f)
        )
        NoteValue.HALF -> drawRect(
            color, topLeft = Offset(cx - gap * 0.55f, centerY - gap * 0.42f),
            size = Size(gap * 1.1f, gap * 0.42f)
        )
        NoteValue.QUARTER -> {
            val p = Path().apply {
                moveTo(cx - gap * 0.28f, centerY - gap * 1.25f)
                lineTo(cx + gap * 0.30f, centerY - gap * 0.45f)
                lineTo(cx - gap * 0.26f, centerY + gap * 0.15f)
                lineTo(cx + gap * 0.34f, centerY + gap * 1.05f)
            }
            drawPath(p, color, style = Stroke(width = gap * 0.26f))
            // The little hook that closes a quarter rest.
            drawPath(
                Path().apply {
                    moveTo(cx + gap * 0.34f, centerY + gap * 1.05f)
                    quadraticBezierTo(
                        cx - gap * 0.30f, centerY + gap * 0.72f,
                        cx + gap * 0.10f, centerY + gap * 1.35f
                    )
                },
                color, style = Stroke(width = gap * 0.20f)
            )
        }
        // Eighth and sixteenth rests share a stroke; the flag count is the blob count.
        NoteValue.EIGHTH, NoteValue.SIXTEENTH -> {
            val blobs = value.flags
            val top = centerY - gap * (0.30f + 0.55f * (blobs - 1))
            drawLine(
                color, Offset(cx + gap * 0.34f, top),
                Offset(cx - gap * 0.24f, centerY + gap * 1.05f), strokeWidth = gap * 0.16f
            )
            for (b in 0 until blobs) {
                val by = top + b * gap * 0.62f
                drawCircle(color, radius = gap * 0.21f, center = Offset(cx - gap * 0.02f, by))
                drawLine(
                    color, Offset(cx - gap * 0.02f, by), Offset(cx + gap * 0.30f, by - gap * 0.06f),
                    strokeWidth = gap * 0.13f
                )
            }
        }
    }
}

/** First index i such that arr[i] >= target. arr must be sorted ascending (onsetBeat always is). */
private fun lowerBound(arr: FloatArray, target: Float): Int {
    var lo = 0
    var hi = arr.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (arr[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
}

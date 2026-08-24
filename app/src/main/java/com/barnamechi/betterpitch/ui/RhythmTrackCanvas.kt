package com.barnamechi.betterpitch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.barnamechi.betterpitch.music.RhythmEvent
import kotlin.math.floor

// Per-event verdict, same byte-array convention as StaffCanvas's R_* constants.
internal const val RR_PENDING: Byte = 0
internal const val RR_PERFECT: Byte = 1
internal const val RR_GOOD: Byte = 2
internal const val RR_MISSED: Byte = 3

/** How many beats of lead time are visible left of the judgement line. */
internal const val RHYTHM_LEAD_BEATS = 4f

/**
 * The scrolling rhythm track: a single horizontal lane of duration blocks, mirroring StaffCanvas's
 * scroll-toward-judgement-line mechanic but with no pitch axis. Durations are non-uniform (unlike
 * the sight-reading game's fixed beatsPerNote), so the visible range is found by binary-searching
 * the precomputed [onsetBeat] array rather than closed-form arithmetic.
 *
 * [nowBeat] is read inside the draw lambda only, same rationale as StaffCanvas: a snapshot read
 * there invalidates draw and nothing else, so this repaints every frame without recomposing the
 * page.
 */
@Composable
internal fun RhythmTrackCanvas(
    events: List<RhythmEvent>,
    onsetBeat: FloatArray,
    results: ByteArray,
    judgedAt: FloatArray,
    nowBeat: FloatState,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val now = nowBeat.floatValue
        val gap = size.height / 6f
        val centerY = size.height / 2f
        val laneHalfHeight = gap * 1.3f

        val judgeX = size.width * 0.78f
        val entryX = gap
        val ppb = ((judgeX - entryX) / RHYTHM_LEAD_BEATS).coerceAtLeast(1f)

        // lane baseline
        drawLine(Line, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 1.dp.toPx())

        // judgement line + pulse; blinks with the beat, same free sync self-check as StaffCanvas.
        val pulse = if (running) 1f - (now - floor(now)) else 0.35f
        drawLine(
            Honey.copy(alpha = 0.45f + 0.55f * pulse),
            Offset(judgeX, centerY - laneHalfHeight * 1.6f),
            Offset(judgeX, centerY + laneHalfHeight * 1.6f),
            strokeWidth = 2.dp.toPx()
        )

        if (onsetBeat.isEmpty()) return@Canvas

        val marginBeats = (size.width - judgeX) / ppb + 1f
        val from = lowerBound(onsetBeat, now - marginBeats).coerceAtLeast(0)
        val to = lowerBound(onsetBeat, now + (judgeX / ppb) + marginBeats).coerceAtMost(events.size)

        for (i in from until to) {
            val ev = events[i]
            if (ev.isRest) continue // rests draw as an empty gap; nothing to paint

            val startX = judgeX - (onsetBeat[i] - now) * ppb
            val w = (ev.beats * ppb - gap * 0.12f).coerceAtLeast(4f)

            val verdict = results[i]
            val color = when (verdict) {
                RR_PERFECT -> Mint
                RR_GOOD -> Honey
                RR_MISSED -> Coral
                // Pending events brighten as they approach, same "eye drawn to what's next" cue.
                else -> lerp(Muted, TextC, (1f - (onsetBeat[i] - now) / RHYTHM_LEAD_BEATS).coerceIn(0f, 1f))
            }
            val flash = if (verdict == RR_PENDING) 0f
            else (1f - (now - judgedAt[i]) / 0.4f).coerceIn(0f, 1f)
            val blockH = laneHalfHeight * 2f * (1f + 0.12f * flash)

            drawRoundRect(
                color,
                topLeft = Offset(startX, centerY - blockH / 2f),
                size = Size(w, blockH),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
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

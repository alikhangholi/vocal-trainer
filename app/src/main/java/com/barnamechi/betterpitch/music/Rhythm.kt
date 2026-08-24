package com.barnamechi.betterpitch.music

import kotlin.random.Random

/**
 * One note or rest, in beats. Durations are plain floats (quarter = 1f) rather than an enum of
 * note-value symbols, because the rhythm game never draws notation - only a scrolling duration
 * track - so there is nothing a symbol would buy over the number itself. A tied note is simply one
 * event with a longer [beats]; there is no separate tie concept to model in a 1-D duration track.
 */
data class RhythmEvent(val beats: Float, val isRest: Boolean = false)

/** A one-bar (by default) phrase. Rounds are built by drawing whole patterns, not individual
 *  durations, so a round reads as a musical phrase rather than atomized random note-lengths. */
data class RhythmPattern(val events: List<RhythmEvent>, val beatsPerBar: Int = 4)

data class RhythmLevel(
    val id: Int,
    val title: String,
    val description: String,
    val bpmRange: IntRange,
    val patterns: List<RhythmPattern>,
    /** Hit-window half-widths, in beats (not ms), so they scale automatically with BPM. */
    val perfectWindowBeats: Float,
    val goodWindowBeats: Float,
    /** Fraction of tap-eligible events (Perfect or Good) needed to pass. */
    val passAccuracy: Float,
    /** Extra gate on levels 7-9: fraction of hits that must land in the tighter Perfect window. */
    val passPerfectRatio: Float = 0f,
)

/**
 * Beginner-to-advanced rhythm curriculum. Sequencing follows standard music-ed practice (quarters
 * -> paired eighths -> rests -> dotted rhythms -> sixteenths -> triplets -> syncopation -> mixed).
 * Hit windows are beat-fractions loosely modelled on rhythm-game timing tiers (commercial games run
 * roughly +-16-30ms at their tightest "Perfect" tier) but scaled much looser for beginners and only
 * tightened toward that range at the top level, since this app teaches timing rather than testing
 * trained reflexes.
 */
object Rhythm {
    private fun q() = RhythmEvent(1f)
    private fun qr() = RhythmEvent(1f, isRest = true)
    private fun h() = RhythmEvent(2f)
    private fun e() = RhythmEvent(0.5f)
    private fun er() = RhythmEvent(0.5f, isRest = true)
    private fun s() = RhythmEvent(0.25f)
    private fun dq() = RhythmEvent(1.5f)
    private fun tie(beats: Float) = RhythmEvent(beats)
    private fun t() = RhythmEvent(1f / 3f) // triplet eighth

    private fun pattern(vararg events: RhythmEvent) = RhythmPattern(events.toList())

    private val LEVEL_1_PATTERNS = listOf(
        pattern(q(), q(), q(), q()),
        pattern(h(), q(), q()),
    )

    private val LEVEL_2_PATTERNS = LEVEL_1_PATTERNS + listOf(
        pattern(qr(), q(), q(), q()),
        pattern(q(), qr(), q(), q()),
        pattern(q(), q(), qr(), q()),
        pattern(q(), q(), q(), qr()),
    )

    private val LEVEL_3_PATTERNS = listOf(
        pattern(q(), q(), q(), q()),
        pattern(e(), e(), q(), q(), q()),
        pattern(q(), e(), e(), q(), q()),
        pattern(q(), q(), e(), e(), q()),
        pattern(q(), q(), q(), e(), e()),
        pattern(e(), e(), e(), e(), q(), q()),
    )

    private val LEVEL_4_PATTERNS = LEVEL_3_PATTERNS + listOf(
        pattern(e(), er(), q(), q(), q()),
        pattern(er(), e(), q(), q(), q()),
        pattern(q(), e(), er(), q(), q()),
        pattern(q(), q(), er(), e(), q()),
        pattern(qr(), e(), e(), q(), q()),
    )

    private val LEVEL_5_PATTERNS = listOf(
        pattern(dq(), e(), q(), q()),
        pattern(q(), dq(), e(), q()),
        pattern(q(), q(), dq(), e()),
        pattern(dq(), e(), dq(), e()),
        pattern(dq(), e(), e(), e(), q()),
    )

    private val LEVEL_6_PATTERNS = listOf(
        pattern(s(), s(), s(), s(), q(), q(), q()),
        pattern(q(), s(), s(), s(), s(), q(), q()),
        pattern(e(), s(), s(), q(), q(), q()),
        pattern(s(), s(), e(), q(), q(), q()),
        pattern(s(), s(), s(), s(), s(), s(), s(), s(), q(), q()),
    )

    private val LEVEL_7_PATTERNS = listOf(
        pattern(t(), t(), t(), q(), q(), q()),
        pattern(q(), t(), t(), t(), q(), q()),
        pattern(t(), t(), t(), t(), t(), t(), q(), q()),
        pattern(q(), q(), t(), t(), t(), q()),
    )

    private val LEVEL_8_PATTERNS = listOf(
        pattern(e(), q(), e(), q(), q()),
        pattern(q(), e(), q(), e(), q()),
        pattern(e(), tie(1.5f), q(), q()),
        pattern(q(), q(), e(), tie(1.5f)),
    )

    private val LEVEL_9_PATTERNS =
        LEVEL_5_PATTERNS + LEVEL_6_PATTERNS + LEVEL_7_PATTERNS + LEVEL_8_PATTERNS

    val LEVELS: List<RhythmLevel> = listOf(
        RhythmLevel(
            1, "Steady quarters", "One tap per beat. Just lock in with the click.",
            40..60, LEVEL_1_PATTERNS,
            perfectWindowBeats = 0.12f, goodWindowBeats = 0.25f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            2, "Quarter rests", "Some beats are silent - don't tap on a rest.",
            40..60, LEVEL_2_PATTERNS,
            perfectWindowBeats = 0.12f, goodWindowBeats = 0.25f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            3, "Paired eighths", "Two even taps per beat, in pairs.",
            50..72, LEVEL_3_PATTERNS,
            perfectWindowBeats = 0.09f, goodWindowBeats = 0.20f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            4, "Eighth rests", "Paired eighths, with one half of the pair silent.",
            50..72, LEVEL_4_PATTERNS,
            perfectWindowBeats = 0.09f, goodWindowBeats = 0.20f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            5, "Dotted quarter + eighth", "A long-short pair: three quarters' worth, then one.",
            60..84, LEVEL_5_PATTERNS,
            perfectWindowBeats = 0.09f, goodWindowBeats = 0.20f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            6, "Sixteenth-note groupings", "Four even taps per beat, alone or mixed with eighths.",
            60..84, LEVEL_6_PATTERNS,
            perfectWindowBeats = 0.06f, goodWindowBeats = 0.15f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            7, "Eighth-note triplets", "Three even taps per beat instead of two.",
            72..96, LEVEL_7_PATTERNS,
            perfectWindowBeats = 0.06f, goodWindowBeats = 0.15f, passAccuracy = 0.85f,
            passPerfectRatio = 0.60f,
        ),
        RhythmLevel(
            8, "Syncopation & ties", "Accents land off the beat - listen for the push and pull.",
            72..96, LEVEL_8_PATTERNS,
            perfectWindowBeats = 0.05f, goodWindowBeats = 0.12f, passAccuracy = 0.85f,
            passPerfectRatio = 0.60f,
        ),
        RhythmLevel(
            9, "Mixed & advanced", "Everything above, drawn at random. The full workout.",
            84..120, LEVEL_9_PATTERNS,
            perfectWindowBeats = 0.05f, goodWindowBeats = 0.12f, passAccuracy = 0.85f,
            passPerfectRatio = 0.65f,
        ),
    )

    fun randomRound(level: RhythmLevel, bars: Int, rng: Random = Random.Default): List<RhythmEvent> =
        (0 until bars).flatMap { level.patterns[rng.nextInt(level.patterns.size)].events }
}

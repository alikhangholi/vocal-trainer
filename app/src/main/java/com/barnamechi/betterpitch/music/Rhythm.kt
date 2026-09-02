package com.barnamechi.betterpitch.music

import kotlin.random.Random

/**
 * The duration domain. Everything notatable divides 1920 exactly - halves, thirds and quarters of
 * a beat all land on an integer - so onsets are counted in ticks and never accumulated as floats.
 *
 * That matters more than it looks. Every question the engraver asks ("are these two notes in the
 * same beat?", "does this note cross the barline?") is an equality test on a boundary, and a 6/8
 * eighth is 1/3 of a beat: added up as floats, three of them make 0.99999997 and land in the wrong
 * beat. Beats stay floats for scrolling and scoring, where a rounding error is invisible; structure
 * stays integers, where it is not.
 */
const val TICKS_PER_WHOLE = 1920

/**
 * A note value as it is *printed* - the symbol, not a length in beats.
 *
 * Length in beats is deliberately not stored, because it is a property of the meter rather than of
 * the symbol: an eighth is half a beat in 4/4 but a third of a beat in 6/8, where the beat is the
 * dotted quarter.
 *
 * [flags] is how many flags the stem carries, which is also how many beams the note takes when it
 * is part of a beamed group - exactly what the canvas needs.
 */
enum class NoteValue(val ticks: Int, val flags: Int) {
    WHOLE(1920, 0),
    HALF(960, 0),
    QUARTER(480, 0),
    EIGHTH(240, 1),
    SIXTEENTH(120, 2);

    /** Half and whole notes are open noteheads. */
    val hollow: Boolean get() = this == WHOLE || this == HALF

    /** Whole notes have no stem. */
    val stemmed: Boolean get() = this != WHOLE
}

/**
 * A time signature, plus how it is counted. Only [top] and [bottom] are given; everything else is
 * derived, so there is no way to write down a meter whose fields disagree with each other.
 *
 * [beatTicks] does double duty: it is both the metronome click period and the cell that beams are
 * grouped inside. That is the whole reason compound meter needs no special case anywhere else.
 */
data class Meter(val top: Int, val bottom: Int) {
    val barTicks: Int = top * (TICKS_PER_WHOLE / bottom)

    /** 6/8, 9/8, 12/8 - grouped in threes and counted in dotted beats. 3/8 and 3/4 are not. */
    val isCompound: Boolean = bottom >= 8 && top % 3 == 0 && top > 3

    /** Ticks in one clock beat, i.e. one metronome click. 4/4 and 3/4 -> 480; 6/8 -> 720. */
    val beatTicks: Int =
        if (isCompound) 3 * (TICKS_PER_WHOLE / bottom) else TICKS_PER_WHOLE / bottom

    /** Clicks per bar: 4, 3, and - for 6/8 - two, not six. That is how 6/8 is actually counted,
     *  and it is what keeps the beat clock, the hit windows and anchorAt() completely unaware of
     *  compound time. An eighth simply becomes 1/3 of a beat. */
    val beatsPerBar: Int = barTicks / beatTicks

    /** What the time signature draws and what a level card shows. */
    val label: String get() = "$top/$bottom"

    companion object {
        val FOUR_FOUR = Meter(4, 4)
        val THREE_FOUR = Meter(3, 4)
        val SIX_EIGHT = Meter(6, 8)
    }
}

/**
 * One notated event. Symbolic rather than a bare duration, because the game now *draws* it: a
 * length of 1.5 beats is a dotted quarter in 4/4 and nothing writable at all in 6/8, so the symbol
 * has to be the source of truth and the duration derived from it.
 */
data class RhythmEvent(
    val value: NoteValue,
    val dots: Int = 0,
    /** 1 = normal. 3 = one of an eighth-note triplet, three in the space of two. */
    val tuplet: Int = 1,
    val isRest: Boolean = false,
    /**
     * This note is held through the next event, which is drawn and tied but never tapped. Used
     * only where no single symbol can express the length - 2.5 beats in 4/4, or a note spanning
     * both dotted-quarter groups of a 6/8 bar. Anything a dot can write uses the dot instead.
     */
    val tiedToNext: Boolean = false,
) {
    init {
        require(dots in 0..2) { "unsupported dot count $dots" }
        // The bracket says "3"; a general ratio would make it lie.
        require(tuplet == 1 || tuplet == 3) { "unsupported tuplet $tuplet" }
    }

    /** Exact length in ticks: dots multiply by 3/2 then 7/4, a triplet by 2/3. */
    val ticks: Int = run {
        var t = value.ticks * (2 * (1 shl dots) - 1) / (1 shl dots)
        if (tuplet > 1) t = t * 2 / tuplet
        t
    }
}

/**
 * A one-bar phrase. Rounds are built by drawing whole patterns, not individual durations, so a
 * round reads as a musical phrase rather than atomized random note-lengths.
 *
 * The bar-length check runs at class-init, which is exactly when a mistyped pattern should be
 * heard about: a pattern that does not fill its bar would silently push every later barline off
 * the accent for the rest of the round.
 */
data class RhythmPattern(val events: List<RhythmEvent>, val meter: Meter) {
    init {
        val sum = events.sumOf { it.ticks }
        require(sum == meter.barTicks) {
            "pattern is $sum ticks, not one bar of ${meter.label} (${meter.barTicks})"
        }
    }
}

data class RhythmLevel(
    val id: Int,
    val title: String,
    val description: String,
    val meter: Meter,
    val bpmRange: IntRange,
    val patterns: List<RhythmPattern>,
    /** Hit-window half-widths, in beats (not ms), so they scale automatically with BPM. */
    val perfectWindowBeats: Float,
    val goodWindowBeats: Float,
    /** Fraction of tap-eligible events (Perfect or Good) needed to pass. */
    val passAccuracy: Float,
    /** Extra gate on the harder levels: fraction of hits that must land in the tighter window. */
    val passPerfectRatio: Float = 0f,
)

/**
 * Beginner-to-advanced rhythm curriculum. Sequencing follows standard music-ed practice (quarters
 * -> rests -> paired eighths -> dotted rhythms -> sixteenths -> triplets -> syncopation -> mixed),
 * then leaves 4/4 for 3/4 and compound 6/8.
 *
 * Every level is playable from the start. There is no unlock order, so [RhythmLevel.id] is a
 * display order and nothing else - the pass thresholds are a verdict on a round, not a gate.
 *
 * Hit windows are beat-fractions loosely modelled on rhythm-game timing tiers (commercial games
 * run roughly +-16-30ms at their tightest "Perfect" tier) but scaled much looser for beginners and
 * only tightened toward that range at the top, since this app teaches timing rather than testing
 * trained reflexes.
 *
 * Two rules bind any new level:
 *
 * 1. **goodWindowBeats must stay under half the level's smallest note-to-note gap.** Judging walks
 *    a single `target` cursor, so a window wider than that lets a tap aimed at one note be credited
 *    to the one before it - a scoring bug that never looks like one. Sixteenths give a gap of 0.25
 *    beats, so 0.12; 6/8 eighths give 1/3 of a beat, so 0.12 there too.
 * 2. **Never renumber an existing level.** Best scores are stored under `level_{id}_best`, so a
 *    renumber silently moves every score onto the wrong level. New levels are appended.
 */
object Rhythm {
    private fun q() = RhythmEvent(NoteValue.QUARTER)
    private fun qr() = RhythmEvent(NoteValue.QUARTER, isRest = true)
    private fun h() = RhythmEvent(NoteValue.HALF)
    private fun dh() = RhythmEvent(NoteValue.HALF, dots = 1)
    private fun e() = RhythmEvent(NoteValue.EIGHTH)
    private fun er() = RhythmEvent(NoteValue.EIGHTH, isRest = true)
    private fun s() = RhythmEvent(NoteValue.SIXTEENTH)
    private fun dq() = RhythmEvent(NoteValue.QUARTER, dots = 1)
    private fun t() = RhythmEvent(NoteValue.EIGHTH, tuplet = 3) // triplet eighth

    /** Held through the following event; the tie arc is drawn and the second event is not tapped. */
    private fun tied(ev: RhythmEvent) = ev.copy(tiedToNext = true)

    // One per meter, so every pattern is checked against the bar it claims to fill.
    private fun p44(vararg events: RhythmEvent) = RhythmPattern(events.toList(), Meter.FOUR_FOUR)
    private fun p34(vararg events: RhythmEvent) = RhythmPattern(events.toList(), Meter.THREE_FOUR)
    private fun p68(vararg events: RhythmEvent) = RhythmPattern(events.toList(), Meter.SIX_EIGHT)

    // --- 4/4 ---------------------------------------------------------------------------------

    private val LEVEL_1_PATTERNS = listOf(
        p44(q(), q(), q(), q()),
        p44(h(), q(), q()),
        p44(q(), q(), h()),
        p44(h(), h()),
    )

    private val LEVEL_2_PATTERNS = LEVEL_1_PATTERNS + listOf(
        p44(qr(), q(), q(), q()),
        p44(q(), qr(), q(), q()),
        p44(q(), q(), qr(), q()),
        p44(q(), q(), q(), qr()),
        p44(h(), q(), qr()),
    )

    private val LEVEL_3_PATTERNS = listOf(
        p44(q(), q(), q(), q()),
        p44(e(), e(), q(), q(), q()),
        p44(q(), e(), e(), q(), q()),
        p44(q(), q(), e(), e(), q()),
        p44(q(), q(), q(), e(), e()),
        p44(e(), e(), e(), e(), q(), q()),
    )

    private val LEVEL_4_PATTERNS = LEVEL_3_PATTERNS + listOf(
        p44(e(), er(), q(), q(), q()),
        p44(er(), e(), q(), q(), q()),
        p44(q(), e(), er(), q(), q()),
        p44(q(), q(), er(), e(), q()),
        p44(qr(), e(), e(), q(), q()),
    )

    private val LEVEL_5_PATTERNS = listOf(
        p44(dq(), e(), q(), q()),
        p44(q(), dq(), e(), q()),
        p44(q(), q(), dq(), e()),
        p44(dq(), e(), dq(), e()),
        p44(dq(), e(), e(), e(), q()),
    )

    private val LEVEL_6_PATTERNS = listOf(
        p44(s(), s(), s(), s(), q(), q(), q()),
        p44(q(), s(), s(), s(), s(), q(), q()),
        p44(e(), s(), s(), q(), q(), q()),
        p44(s(), s(), e(), q(), q(), q()),
        p44(s(), s(), s(), s(), s(), s(), s(), s(), q(), q()),
    )

    private val LEVEL_7_PATTERNS = listOf(
        p44(t(), t(), t(), q(), q(), q()),
        p44(q(), t(), t(), t(), q(), q()),
        p44(t(), t(), t(), t(), t(), t(), q(), q()),
        p44(q(), q(), t(), t(), t(), q()),
    )

    private val LEVEL_8_PATTERNS = listOf(
        p44(e(), q(), e(), q(), q()),
        p44(q(), e(), q(), e(), q()),
        p44(e(), dq(), q(), q()),
        p44(q(), q(), e(), dq()),
        p44(e(), q(), q(), q(), e()),
        // 2.5 beats has no single symbol, so this one is a genuine tie rather than a dot.
        p44(tied(h()), e(), e(), q()),
    )

    private val LEVEL_9_PATTERNS =
        LEVEL_5_PATTERNS + LEVEL_6_PATTERNS + LEVEL_7_PATTERNS + LEVEL_8_PATTERNS

    // --- 3/4 ---------------------------------------------------------------------------------

    private val LEVEL_10_PATTERNS = listOf(
        p34(q(), q(), q()),
        p34(dh()),
        p34(h(), q()),
        p34(q(), h()),
        p34(q(), q(), qr()),
        p34(qr(), q(), q()),
    )

    private val LEVEL_11_PATTERNS = listOf(
        p34(e(), e(), q(), q()),
        p34(q(), e(), e(), q()),
        p34(q(), q(), e(), e()),
        p34(dq(), e(), q()),
        p34(q(), dq(), e()),
        p34(e(), e(), e(), e(), q()),
        p34(e(), e(), q(), qr()),
    )

    // --- 6/8 - the beat is the dotted quarter, so a bar is two beats -------------------------

    private val LEVEL_12_PATTERNS = listOf(
        p68(e(), e(), e(), e(), e(), e()),
        p68(dq(), e(), e(), e()),
        p68(e(), e(), e(), dq()),
        p68(dq(), dq()),
        p68(dh()),
    )

    private val LEVEL_13_PATTERNS = listOf(
        p68(q(), e(), q(), e()),
        p68(q(), e(), e(), e(), e()),
        p68(e(), e(), e(), q(), e()),
        p68(q(), e(), dq()),
        p68(er(), e(), e(), dq()),
        p68(e(), er(), e(), q(), e()),
        // Five eighths' worth across both groups of the bar - a tie, not a dot.
        p68(tied(dq()), q(), e()),
    )

    val LEVELS: List<RhythmLevel> = listOf(
        RhythmLevel(
            1, "Steady quarters", "One tap per beat. Just lock in with the click.",
            Meter.FOUR_FOUR, 40..60, LEVEL_1_PATTERNS,
            perfectWindowBeats = 0.12f, goodWindowBeats = 0.25f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            2, "Quarter rests", "Some beats are silent - don't tap on a rest.",
            Meter.FOUR_FOUR, 40..60, LEVEL_2_PATTERNS,
            perfectWindowBeats = 0.12f, goodWindowBeats = 0.25f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            3, "Paired eighths", "Two even taps per beat, beamed in pairs.",
            Meter.FOUR_FOUR, 50..72, LEVEL_3_PATTERNS,
            perfectWindowBeats = 0.09f, goodWindowBeats = 0.20f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            4, "Eighth rests", "Paired eighths, with one half of the pair silent.",
            Meter.FOUR_FOUR, 50..72, LEVEL_4_PATTERNS,
            perfectWindowBeats = 0.09f, goodWindowBeats = 0.20f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            5, "Dotted quarter + eighth", "A long-short pair: three quarters' worth, then one.",
            Meter.FOUR_FOUR, 60..84, LEVEL_5_PATTERNS,
            perfectWindowBeats = 0.09f, goodWindowBeats = 0.20f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            6, "Sixteenth-note groupings", "Four even taps per beat, alone or mixed with eighths.",
            Meter.FOUR_FOUR, 60..84, LEVEL_6_PATTERNS,
            perfectWindowBeats = 0.06f, goodWindowBeats = 0.12f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            7, "Eighth-note triplets", "Three even taps per beat instead of two.",
            Meter.FOUR_FOUR, 72..96, LEVEL_7_PATTERNS,
            perfectWindowBeats = 0.06f, goodWindowBeats = 0.15f, passAccuracy = 0.85f,
            passPerfectRatio = 0.60f,
        ),
        RhythmLevel(
            8, "Syncopation & ties", "Accents land off the beat - listen for the push and pull.",
            Meter.FOUR_FOUR, 72..96, LEVEL_8_PATTERNS,
            perfectWindowBeats = 0.05f, goodWindowBeats = 0.12f, passAccuracy = 0.85f,
            passPerfectRatio = 0.60f,
        ),
        RhythmLevel(
            9, "Mixed & advanced", "Everything from 5 to 8, drawn at random. The full workout.",
            Meter.FOUR_FOUR, 84..120, LEVEL_9_PATTERNS,
            perfectWindowBeats = 0.05f, goodWindowBeats = 0.12f, passAccuracy = 0.85f,
            passPerfectRatio = 0.65f,
        ),
        RhythmLevel(
            10, "Waltz time", "Three beats to a bar. Feel the accent land every third click.",
            Meter.THREE_FOUR, 50..84, LEVEL_10_PATTERNS,
            perfectWindowBeats = 0.12f, goodWindowBeats = 0.25f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            11, "Waltz with eighths", "3/4 with beamed eighths and dotted rhythms.",
            Meter.THREE_FOUR, 50..84, LEVEL_11_PATTERNS,
            perfectWindowBeats = 0.09f, goodWindowBeats = 0.20f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            12, "Compound time", "6/8: the click is a dotted quarter, so eighths come in threes.",
            Meter.SIX_EIGHT, 40..60, LEVEL_12_PATTERNS,
            perfectWindowBeats = 0.06f, goodWindowBeats = 0.12f, passAccuracy = 0.85f,
        ),
        RhythmLevel(
            13, "6/8 long-short", "The compound lilt: quarter-eighth, rests and a tie across the bar.",
            Meter.SIX_EIGHT, 50..72, LEVEL_13_PATTERNS,
            perfectWindowBeats = 0.06f, goodWindowBeats = 0.12f, passAccuracy = 0.85f,
            passPerfectRatio = 0.55f,
        ),
    )

    fun randomRound(level: RhythmLevel, bars: Int, rng: Random = Random.Default): List<RhythmEvent> =
        (0 until bars).flatMap { level.patterns[rng.nextInt(level.patterns.size)].events }
}

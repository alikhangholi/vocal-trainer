package com.barnamechi.betterpitch.music

/**
 * Everything needed to *engrave* one round of rhythm, worked out once.
 *
 * The grouping questions an engraver asks - "are these two notes in the same beat?", "does this
 * one spill past the beat?" - are equality tests on boundaries, so they are all answered in exact
 * [TICKS_PER_WHOLE] ticks. Only after the structure is settled is anything converted to the float
 * beats the scroller and the scorer use.
 *
 * This is deliberately not in the canvas. The canvas runs sixty times a second and must neither
 * allocate nor walk the whole round; a [RhythmLayout] is built once, next to the round's mutable
 * verdict arrays, and every array here is immutable after `init`. It is a value object, not state.
 */
class RhythmLayout(val events: List<RhythmEvent>, val meter: Meter) {

    val size: Int = events.size

    /** Exact start of each event, in ticks from the head of the round. */
    val onsetTick = IntArray(size)

    /** Start of each event in clock beats. One division per event, never an accumulation. */
    val onsetBeat = FloatArray(size)

    /**
     * Everything the player is expected to tap. One predicate covers both things that are drawn
     * but not played - a rest, and the far side of a tie, where the previous tap already started
     * the sound - so the frame loop and `tap()` have a single rule to skip on rather than one
     * check per exception. Never add a second.
     */
    val tapEligible = BooleanArray(size)

    /**
     * Which event's verdict an event is drawn with. It is the event itself, except for the far
     * side of a tie, which points back at the note that was actually tapped - both halves of a tie
     * are one sound and must light up as one. `verdictSource[i] == i` therefore also means "this
     * glyph owns the tap", which is what stops the timing mark being drawn twice.
     */
    val verdictSource = IntArray(size) { it }

    /**
     * First and last index of the beam group each event belongs to; both equal the index itself
     * for a note that carries its own flag or has none. Stored this way rather than as a group id
     * so the canvas can widen its visible window out to whole beam groups in O(1) - a group that
     * straddles the edge of the window must still be drawn as one beam.
     */
    val beamFirst = IntArray(size) { it }
    val beamLast = IntArray(size) { it }

    /** Same, for the members of one tuplet, so its bracket and "3" are drawn exactly once. */
    val tupletFirst = IntArray(size) { it }
    val tupletLast = IntArray(size) { it }

    val totalTicks: Int
    val totalBeats: Float
    val tapCount: Int

    init {
        var acc = 0
        for (i in 0 until size) {
            onsetTick[i] = acc
            onsetBeat[i] = acc.toFloat() / meter.beatTicks
            acc += events[i].ticks
        }
        totalTicks = acc
        totalBeats = acc.toFloat() / meter.beatTicks

        for (i in 0 until size) {
            val prev = if (i == 0) null else events[i - 1]
            val heldOver = prev != null && prev.tiedToNext && !prev.isRest
            tapEligible[i] = !events[i].isRest && !heldOver
            // Walks the chain, so a note tied through two events still resolves to the one tap.
            if (heldOver) verdictSource[i] = verdictSource[i - 1]
        }
        tapCount = tapEligible.count { it }

        groupRuns({ i -> beamBreaksBefore(i) }, { i -> beamable(i) }, beamFirst, beamLast)
        groupRuns(
            { i -> tupletBreaksBefore(i) }, { i -> events[i].tuplet > 1 }, tupletFirst, tupletLast
        )
    }

    /** Latest a tap for event [i] can still land. */
    fun deadlineOf(i: Int, goodWindowBeats: Float): Float = onsetBeat[i] + goodWindowBeats

    /** How many beams a beamed note takes, i.e. its flag count. */
    fun flagsOf(i: Int): Int = events[i].value.flags

    /** True when [i] opens a group of two or more - the one place a beam should be drawn. */
    fun isBeamGroupHead(i: Int): Boolean = beamFirst[i] == i && beamLast[i] > i

    /** True when [i] belongs to no beam group, so it carries its own flags. Note the *last* member
     *  of a group also satisfies `beamLast[i] == i`, which is why both ends must be checked. */
    fun isUnbeamed(i: Int): Boolean = beamFirst[i] == i && beamLast[i] == i

    private fun beamable(i: Int) = !events[i].isRest && events[i].value.flags > 0

    /** Which beat cell a tick falls in. In 6/8 the cell is the dotted quarter, which is exactly
     *  why the eighths group in threes there and in twos everywhere else - no special case. */
    private fun cell(tick: Int) = tick / meter.beatTicks

    /**
     * A beam starts before [i] when anything at all separates it from its neighbour: one of them
     * cannot be beamed, they sit in different beats (which, since a bar is a whole number of
     * beats, also stops a beam crossing a barline), one is a triplet and the other is not, or the
     * previous note spills out of the beat it started in.
     */
    private fun beamBreaksBefore(i: Int): Boolean {
        if (i == 0) return true
        if (!beamable(i) || !beamable(i - 1)) return true
        if (cell(onsetTick[i]) != cell(onsetTick[i - 1])) return true
        if (events[i].tuplet != events[i - 1].tuplet) return true
        val prevEnd = onsetTick[i - 1] + events[i - 1].ticks - 1
        return cell(prevEnd) != cell(onsetTick[i - 1])
    }

    private fun tupletBreaksBefore(i: Int): Boolean {
        if (i == 0) return true
        if (events[i].tuplet <= 1 || events[i - 1].tuplet != events[i].tuplet) return true
        return cell(onsetTick[i]) != cell(onsetTick[i - 1])
    }

    /**
     * Fills [first]/[last] with the extent of each maximal run of members. A run of one is left
     * pointing at itself, which is what makes a lone eighth keep its flag instead of growing a
     * one-note beam.
     */
    private fun groupRuns(
        breaksBefore: (Int) -> Boolean,
        member: (Int) -> Boolean,
        first: IntArray,
        last: IntArray,
    ) {
        var i = 0
        while (i < size) {
            if (!member(i)) { i++; continue }
            var j = i + 1
            while (j < size && !breaksBefore(j)) j++
            if (j - i >= 2) {
                for (k in i until j) { first[k] = i; last[k] = j - 1 }
            }
            i = j
        }
    }
}

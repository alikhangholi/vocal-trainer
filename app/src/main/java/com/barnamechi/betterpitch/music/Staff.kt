package com.barnamechi.betterpitch.music

import kotlin.random.Random

/**
 * Treble-staff geometry in the diatonic (letter) domain. Naturals only - the sight-reading game
 * never asks for an accidental, so there is no need to decide between C# and Db here.
 *
 * A "step" is one letter of the musical alphabet, i.e. half a staff line-gap. Steps are absolute,
 * so consecutive letters always differ by exactly 1 whatever the octave.
 */
object Staff {
    /** Diatonic index within the octave; -1 marks an accidental. */
    private val DIATONIC = intArrayOf(0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6)

    const val BOTTOM_LINE_STEP = 37 // E4
    const val MIDDLE_LINE_STEP = 41 // B4
    const val TOP_LINE_STEP = 45    // F5
    const val G_LINE_STEP = 39      // G4 - the line the treble clef curls around

    const val GAME_LOW = 60  // C4, one ledger line below the staff
    const val GAME_HIGH = 84 // C6, two ledger lines above

    private fun pc(midi: Int) = ((midi % 12) + 12) % 12

    fun isNatural(midi: Int): Boolean = DIATONIC[pc(midi)] >= 0

    /** Absolute staff step. step(60) = 35 (C4), step(71) = 41 (B4, middle line). */
    fun step(midi: Int): Int = 7 * (midi / 12) + DIATONIC[pc(midi)]

    /** The 15 naturals of C4..C6, ascending. */
    val NATURALS: IntArray = (GAME_LOW..GAME_HIGH).filter(::isNatural).toIntArray()

    /** The seven answerable pitch classes, in C-major order: C D E F G A B. */
    val ANSWER_CLASSES: IntArray = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    fun pitchClass(midi: Int): Int = pc(midi)

    /** Which staff position a note sits on. Lines are drawn at odd steps, spaces at even steps -
     *  see StaffCanvas's BOTTOM_LINE_STEP..TOP_LINE_STEP `step 2` line loop. The parity holds
     *  through ledger lines too, so this works for any natural in NATURALS unchanged. */
    enum class NoteMode { ALL, LINES, SPACES }

    fun isLine(midi: Int): Boolean = step(midi) % 2 == (BOTTOM_LINE_STEP % 2)

    fun filterMode(pool: IntArray, mode: NoteMode): IntArray = when (mode) {
        NoteMode.ALL -> pool
        NoteMode.LINES -> pool.filter(::isLine).toIntArray()
        NoteMode.SPACES -> pool.filterNot(::isLine).toIntArray()
    }

    /** Naturals within [low, high], inclusive. Used to build the game's configurable-range pool. */
    fun naturalsInRange(low: Int, high: Int): IntArray = (low..high).filter(::isNatural).toIntArray()

    /** The steppers in the range UI walk this ladder one natural letter at a time. */
    fun stepDownNatural(midi: Int): Int? = NATURALS.lastOrNull { it < midi }
    fun stepUpNatural(midi: Int): Int? = NATURALS.firstOrNull { it > midi }

    fun randomRound(count: Int, pool: IntArray, rng: Random = Random.Default): IntArray =
        IntArray(count) { pool[rng.nextInt(pool.size)] }
}

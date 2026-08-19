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

    fun randomRound(count: Int, rng: Random = Random.Default): IntArray =
        IntArray(count) { NATURALS[rng.nextInt(NATURALS.size)] }
}

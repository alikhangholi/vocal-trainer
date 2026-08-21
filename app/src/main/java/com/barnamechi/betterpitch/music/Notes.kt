package com.barnamechi.betterpitch.music

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Pure music helpers: MIDI <-> frequency, names, solfège, cents. */
object Notes {
    val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val SOLFEGE = mapOf(0 to "Do", 2 to "Re", 4 to "Mi", 5 to "Fa", 7 to "Sol", 9 to "La", 11 to "Si")

    const val LOW = 36   // C2
    const val HIGH = 84  // C6
    const val MIDDLE_C = 60 // C4

    fun midiToFreq(m: Int): Double = 440.0 * 2.0.pow((m - 69) / 12.0)
    fun freqToMidiFloat(f: Double): Double = 69.0 + 12.0 * log2(f / 440.0)
    fun name(m: Int): String = NAMES[((m % 12) + 12) % 12] + (m / 12 - 1)
    fun solfege(m: Int): String = SOLFEGE[((m % 12) + 12) % 12] ?: name(m)
    fun isBlack(m: Int): Boolean = (m % 12) in setOf(1, 3, 6, 8, 10)
    fun centsOff(f: Double, m: Int): Int = (1200.0 * log2(f / midiToFreq(m))).roundToInt()
}

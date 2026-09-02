package com.barnamechi.betterpitch.data

import android.content.Context

/**
 * Local progress for the rhythm game: the best accuracy reached on each level. The app has no
 * other storage layer - this is the one deliberate exception, since a leveled game needs its
 * scores to survive a restart.
 *
 * It records; it does not gate. Every level is playable from a fresh install, so there is no
 * unlock state to keep and nothing here can ever make a level unreachable.
 */
class RhythmProgress(context: Context) {
    private val prefs = context.getSharedPreferences("rhythm_progress", Context.MODE_PRIVATE)

    fun bestAccuracy(levelId: Int): Int = prefs.getInt(bestKey(levelId), 0)

    /** Call once a round finishes judging. Best scores only ever increase. */
    fun recordResult(levelId: Int, accuracyPercent: Int) {
        if (accuracyPercent <= bestAccuracy(levelId)) return
        prefs.edit().putInt(bestKey(levelId), accuracyPercent).apply()
    }

    /** Keyed by level id, so an existing level must never be renumbered - that would silently
     *  move every score onto the wrong level. New levels are appended. */
    private fun bestKey(levelId: Int) = "level_${levelId}_best"
}

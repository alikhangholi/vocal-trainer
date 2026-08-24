package com.barnamechi.betterpitch.data

import android.content.Context

/**
 * Local progress for the rhythm game: which level is unlocked, and the best accuracy reached on
 * each. The app has no other storage layer - this is the one deliberate exception, since a leveled
 * game needs progress to survive a restart.
 */
class RhythmProgress(context: Context) {
    private val prefs = context.getSharedPreferences("rhythm_progress", Context.MODE_PRIVATE)

    fun unlockedThrough(): Int = prefs.getInt(KEY_UNLOCKED_THROUGH, 1)

    fun bestAccuracy(levelId: Int): Int = prefs.getInt(bestKey(levelId), 0)

    /** Call once a round finishes judging. Bumps the best score and, if passed, unlocks the next
     *  level - never re-locks anything already unlocked. */
    fun recordResult(levelId: Int, accuracyPercent: Int, passed: Boolean) {
        val editor = prefs.edit()
        if (accuracyPercent > bestAccuracy(levelId)) editor.putInt(bestKey(levelId), accuracyPercent)
        if (passed && levelId + 1 > unlockedThrough()) editor.putInt(KEY_UNLOCKED_THROUGH, levelId + 1)
        editor.apply()
    }

    private fun bestKey(levelId: Int) = "level_${levelId}_best"

    private companion object {
        const val KEY_UNLOCKED_THROUGH = "unlocked_through"
    }
}
